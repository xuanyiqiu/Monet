"""Durable job queue on Postgres (no broker): FOR UPDATE SKIP LOCKED claims, leases, retries with backoff.

Status machine: queued -> running -> done | queued (retry, run_at in the future) | failed (attempts exhausted).
A worker that dies leaves its lease to expire; the job then becomes claimable again. Handlers must be
idempotent. Workers extend the lease with a heartbeat while a handler runs.
"""

import asyncio
import contextlib
import logging
import socket
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from uuid import UUID

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncEngine

from monet_api.db import jobs
from monet_api.documents.store import now

log = logging.getLogger(__name__)


@dataclass(slots=True)
class Job:
    id: UUID
    kind: str
    payload: dict
    attempts: int
    max_attempts: int


Handler = Callable[[dict, Job], Awaitable[None]]


class PgJobQueue:
    def __init__(self, engine: AsyncEngine, lease_s: int = 120) -> None:
        self.engine = engine
        self.lease = timedelta(seconds=lease_s)

    async def enqueue(
        self, kind: str, payload: dict, max_attempts: int = 3, run_at: datetime | None = None
    ) -> UUID:
        ts = now()
        job_id = uuid.uuid4()
        async with self.engine.begin() as c:
            await c.execute(
                sa.insert(jobs).values(
                    id=job_id,
                    kind=kind,
                    payload=payload,
                    status="queued",
                    max_attempts=max_attempts,
                    run_at=run_at or ts,
                    created_at=ts,
                    updated_at=ts,
                )  # fmt: skip
            )
        return job_id

    async def claim(self, worker_id: str) -> Job | None:
        """Atomically take the oldest runnable job (queued, or running with an expired lease)."""
        ts = now()
        runnable = sa.or_(
            sa.and_(jobs.c.status == "queued", jobs.c.run_at <= ts),
            sa.and_(jobs.c.status == "running", jobs.c.locked_until < ts),
        )
        pick = (
            sa.select(jobs.c.id)
            .where(runnable)
            .order_by(jobs.c.run_at)
            .limit(1)
            .with_for_update(skip_locked=True)
        )
        async with self.engine.begin() as c:
            job_id = (await c.execute(pick)).scalar()
            if job_id is None:
                return None
            row = (
                await c.execute(
                    sa.update(jobs)
                    .where(jobs.c.id == job_id)
                    .values(
                        status="running", locked_by=worker_id, locked_until=ts + self.lease,
                        attempts=jobs.c.attempts + 1, updated_at=ts,
                    )
                    .returning(jobs.c.id, jobs.c.kind, jobs.c.payload, jobs.c.attempts, jobs.c.max_attempts)
                )
            ).one()  # fmt: skip
        return Job(*row)

    async def heartbeat(self, job_id: UUID, worker_id: str) -> None:
        ts = now()
        async with self.engine.begin() as c:
            await c.execute(
                sa.update(jobs)
                .where(jobs.c.id == job_id, jobs.c.locked_by == worker_id, jobs.c.status == "running")
                .values(locked_until=ts + self.lease, updated_at=ts)
            )

    async def complete(self, job_id: UUID) -> None:
        async with self.engine.begin() as c:
            await c.execute(
                sa.update(jobs).where(jobs.c.id == job_id)
                .values(status="done", locked_by=None, locked_until=None, updated_at=now())
            )  # fmt: skip

    async def fail(self, job: Job, error: str) -> str:
        """Retry with exponential backoff, or give up. Returns the new status."""
        ts = now()
        exhausted = job.attempts >= job.max_attempts
        values = {"last_error": error[:1000], "locked_by": None, "locked_until": None, "updated_at": ts}
        if exhausted:
            values["status"] = "failed"
        else:
            values.update(
                status="queued", run_at=ts + timedelta(seconds=min(300, 5 * 2 ** (job.attempts - 1)))
            )
        async with self.engine.begin() as c:
            await c.execute(sa.update(jobs).where(jobs.c.id == job.id).values(**values))
        return values["status"]

    async def counts(self) -> dict[str, int]:
        async with self.engine.connect() as c:
            rows = (await c.execute(sa.select(jobs.c.status, sa.func.count()).group_by(jobs.c.status))).all()
        return {s: n for s, n in rows}


class Worker:
    def __init__(self, queue: PgJobQueue, handlers: dict[str, Handler], poll_s: float = 1.0) -> None:
        self.queue = queue
        self.handlers = handlers
        self.poll_s = poll_s
        self.id = f"{socket.gethostname()}-{uuid.uuid4().hex[:6]}"
        self._stop = asyncio.Event()
        self._task: asyncio.Task | None = None

    def start(self) -> None:
        self._task = asyncio.create_task(self._loop(), name=f"worker-{self.id}")

    async def stop(self) -> None:
        self._stop.set()
        if self._task:
            await self._task

    async def run_once(self) -> bool:
        """Claim and run a single job. Returns False when the queue was empty."""
        job = await self.queue.claim(self.id)
        if job is None:
            return False
        handler = self.handlers.get(job.kind)
        beat = asyncio.create_task(self._beat(job))
        try:
            if handler is None:
                raise RuntimeError(f"no handler for job kind {job.kind!r}")
            await handler(job.payload, job)
        except Exception as e:
            status = await self.queue.fail(job, f"{type(e).__name__}: {e}")
            log.warning("job %s (%s) attempt %d failed -> %s: %s", job.id, job.kind, job.attempts, status, e)
        else:
            await self.queue.complete(job.id)
        finally:
            beat.cancel()
        return True

    async def _beat(self, job: Job) -> None:
        interval = max(1.0, self.queue.lease.total_seconds() / 3)
        while True:
            await asyncio.sleep(interval)
            try:
                await self.queue.heartbeat(job.id, self.id)
            except Exception:
                log.exception("heartbeat failed for job %s", job.id)

    async def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                busy = await self.run_once()
            except Exception:
                log.exception("worker loop error")
                busy = False
            if not busy:
                with contextlib.suppress(TimeoutError):
                    await asyncio.wait_for(self._stop.wait(), timeout=self.poll_s)
