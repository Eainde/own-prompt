# Using the Oracle prompt-SQL skill in GitHub Copilot (IntelliJ / JetBrains)

This repo carries a Claude Code skill (`skills/oracle-prompt-sql/`) that generates and verifies
the single-statement Oracle `INSERT` used to deploy prompts into `KYC_DATA_OWNER.AI_CHAT_PROMPT`.

Copilot has no "skill" concept, so we ported it in two parts:

1. **Knowledge** → `.github/copilot-instructions.md` (committed in this repo). JetBrains Copilot
   reads it automatically once you enable custom instructions — Copilot Chat then knows the
   Oracle deployment rules.
2. **The tool** → the Python script `skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py`.
   Copilot in JetBrains cannot run it for you; you run it yourself. It is the source of truth —
   never hand-write the SQL.

The `.github/copilot-instructions.md` file ships with the repo, so **you do not create it** —
you get it on `git pull`. Each teammate only needs the local one-time setup below.

---

## One-time local setup (each teammate)

1. **Install the GitHub Copilot plugin** and sign in:
   `Settings → Plugins → Marketplace → "GitHub Copilot" → Install`, then sign in when prompted.

2. **Enable custom instructions:**
   `Settings → Languages & Frameworks → GitHub Copilot` → tick
   **"Enable custom instructions from `.github/copilot-instructions.md`"**
   (exact wording varies by plugin version).

3. **Restart IntelliJ.** Copilot reads the instructions file on startup only.

4. **Confirm Python 3 is on PATH** (needed to run the generator/verifier):
   ```bash
   python3 --version
   ```

That's it — the instructions file is already in the repo.

---

## How to use it

Open Copilot Chat in IntelliJ. Because the instructions are loaded, Copilot now applies the
Oracle rules automatically. Examples:

- *"How do I regenerate the monolith insert_prompt.sql?"*
- *"Why does this insert throw ORA-01704 / insert 0 rows?"*
- *"Review this hand-written insert_prompt.sql for the 4000-byte and bind-variable rules."*

To make Copilot read the full skill docs in a chat, reference them explicitly:

```
#file:skills/oracle-prompt-sql/SKILL.md
```

### Actually generating / verifying the SQL

Copilot gives guidance; the script does the work. Run from the **repo root**:

```bash
# generate (verifies its own output before writing — a bad statement is never produced)
python3 skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py gen <prompt-dir> --code <CW_PROMPT_CODE>

# verify any sql file, including one written by hand
python3 skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py verify <sql-file> <prompt-dir>
```

Replace the placeholders:

| Placeholder        | Example                         |
|--------------------|---------------------------------|
| `<prompt-dir>`     | `monolith` or `monolith_critic` |
| `<CW_PROMPT_CODE>` | your prompt code                |
| `<sql-file>`       | `monolith/insert_prompt.sql`    |

Real example:

```bash
python3 skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py gen monolith --code YOUR_CODE
python3 skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py verify monolith/insert_prompt.sql monolith
```

Output defaults to `<prompt-dir>/insert_prompt.sql`.

---

## Rules the instructions enforce (why the SQL looks unusual)

- The SQL is run by a Java `PreparedStatement` as ONE string → **one statement, no PL/SQL, no
  `SET DEFINE OFF`, no comments, no trailing semicolon**.
- Oracle caps a string literal at **4000 BYTES** → CLOBs are built from `TO_CLOB(...) || TO_CLOB(...)` pieces.
- `& ; : ?` are consumed by the client before Oracle sees them → they are **encoded** via a
  sentinel + `TRANSLATE`, not escaped.
- A prompt change is a **new `PROMPT_VERSION`** (`INSERT ... SELECT` inheriting config columns),
  never an in-place `UPDATE`.

Full detail: `skills/oracle-prompt-sql/SKILL.md` and the `references/` files.

---

## Gotchas

- **Restart required** after enabling custom instructions or the file is ignored.
- Copilot in JetBrains **will not run the script itself** — that's expected; run it in a terminal.
- `.sql` files are **generated** — edit the prompt (`system.txt` / `user.txt` / `schema.json`),
  then regenerate. Never hand-edit the SQL.
- Path-scoped `*.instructions.md` files are not yet supported in JetBrains, so the single
  `.github/copilot-instructions.md` applies repo-wide.
