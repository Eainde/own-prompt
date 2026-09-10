# Copilot instructions for ownership-prompts

This repo stores KYC ownership-extraction prompts (`monolith/`, `monolith_critic/`) and the
SQL that deploys them into Oracle. When helping in this repo, follow the rules below.

## Oracle prompt-deployment SQL (`insert_prompt.sql`)

Applies whenever the task involves deploying / shipping / versioning a prompt into Oracle,
the `KYC_DATA_OWNER.AI_CHAT_PROMPT` table, generating or editing an `insert_prompt.sql`,
bumping a `PROMPT_VERSION`, or debugging an insert that fails, inserts 0 rows, or throws
about bind variables, `ORA-01704`, or a literal being too long.

**Do not hand-write or hand-patch this SQL. Run the generator/verifier instead:**

```bash
# generate (verifies its own output before writing — a bad statement is never produced)
python3 skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py gen <prompt-dir> --code <CW_PROMPT_CODE>

# verify ANY sql file, including one written by hand or by you
python3 skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py verify <sql-file> <prompt-dir>
```

`<prompt-dir>` holds `system.txt`, `user.txt`, `schema.json`; output defaults to
`<prompt-dir>/insert_prompt.sql`. If you ever emit or edit this SQL directly, you MUST tell
the user to run `verify` on it — decoding every literal back to source is the only check that
catches a dropped character inside a 100 KB+ statement.

### The target constrains everything
The SQL is handed to a Java `PreparedStatement` as ONE string, which accepts exactly one
statement. Therefore, in generated SQL:
- **No PL/SQL** — no `DECLARE`/`BEGIN`/`END`, no anonymous block, no `DBMS_LOB.APPEND`.
- **One statement only** — no companion `UPDATE`, no `COMMIT`.
- **No SQL\*Plus directives** (`SET DEFINE OFF`, `WHENEVER SQLERROR`, `/`) — the driver never sees them.
- **No comments** (`--` swallows a flattened one-line statement) and **no trailing semicolon**.

### Two hazards
1. **Oracle caps a SQL string literal at 4000 BYTES** (32767 is PL/SQL-only; do not assume
   `MAX_STRING_SIZE=EXTENDED`). Assemble each CLOB from pieces: `TO_CLOB(...) || TO_CLOB(...)`.
   Budget ~3000 bytes/piece; count BYTES not chars (content is UTF-8 with `§ × ü — → ≥`).
2. **`& ; : ?` are eaten by the client before Oracle sees them** (`:word` is a named bind).
   They are **encoded, not escaped** — swapped for sentinels and rebuilt with one `TRANSLATE`
   per piece. Sentinels: `~`→`CHR(10)` newline, `^`→`CHR(38)` `&`, `!`→`CHR(59)` `;`,
   `*`→`CHR(58)` `:`, `@`→`CHR(63)` `?`. Result: zero `& ; : ?` anywhere in the SQL.
   Traps: `TRANSLATE` silently DELETES chars if `to_string` is shorter than `from_string`
   (both must be length 5); a sentinel that occurs naturally in a prompt silently corrupts
   text — pick a different sentinel, never weaken the check. Escaping/doubling `:` does NOT
   work; do not pad a colon to ` : ` (it alters the stored prompt).

### Versioning: always a new row, never an UPDATE
A prompt change is a new `PROMPT_VERSION`. Only the three CLOBs are new; every config column is
inherited from the preceding version via `INSERT ... SELECT` against the current max-version row.
Column ↔ file: `SYSTEM_INSTRUCTION`←`system.txt`, `CW_PROMPT_TEXT`←`user.txt` (the USER prompt),
`CW_RESPONSE_SCHEMA`←`schema.json`. **Silent no-op**: if no row exists for the prompt code, the
insert affects 0 rows with no error — always check the update count; a brand-new code needs a
seed row first.

### Deeper detail lives in the skill
Read these when the summary above is not enough:
- `skills/oracle-prompt-sql/SKILL.md` — full procedure and the verify checklist.
- `skills/oracle-prompt-sql/references/table-contract.md` — table DDL, columns, audit trigger,
  grants, seeding a new prompt code.
- `skills/oracle-prompt-sql/references/why-these-constraints.md` — the failure each rule prevents.

## General
- The `.sql` files are GENERATED — edit prompts, then regenerate; never hand-edit the SQL.
- Nullable schema fields use `"type": "string"` (not `["string","null"]`).
- Template variables use `{{camelCase}}` double-brace syntax.
