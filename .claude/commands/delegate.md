---
description: Run the worker (OpenCode + free DeepSeek) on a task spec, then verify and review it
argument-hint: <task id, e.g. A-14>
---

Delegate **$ARGUMENTS** to the worker and verify the result. Spec: `docs/tasks/$ARGUMENTS.md`.

1. **Lint gate.** `.tools/bin/uv.exe run --no-project python scripts/lint_task.py docs/tasks/$ARGUMENTS.md`. If it fails, stop and fix the spec (use /plan-task rules). Never delegate a failing spec.
2. **Branch.** `git status --short` must be clean (or only the spec). Create the branch named in the spec from `main`.
3. **Dry run (read-only, free).**
   `.tools/bin/oc.cmd run --agent plan "Read docs/tasks/$ARGUMENTS.md. Do NOT edit anything. Reply with (1) a 5-bullet plan of what you will do, (2) every ambiguity, missing detail or contradiction in the spec." *> .tools/logs/$ARGUMENTS-dry.log`
   Compare its plan with the spec's intent. If its plan differs, or it names real ambiguities, fix the spec and repeat. Do not skip this.
4. **Real run.** Run from the repo root, in the background, logging to `.tools/logs/$ARGUMENTS.log`:
   `.tools/bin/oc.cmd run --thinking --title "$ARGUMENTS worker" "Implement docs/tasks/$ARGUMENTS.md exactly as written. Write code and tests with your edit tools, run the acceptance commands exactly as the spec gives them, fill in the Worker report, and commit. You are already on the right branch. Do not stop until it is committed or you have described a blocker in the Worker report."`
   If it exits with no commit, read the log tail: an auto-rejected permission ends the run silently. Fix `opencode.json` or the spec, then retry (max 3 attempts, then take the task over yourself).
5. **Verify yourself.** Do not trust the Worker report. Run the acceptance commands, read `git diff main...HEAD`, confirm no existing test was edited or weakened, and that nothing outside "Files to edit" changed.
6. **Independent review.** Spawn the `code-reviewer` agent (Sonnet, low effort) with the branch and spec path. If that agent type is unavailable, spawn a general-purpose agent with model `sonnet`, effort `low`, and the contents of `.claude/agents/code-reviewer.md` as its prompt. Do NOT pass it your own opinion of the code.
7. **Decide.** Fix real findings through a short follow-up spec to the worker (max 2 rounds) or yourself. Reject nitpicks, with a reason.
8. **Close.** Tick the BACKLOG item, append 3-5 lines to `docs/PROGRESS.md`, add one line per lesson to `docs/tasks/LESSONS.md` (what the worker got wrong or the spec gap behind it, and the rule to prevent it). Commit. Merge to `main` only if the user asked.
