# Planner lessons

One line per task: what the worker got wrong or what slowed it, and the spec gap behind it.
**Read this before writing every spec.** Add a line after every delegated task.

- A-10 · The worker stopped silently (exit 0, nothing written) when it tried a command not in `opencode.json`'s allow list. → Give the exact commands in the prompt (`cd services/api && ../../.tools/bin/uv.exe run pytest -q`); never rely on it choosing allowed ones.
- A-10 · The spec said "add `supabase_url`" but the setting already existed; the worker noticed and adapted. → Before writing a spec, `grep` that each name I ask it to add does not already exist.
- A-10 · Two follow-ups were not in the spec (close the client on app shutdown, percent-encode keys), so they were not done. → Ask "who calls `aclose` / who owns the lifecycle?" for every resource the task creates.
- A-10 · Worker added no tests beyond the 7 listed, and all 7 were real. → Numbered test cases with concrete inputs/outputs work; keep that format.
- A-10b · The dry run (read-only worker plan) found 4 spec problems I had missed, one a safety hazard: tests that call `create_app(settings)` can read `MONET_DATABASE_URL` from the environment and run migrations on a real DB. → Every test that builds the app must null `database_url` and `supabase_*` via `model_copy`. Always do the dry run; it costs nothing.
- A-10b · Two of my five tests passed before any change (vacuous). → For each test ask "does it fail on the old code?" If not, rewrite it or label it a regression guard.
- A-10b · Bookkeeping was unspecified (does the worker edit BACKLOG/PROGRESS, which files go in the commit, may it stage `opencode.json`?). → List the spec file (Worker report) under "Files to edit", say who updates BACKLOG/PROGRESS, and name the exact files for the commit.
- harness · Headless OpenCode ends the whole run on any permission prompt ("ask" is auto-rejected). `"*": "deny"` on bash plus `"external_directory": "deny"` turn those into recoverable errors; the worker adapts and carries on.
