# Planner lessons

One line per task: what the worker got wrong or what slowed it, and the spec gap behind it.
**Read this before writing every spec.** Add a line after every delegated task.

- A-10 · The worker stopped silently (exit 0, nothing written) when it tried a command not in `opencode.json`'s allow list. → Give the exact commands in the prompt (`cd services/api && ../../.tools/bin/uv.exe run pytest -q`); never rely on it choosing allowed ones.
- A-10 · The spec said "add `supabase_url`" but the setting already existed; the worker noticed and adapted. → Before writing a spec, `grep` that each name I ask it to add does not already exist.
- A-10 · Two follow-ups were not in the spec (close the client on app shutdown, percent-encode keys), so they were not done. → Ask "who calls `aclose` / who owns the lifecycle?" for every resource the task creates.
- A-10 · Worker added no tests beyond the 7 listed, and all 7 were real. → Numbered test cases with concrete inputs/outputs work; keep that format.
