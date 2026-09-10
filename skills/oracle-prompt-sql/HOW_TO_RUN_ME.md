# Running the Oracle prompt-SQL skill from Copilot or Gemini in IntelliJ

This repo contains a Claude Code skill (`skills/oracle-prompt-sql/`). It generates and verifies
the single-statement Oracle `INSERT` that deploys a prompt into `KYC_DATA_OWNER.AI_CHAT_PROMPT`.

Copilot and Gemini have no "skill" concept, so the skill is ported in two parts:

1. **Knowledge**
   - `SKILL.md` holds the rules, and any AI can read it.
   - `.github/copilot-instructions.md` gives Copilot the same rules automatically, once you
     enable custom instructions. Gemini doesn't read that file. The agent prompt below tells the
     AI to read `SKILL.md` itself, so the same prompt works in both assistants.
2. **The tool** is a generator/verifier that comes in **two interchangeable versions**:
   - **Python** (3.7+): `skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py`
   - **Java** (JDK 11+): `skills/oracle-prompt-sql/scripts/OraclePromptSql.java`. It runs straight
     from source, with no build, Maven or Gradle.

   Both versions produce **byte-identical SQL**, and each one verifies the other's output. The tool
   is the source of truth; never hand-write the SQL.

Everything is in the repo, so **you don't create anything**. You get it with `git pull`. Each
teammate only does the one-time local setup below.

---

## One-time local setup (each teammate)

1. **Install your AI plugin and sign in**, using either one:
   - `Settings → Plugins → Marketplace → "GitHub Copilot"`
   - `Settings → Plugins → Marketplace → "Gemini Code Assist"`

2. **Copilot only: enable custom instructions.**
   `Settings → Languages & Frameworks → GitHub Copilot` → tick
   **"Enable custom instructions from `.github/copilot-instructions.md`"**
   (the exact wording depends on the plugin version). Then **restart IntelliJ**, because Copilot
   reads the instructions file only at startup.

3. **Make sure you have Python 3.7+ or Java 11+.** You need only one of them:
   ```bash
   python3 --version     # Windows: py -3 --version
   java -version
   ```
   Most of the team already has a JDK. If `java -version` shows 11 or higher, you're done.

---

## Run the skill through the AI (Agent mode, recommended)

In **Agent mode**, the AI runs the terminal commands itself. It checks which runtime you have,
picks Python or Java, generates the SQL, verifies it, and reports back. Each command waits for
your approval before it runs.

**Switch the chat to Agent mode first.** In plain chat/ask mode, the AI cannot run commands.
- **Copilot:** in the Copilot Chat panel, change the mode dropdown from *Ask* to **Agent**.
- **Gemini Code Assist:** turn on the **Agent** toggle in the Gemini chat panel.

(The exact labels depend on the plugin version.)

Then paste this prompt, with the two values at the top filled in:

