# T-XX: <title>

**Branch:** feat/<id>-<slug>
**Goal:** one sentence: what exists afterwards that did not before.

<!--
Planner checklist (delete this block when filled):
- Read docs/tasks/LESSONS.md and docs/tasks/_EXAMPLE.md first.
- Read every file listed below; copy real names/signatures, do not invent them.
- grep that every name you ask the worker to add does not already exist.
- For each resource the task creates (client, file, task): who closes/owns it?
- 1-4 files, 3-8 numbered tests, each a concrete input -> output.
- No vague words (improve, properly, edge cases, etc.). Run scripts/lint_task.py.
-->

## Context (read these first)
- `path/to/file.py`: why the worker must read it.

## Files to edit
- `path/to/file.py`: what changes.
- `path/to/test_file.py`: NEW test file.

## Do not touch
Everything else, especially `contracts/openapi.yaml`, any `.env` file, migrations, and other tests.

## Behaviour (decided by planner)
- Names, signatures, status codes, error cases, defaults. Decisions, not goals.

## Tests
1. Concrete input -> expected output.
2. Concrete error case -> expected exception/status.
3. Concrete regression case.

## Acceptance
- [ ] `cd services/api && ../../.tools/bin/uv.exe run pytest -q` green (all existing tests still pass)
- [ ] `cd services/api && ../../.tools/bin/uv.exe run ruff check .` green
- [ ] Commit on the current branch: `feat(api): <summary>`

## Worker report
<!-- worker appends here -->
