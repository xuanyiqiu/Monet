#!/usr/bin/env node
// PreToolUse guard: the user's local PostgreSQL (service postgresql-x64-17, port 5432,
// database "postgres") is their WORK dev database. Monet must never touch it.
// Monet's own DB runs in Docker on port 54329 (db "monet", user "monet").
// Exit code 2 blocks the tool call and shows stderr to the agent.

let raw = "";
process.stdin.on("data", (c) => (raw += c));
process.stdin.on("end", () => {
  let input;
  try {
    input = JSON.parse(raw);
  } catch {
    process.exit(0);
  }
  const ti = input.tool_input || {};
  const text = [ti.command, ti.content, ti.new_string, ti.file_path]
    .filter((v) => typeof v === "string")
    .join("\n")
    .toLowerCase();
  if (!text) process.exit(0);

  const rules = [
    [/(^|[^\d])5432(?!\d)/, "references port 5432 (user's work Postgres). Monet DB is on 54329."],
    [/postgresql-x64-\d+|pgagent/, "touches the user's PostgreSQL Windows service."],
    [/program files[\\/]+postgresql/, "uses the user's local PostgreSQL install. Use `docker exec monet-db psql ...` instead."],
    [/(dbname|database)\s*=\s*['"]?postgres(?![\w-])/, "targets a database named \"postgres\"."],
    [/(-d|--dbname)[\s=]+['"]?postgres(?![\w-])/, "targets a database named \"postgres\"."],
    [/:\/\/[^\s'"]*\/postgres(?![\w-])/, "connection URL points at a database named \"postgres\"."],
    [/drop\s+database|drop\s+schema\s+public/, "drops a database/schema. Do it manually if really needed."],
  ];
  for (const [re, why] of rules) {
    if (re.test(text)) {
      process.stderr.write(
        `BLOCKED by .claude/hooks/guard-db.mjs: this ${why}\n` +
          `The user's "postgres" database is their WORK dev DB and is off-limits. ` +
          `Use Monet's own DB: postgresql://monet:monet@localhost:54329/monet (docker compose service "db").\n`
      );
      process.exit(2);
    }
  }
  process.exit(0);
});