```
Run the oracle-prompt-sql skill to generate and verify the Oracle deployment INSERT.

Prompt folder: <PROMPT_DIR>
Prompt code:   <CW_PROMPT_CODE>

First read skills/oracle-prompt-sql/SKILL.md. Then work from the repo root and run each command below in the terminal yourself, in order.

1. Pick the runtime.
   Run: python3 --version   (on Windows: py -3 --version)
   Run: java -version
   - If Python 3.7 or newer is available, use Python. On Windows, write "py -3" wherever the steps say "python3".
   - Otherwise, if Java 11 or newer is available, use Java. Java runs the .java file directly; do not compile it.
   - If neither is available, stop and tell me. Do not write any SQL yourself.
   Tell me which runtime you picked and why.

2. Generate the SQL.
   Python: python3 skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py gen <PROMPT_DIR> --code <CW_PROMPT_CODE>
   Java:   java skills/oracle-prompt-sql/scripts/OraclePromptSql.java gen <PROMPT_DIR> --code <CW_PROMPT_CODE>

3. Verify the file that step 2 wrote, using the same runtime.
   Python: python3 skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py verify <PROMPT_DIR>/insert_prompt.sql <PROMPT_DIR>
   Java:   java skills/oracle-prompt-sql/scripts/OraclePromptSql.java verify <PROMPT_DIR>/insert_prompt.sql <PROMPT_DIR>

4. Report back:
   - the runtime you used
   - the path of the output file
   - the gen summary (pieces per column and statement size)
   - the verify result (PASS or FAIL)
   - every NOTE line, copied word for word

Rules:
- Never write, edit, patch or "fix" the SQL yourself. Never copy or retype the .sql file. Only the tool may produce it.
- If gen or verify exits with a non-zero code, stop. Show me the error output word for word, and use SKILL.md to explain what it means. Do not retry with a changed SQL file. Do not edit the scripts, and do not change their sentinel characters or weaken any of their checks.
- If the error says a prompt file "contains" a character that "is reserved as the sentinel", tell me the prompt uses a reserved character, and stop.
- Do not modify system.txt, user.txt or schema.json.
- Do not run git, and do not run anything against the database. Deploying the SQL is my job.
- End by reminding me: if the table has no row yet for <CW_PROMPT_CODE>, the insert affects 0 rows and raises no error, so I must check the update count after running it.
```

Replace `<PROMPT_DIR>` with `monolith` or `monolith_critic`, and `<CW_PROMPT_CODE>` with your prompt code.

Why the prompt is written this way:
- **The AI picks the runtime itself**, and tells you which one and why, so you can see it happen.
- **It fails closed:** on any error the AI stops and shows you the output. It is barred from
  "fixing" the SQL, which is the one thing a helpful model would otherwise try. A hand-patched
  literal is exactly where the corruption this tool guards against comes from.
- **It works in both Copilot and Gemini:** it doesn't depend on any assistant-specific
  instructions file.

---

## Chat mode: the AI gives you the commands, you run them

If you'd rather not use Agent mode, paste this prompt into a normal chat. The AI replies with the
commands, and you run them yourself in IntelliJ's Terminal tab:

```
Generate the Oracle deployment INSERT for the <PROMPT_DIR> prompt (prompt code: <CW_PROMPT_CODE>).

Read skills/oracle-prompt-sql/SKILL.md first. Do NOT hand-write the SQL; the generator is the source of truth. Specifically:

1. Give me the exact command to run. Use the launcher, which picks Python or Java automatically:
   macOS/Linux: skills/oracle-prompt-sql/scripts/oracle-prompt-sql.sh gen <PROMPT_DIR> --code <CW_PROMPT_CODE>
   Windows:     skills\oracle-prompt-sql\scripts\oracle-prompt-sql.cmd gen <PROMPT_DIR> --code <CW_PROMPT_CODE>
2. Tell me to run the verifier afterwards, and show me that command too.
3. Remind me of the checks it must pass: one statement / no PL/SQL / no trailing semicolon, 4000-byte literal cap via TO_CLOB() pieces, & ; : ? encoded (not escaped) via sentinel + TRANSLATE, and a new PROMPT_VERSION via INSERT...SELECT (never an UPDATE).
4. Warn me about the silent no-op: if no row exists for the prompt code, the insert affects 0 rows and raises no error.

If I explicitly ask you to hand-write or patch the SQL instead, you MUST end by telling me to run:
   skills/oracle-prompt-sql/scripts/oracle-prompt-sql.sh verify <SQL_FILE> <PROMPT_DIR>
because decoding every literal back to its source is the only check that catches a dropped character.
```

---

## Running the tool by hand (no AI)

Run these from the **repo root**. The launcher uses Python if Python is installed and falls back to Java if not:

```bash
# macOS / Linux
skills/oracle-prompt-sql/scripts/oracle-prompt-sql.sh gen <prompt-dir> --code <CW_PROMPT_CODE>
skills/oracle-prompt-sql/scripts/oracle-prompt-sql.sh verify <sql-file> <prompt-dir>
```

