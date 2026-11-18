# Worker agent rules (OpenCode)

You are a worker. A planner wrote a task spec for you. Read `CLAUDE.md` for project rules; they all apply to you too.

## Your job
1. Read the task spec you were given (in `docs/tasks/`). Do exactly that, nothing more.
2. Edit only the files the spec lists. If you must touch another file, stop and explain why in your summary.
3. Run the acceptance commands from the spec. Fix failures in your code, never by weakening or deleting tests.
4. Commit on the current branch with a Conventional Commit message. Never switch to or commit on `main`.
5. Append a 3–5 line summary to the spec file under `## Worker report`: what changed, test result, open doubts.

## Never
- Touch any PostgreSQL other than Monet's Docker DB. Do not run psql or any DB command at all.
- Read or print `.env` files or secrets.
- Edit `contracts/openapi.yaml`.
- Push, merge, rebase, or force anything.
- Add AI attribution to commits or code.

If the spec is ambiguous, do not guess big: write the question in `## Worker report` and stop.
