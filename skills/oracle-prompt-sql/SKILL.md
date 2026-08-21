---
name: oracle-prompt-sql
description: Convert LLM prompt files (system.txt, user.txt, schema.json) into the single-statement Oracle INSERT for KYC_DATA_OWNER.AI_CHAT_PROMPT, and verify a generated or hand-written one. Use this skill whenever someone asks to deploy, ship, publish, load, or version a prompt into Oracle or the AI_CHAT_PROMPT table, regenerate or edit an insert_prompt.sql, bump a PROMPT_VERSION, or debug why a prompt insert fails, inserts 0 rows, or throws about bind variables, missing IN/OUT parameters, ORA-01704, or a string literal being too long. Also use it whenever you are about to hand-write SQL that embeds a large prompt or JSON schema as a CLOB literal — the constraints here are not obvious and the natural solution (a PL/SQL block, or escaping quotes) is wrong for this target.
---

# Oracle prompt deployment SQL

Turn a prompt folder into ONE `INSERT ... SELECT` that a Java `PreparedStatement` can run.

## Do this first

```bash
# generate (verifies before writing — a bad statement is never produced)
python3 scripts/oracle_prompt_sql.py gen <prompt-dir> --code <CW_PROMPT_CODE>

# verify anything, including SQL you or a model wrote by hand
python3 scripts/oracle_prompt_sql.py verify <sql-file> <prompt-dir>
```

`<prompt-dir>` holds `system.txt`, `user.txt` and `schema.json`. Output defaults to
`<prompt-dir>/insert_prompt.sql`.

**Prefer the script over writing SQL yourself.** Not because hand-writing is forbidden, but
because the output is a 100 KB+ statement in which a single dropped character is invisible,
and the script's verifier decodes every literal back and compares it to the source. If you
hand-write or patch a statement, run `verify` on it — that is the only check that catches a
corrupted literal.

## The target, and why it constrains everything

The SQL is handed to a **Java `PreparedStatement` as one string**, which accepts **exactly one
statement**. There is no SQL\*Plus, no SQLcl, no script runner. Everything below follows from
that one fact:

- no PL/SQL — no `DECLARE` / `BEGIN` / `END`, no anonymous block, no `DBMS_LOB.APPEND`
- no second statement — no companion `UPDATE`, no `COMMIT`
- no SQL\*Plus directives (`SET DEFINE OFF`, `WHENEVER SQLERROR`, `/`) — those are *client*
  commands the driver never sees
- no comments — `--` would swallow the rest of a statement that gets flattened to one line
- no trailing semicolon — Oracle JDBC rejects it

A PL/SQL block with `DBMS_LOB.APPEND` is the natural answer to "how do I build a 110 KB CLOB",
and an earlier iteration of this work used one. It was correct and **unusable**. Don't go back
to it.

## The two hazards

### 1. Oracle caps a SQL string literal at 4000 BYTES

The 32767 figure is **PL/SQL only** and does not apply here; `MAX_STRING_SIZE=EXTENDED` is not
safe to assume. A 110 KB `system.txt` cannot be one literal, so each CLOB is assembled from
pieces:

```sql
TO_CLOB(...) || TO_CLOB(...) || TO_CLOB(...)
```

Concatenating `TO_CLOB` values yields a CLOB, so the *result* is uncapped even though each piece
is not. Budget **3000 bytes** per piece — headroom for a multi-byte character landing on a
boundary. Count **bytes, not characters**: the content is UTF-8 with `§ × ü — → ≥` in it.

### 2. Four characters the client eats before Oracle sees them

| Char | Why it breaks |
|---|---|
| `&` | substitution-variable marker |
| `;` | statement terminator |
| `:` | **named bind** — `:word` is consumed by the Oracle driver and by Spring's `NamedParameterJdbcTemplate` |
| `?` | positional bind marker |

Prompt text is full of them (hundreds of `:` alone), so they are **encoded, not escaped**. Each
is swapped for a sentinel character verified absent from every source file, and Oracle rebuilds
the original with one `TRANSLATE` per piece:

```sql
TO_CLOB(TRANSLATE('...','~^!*@',CHR(10)||CHR(38)||CHR(59)||CHR(58)||CHR(63)))
```

| Sentinel | Restores |
|---|---|
| `~` | `CHR(10)` newline |
| `^` | `CHR(38)` `&` |
| `!` | `CHR(59)` `;` |
| `*` | `CHR(58)` `:` |
| `@` | `CHR(63)` `?` |

`TRANSLATE` is a 1:1 character map, so encoding costs nothing in size and one call covers all
five. The result: **zero `&`, `;`, `:`, `?` anywhere in the generated SQL.** Nothing can misread
`:word` because there is no `:` left to find.

Newlines ride the same mechanism, so no literal spans a line and the whole statement can be
flattened without changing meaning.