```bat
:: Windows (cmd or PowerShell)
skills\oracle-prompt-sql\scripts\oracle-prompt-sql.cmd gen <prompt-dir> --code <CW_PROMPT_CODE>
skills\oracle-prompt-sql\scripts\oracle-prompt-sql.cmd verify <sql-file> <prompt-dir>
```

Or call a runtime directly:

```bash
# Python
python3 skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py gen <prompt-dir> --code <CW_PROMPT_CODE>
python3 skills/oracle-prompt-sql/scripts/oracle_prompt_sql.py verify <sql-file> <prompt-dir>

# Java (JDK 11+, runs from source, no compile step)
java skills/oracle-prompt-sql/scripts/OraclePromptSql.java gen <prompt-dir> --code <CW_PROMPT_CODE>
java skills/oracle-prompt-sql/scripts/OraclePromptSql.java verify <sql-file> <prompt-dir>
```

To force a runtime through the launcher, set `ORACLE_SQL_RUNTIME=python` or `ORACLE_SQL_RUNTIME=java`.

| Placeholder        | Example                         |
|--------------------|---------------------------------|
| `<prompt-dir>`     | `monolith` or `monolith_critic` |
| `<CW_PROMPT_CODE>` | your prompt code                |
| `<sql-file>`       | `monolith/insert_prompt.sql`    |

The output goes to `<prompt-dir>/insert_prompt.sql` unless you pass `-o FILE`. `gen` verifies its
own output before it writes anything, so it never produces a bad statement. Exit codes: `0` =
OK, `1` = verification failed or input refused, `2` = bad arguments, `127` = neither Python nor
Java was found (launcher only).

---

## Rules the tool enforces (why the SQL looks unusual)

- A Java `PreparedStatement` runs the SQL as ONE string → **one statement, no PL/SQL, no
  `SET DEFINE OFF`, no comments, no trailing semicolon**.
- Oracle caps a string literal at **4000 BYTES** → each CLOB is built from `TO_CLOB(...) || TO_CLOB(...)` pieces.
- The client consumes `& ; : ?` before Oracle ever sees them → they are **encoded**, not
  escaped: each is swapped for a stand-in character that `TRANSLATE` turns back into the original.
- A prompt change is a **new `PROMPT_VERSION`** (`INSERT ... SELECT` inheriting config columns),
  never an in-place `UPDATE`.

For the full detail, see `skills/oracle-prompt-sql/SKILL.md` and the `references/` files.

---

## Gotchas

- **"The AI just printed commands instead of running them"**: the chat is in Ask/chat mode.
  Switch it to **Agent** mode.
- **Copilot:** restart IntelliJ after enabling custom instructions, or Copilot ignores the file.
  The file must be at `.github/copilot-instructions.md`; Copilot doesn't look anywhere else.
- **The `.sql` files are generated.** Edit the prompt (`system.txt` / `user.txt` / `schema.json`),
  then regenerate. Never hand-edit the SQL.
- **"contains '~', which is reserved as the sentinel…"**: the tool uses `~ ^ ! * @` as stand-ins
  (for newline, `&`, `;`, `:` and `?`), and it refuses any prompt that already contains one of
  those characters. Otherwise the stored text would be silently corrupted. Don't work around it;
  raise it with the repo owner.
- **macOS "permission denied" on the `.sh`:** run it as `sh skills/oracle-prompt-sql/scripts/oracle-prompt-sql.sh ...`,
  or run `chmod +x` on it once.
- **Windows:** the launcher prefers the official `py` launcher. The Microsoft Store `python` stub
  is detected and skipped, so the launcher falls back to Java.
- **Changing the tool:** any change to `oracle_prompt_sql.py` must also be made in
  `OraclePromptSql.java`. Afterwards, run `gen` with both and compare the outputs with
  `cmp`/`fc /b`; they must be byte-identical.
