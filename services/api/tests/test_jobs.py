"""Job queue tests against Monet's Postgres (docker compose up -d db; DB `monet_test`)."""

import asyncio
from collections import Counter
from datetime import timedelta

import pytest
import sqlalchemy as sa

from monet_api import db
from monet_api.documents.store import now
from monet_api.jobs import PgJobQueue, Worker
from tests.test_db import pg_url  # noqa: F401  (fixture: skips when the DB is down)

pytestmark = pytest.mark.db


@pytest.fixture
async def engine(pg_url):  # noqa: F811
    await db.upgrade(pg_url)
    eng = db.create_engine(pg_url)
    async with eng.begin() as c:
        await c.execute(sa.text("truncate jobs"))
    yield eng
    await eng.dispose()


async def test_claim_complete_and_empty_queue(engine):
    q = PgJobQueue(engine)
    assert await q.claim("w1") is None
    jid = await q.enqueue("t", {"n": 1})
    job = await q.claim("w1")
    assert job.id == jid and job.payload == {"n": 1} and job.attempts == 1
    assert await q.claim("w2") is None  # leased
    await q.complete(jid)
    assert await q.counts() == {"done": 1}


async def test_retry_with_backoff_then_fail(engine):
    q = PgJobQueue(engine)
    await q.enqueue("t", {}, max_attempts=2)
    job = await q.claim("w")
    assert await q.fail(job, "boom") == "queued"
    assert await q.claim("w") is None  # backoff: run_at is in the future
    async with engine.begin() as c:
        await c.execute(sa.text("update jobs set run_at = now() - interval '1 second'"))
    job = await q.claim("w")
    assert job.attempts == 2
    assert await q.fail(job, "boom again") == "failed"
    assert await q.counts() == {"failed": 1}


async def test_expired_lease_is_reclaimed(engine):
    q = PgJobQueue(engine, lease_s=60)
    await q.enqueue("t", {})
    await q.claim("dead-worker")
    assert await q.claim("w2") is None
    async with engine.begin() as c:
        await c.execute(sa.text("update jobs set locked_until = now() - interval '1 second'"))
    job = await q.claim("w2")
    assert job is not None and job.attempts == 2


async def test_heartbeat_extends_lease_only_for_owner(engine):
    q = PgJobQueue(engine, lease_s=60)
    jid = await q.enqueue("t", {})
    await q.claim("w1")
    async with engine.begin() as c:
        await c.execute(sa.text("update jobs set locked_until = now() + interval '1 second'"))
    await q.heartbeat(jid, "intruder")
    await q.heartbeat(jid, "w1")
    async with engine.connect() as c:
        until = (await c.execute(sa.text("select locked_until from jobs"))).scalar()
    assert until > now() + timedelta(seconds=30)


async def test_concurrent_workers_run_each_job_exactly_once(engine):
    q = PgJobQueue(engine)
    for i in range(40):
        await q.enqueue("count", {"i": i})
    seen: Counter = Counter()

    async def handler(payload, job):
        seen[payload["i"]] += 1
        await asyncio.sleep(0.005)

    workers = [Worker(q, {"count": handler}, poll_s=0.05) for _ in range(5)]
    for w in workers:
        w.start()
    for _ in range(200):
        if (await q.counts()).get("done") == 40:
            break
        await asyncio.sleep(0.05)
    for w in workers:
        await w.stop()
    assert sorted(seen) == list(range(40)) and set(seen.values()) == {1}
    assert await q.counts() == {"done": 40}


async def test_worker_handles_failures_and_unknown_kinds(engine):
    q = PgJobQueue(engine)
    await q.enqueue("flaky", {}, max_attempts=1)
    await q.enqueue("mystery", {}, max_attempts=1)

    async def flaky(payload, job):
        raise RuntimeError("nope")

    w = Worker(q, {"flaky": flaky})
    assert await w.run_once() and await w.run_once() and not await w.run_once()
    assert await q.counts() == {"failed": 2}
    async with engine.connect() as c:
        errors = {r[0] for r in (await c.execute(sa.text("select last_error from jobs"))).all()}
    assert any("RuntimeError: nope" in e for e in errors) and any("no handler" in e for e in errors)
