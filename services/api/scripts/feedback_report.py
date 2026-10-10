"""List the most reported texts so they can be fixed in the lexicon or the prompt.

    uv run python scripts/feedback_report.py [--limit 20]

Reads MONET_DATABASE_URL from .env (Monet's own Postgres only).
"""

import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from monet_api import db  # noqa: E402
from monet_api.config import Settings  # noqa: E402


async def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=20)
    args = ap.parse_args()
    url = Settings().database_url
    if not url:
        print("MONET_DATABASE_URL is not set.", file=sys.stderr)
        return 2
    engine = db.create_engine(url)
    try:
        rows = await db.PgFeedbackRepo(engine).top_reported(args.limit)
    finally:
        await engine.dispose()
    if not rows:
        print("No feedback yet.")
    for text, n in rows:
        print(f"{n:4}  {text}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