Two traps worth knowing before you touch the sentinel scheme:

- **`TRANSLATE` silently DELETES characters when `to_string` is shorter than `from_string`.** It
  does not error. Both must be length 5; the verifier asserts it.
- **A sentinel that also occurs naturally in the prompt silently corrupts the stored text.** The
  script fails loudly if a prompt edit introduces one. If that fires, pick a different sentinel —
  don't weaken the check.

Escaping is not an alternative here. Doubling or backslashing `:` does not help, because the
*client* strips the bind before Oracle parses anything. And do not "fix" a colon by padding it to
` : ` — that alters the stored prompt.

## Versioning: always a new row, never an UPDATE

A prompt change is a new `PROMPT_VERSION`. Only the three CLOBs are new; **every config column is
inherited from the immediately preceding version and must never be retyped.** `INSERT ... SELECT`
against the current max-version row does both jobs and keeps it to one statement:

```sql
SELECT P.CW_PROMPT_CODE, P.PROMPT_VERSION + 1, P.CW_MODEL_NAME, ...
  FROM KYC_DATA_OWNER.AI_CHAT_PROMPT P
 WHERE P.CW_PROMPT_CODE = '<code>'
   AND P.PROMPT_VERSION = (SELECT MAX(PROMPT_VERSION) FROM ... WHERE CW_PROMPT_CODE = '<code>')
```

**Watch for the silent no-op**: if no row exists for that prompt code, the `SELECT` returns
nothing and the insert affects **0 rows with no error**. Always check the update count. A
brand-new prompt code needs a seed row with explicit config values first — see
`references/table-contract.md`.

Column ↔ file mapping (easy to invert — `CW_PROMPT_TEXT` is the *user* prompt):

| Column | File |
|---|---|
| `SYSTEM_INSTRUCTION` | `system.txt` |
| `CW_PROMPT_TEXT` | `user.txt` |
| `CW_RESPONSE_SCHEMA` | `schema.json` |

## Verifying

Run `verify` and read the output. It performs every check that has caught a real bug:

1. each CLOB, decoded through `TRANSLATE`, is **byte-identical** to its source file
2. zero `&`, `;`, `:`, `?` across the whole statement, and no `:\w` bind pattern
3. largest literal **value** ≤ 4000 UTF-8 bytes (measure the decoded value — doubled quotes
   inflate the source text and give a wrong answer)
4. `TRANSLATE` `from_string` and `to_string` are the same length
5. INSERT column count == SELECT item count
6. every `NOT NULL` column is supplied or table-defaulted
7. no raw newline inside a literal
8. exactly one `INSERT INTO`, no PL/SQL tokens, no comment tokens, no trailing semicolon
9. `schema.json` parses as JSON before it is embedded

`verify` separates **failures** (would store wrong data, or stop the statement running) from
**NOTEs** (runs correctly as-is, but fragile). Exit code follows failures only. Two NOTEs are
common and worth understanding rather than ignoring:

- **Literals spanning a line.** Safe to run, but a tool that reflows the file would change the
  stored text. Encoding newlines as `~` → `CHR(10)` removes the hazard, which is why the
  generator does it.
- **Non-ASCII characters are NOT encoded.** Only `&` `;` `:` `?` go through the sentinel map.
  Accents, dashes, currency and maths symbols travel as real characters and survive only if the
  connection charset is **AL32UTF8** — on a single-byte charset the driver silently converts them
  to `?`. No SQL-side check can catch a driver-side downgrade, so confirm `NLS_CHARACTERSET`
  before a first deploy to an unfamiliar database, and spot-check `DBMS_LOB.GETLENGTH` against
  the source byte count afterwards. This is the one way a verified statement can still store the
  wrong thing.

Two habits that keep the checks honest:

- **Match CLOBs to source files by the INSERT column list, not by position.** A statement may
  name the three CLOB columns in any order. Assuming one order reports a false mismatch on
  perfectly valid SQL — and a verifier that cries wolf gets ignored.
- Checks 5, 6 and 8 **strip string literals before counting**. Prompt text is full of commas,
parens and semicolons that mean nothing to the parser, and a naive counter reports nonsense.
If you write your own check, strip literals first.

Chunk boundaries are not meaningful — two correct statements can split the same text at
different points. Compare **decoded content**, never the SQL text, when asking "is this the same
prompt?".

## Reference files

- `references/table-contract.md` — table DDL, all columns, the audit trigger, grants, seeding a
  new prompt code. Read when adding a prompt code, changing which columns are written, or
  checking a `NOT NULL`.
- `references/why-these-constraints.md` — the failure each rule prevents, and the approaches
  that were tried and rejected. Read before relaxing any rule above, or when a reviewer asks why
  the SQL looks unusual.
