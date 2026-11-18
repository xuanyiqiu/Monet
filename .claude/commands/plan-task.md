---
description: Write a worker task spec for a backlog item (docs/tasks/<ID>.md) and lint it
argument-hint: <backlog id, e.g. A-14>
---

Write the task spec for backlog item **$ARGUMENTS**. You are the planner: your job is the design decisions, the worker only types them out.

Follow these steps in order. Do not skip any.

1. Read `docs/tasks/LESSONS.md` and `docs/tasks/_EXAMPLE.md` (the format that worked).
2. Read the backlog line for $ARGUMENTS in `docs/BACKLOG.md`, and `docs/ARCHITECTURE.md` if it touches structure. Never plan a task that needs a change to `contracts/openapi.yaml` (write a "Contract request" in `docs/PROGRESS.md` instead and stop).
3. Read every source file the task will touch, plus the test file nearest to it. Copy real names and signatures.
4. For every name, setting or file the worker must add: `grep` that it does not already exist.
5. For every resource the task creates (client, file, process, cache): decide who owns and closes it, and write it in the spec.
6. If the task is bigger than 1-4 files or 3-8 test cases, split it: write `<ID>a.md`, `<ID>b.md`, each independently testable. Say so to the user.
7. Copy `docs/tasks/_TEMPLATE.md` to `docs/tasks/$ARGUMENTS.md`, fill it with decisions (signatures, status codes, error cases, defaults), and delete the planner checklist comment.
8. Lint: `.tools/bin/uv.exe run --no-project python scripts/lint_task.py docs/tasks/$ARGUMENTS.md`. Fix every ERROR. Read each WARN and either fix it or accept it knowingly.
9. Finish with a 5-line summary for the user: goal, files, test count, design decisions that are most debatable, anything you were unsure about. Do not delegate yet; the user (or `/delegate`) does that.

Quality bar: a different engineer who has never seen this repo must be able to implement the spec without asking a question.
