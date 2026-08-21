# `KYC_DATA_OWNER.AI_CHAT_PROMPT` — table contract

Read this when adding a prompt code, changing which columns the insert writes, checking a
`NOT NULL`, or seeding a brand-new prompt.

## DDL

Transcribed from screenshots of the live DDL — confirm against the data dictionary if a detail
matters.

```sql
CREATE TABLE "KYC_DATA_OWNER"."AI_CHAT_PROMPT"
 ( "CW_PROMPT_CODE"          VARCHAR2(50 CHAR)  NOT NULL ENABLE,
   "CW_PROMPT_LANG"          VARCHAR2(10 CHAR),
   "CW_PROMPT_TEXT"          CLOB,
   "CREATED_BY"              VARCHAR2(128 CHAR) NOT NULL ENABLE,
   "CREATE_DATE"             TIMESTAMP (6)      NOT NULL ENABLE,
   "LAST_UPDATED_BY"         VARCHAR2(128 CHAR) NOT NULL ENABLE,
   "LAST_UPDATE_DATE"        TIMESTAMP (6)      NOT NULL ENABLE,
   "CW_RESPONSE_SCHEMA"      CLOB,
   "CW_MODEL_NAME"           VARCHAR2(100 BYTE),
   "CW_MAX_OUTPUT_TOKENS"    NUMBER(*,0),
   "CW_CALL_TIMEOUT_MILLIS"  NUMBER(*,0),
   "CW_TEMPERATURE"          NUMBER,
   "CW_TOP_P"                NUMBER,
   "CW_TOP_K"                NUMBER,
   "CW_THINKING_BUDGET"      NUMBER(*,0),
   "SYSTEM_INSTRUCTION"      CLOB,
   "PROMPT_VERSION"          NUMBER            DEFAULT 1   NOT NULL ENABLE,
   "DEFAULT_PROMPT_VERSION"  VARCHAR2(1 BYTE)  DEFAULT 'Y' NOT NULL ENABLE,
   "CW_SEED"                 NUMBER(*,0),
   "CRITIC_LOOPS"            NUMBER,
   "CW_LOCATION"             VARCHAR2(50 CHAR),
   CONSTRAINT "CHK_AI_CHAT_PROMPT_D_V"
     CHECK (DEFAULT_PROMPT_VERSION in ('Y','N')) ENABLE,
   CONSTRAINT "AI_CHAT_PROMPT_PK"
     PRIMARY KEY ("CW_PROMPT_CODE", "PROMPT_VERSION")
 );
```

**Primary key `(CW_PROMPT_CODE, PROMPT_VERSION)`** is what makes versioning work: a new version
is a new row, not an edit.

## Column roles in the insert

| Column | Where the value comes from |
|---|---|
| `CW_PROMPT_CODE` | inherited (`P.CW_PROMPT_CODE`) |
| `PROMPT_VERSION` | `P.PROMPT_VERSION + 1` |
| `CW_PROMPT_TEXT` | **`user.txt`** |
| `SYSTEM_INSTRUCTION` | **`system.txt`** |
| `CW_RESPONSE_SCHEMA` | **`schema.json`** (parse it as JSON first) |
| `CW_PROMPT_LANG`, `CW_MODEL_NAME`, `CW_MAX_OUTPUT_TOKENS`, `CW_CALL_TIMEOUT_MILLIS`, `CW_TEMPERATURE`, `CW_TOP_P`, `CW_TOP_K`, `CW_THINKING_BUDGET`, `CW_SEED`, `CRITIC_LOOPS`, `CW_LOCATION` | inherited from the previous version — **never retyped** |
| `CREATED_BY`, `LAST_UPDATED_BY` | `USER` |
| `CREATE_DATE`, `LAST_UPDATE_DATE` | `SYSTIMESTAMP` |
| `DEFAULT_PROMPT_VERSION` | **not named in the insert** |

Retyping a config column is how a model silently gets deployed at the wrong temperature or
token budget. Inheriting them via `INSERT ... SELECT` makes that impossible, which is the main
reason for the `SELECT` form beyond staying within one statement.

`DEFAULT_PROMPT_VERSION` is unused. It is deliberately omitted from the column list: it is
`NOT NULL DEFAULT 'Y'`, so the table default satisfies the check constraint on its own. Nothing
demotes older versions and nothing needs to.

## Prompt codes

| Prompt folder | `CW_PROMPT_CODE` |
|---|---|
| `monolith/` | `MONOLITH_OWNERSHIP_EXTRACTION` (29 chars) |
| `monolith_critic/` | `MONOLITH_OWNERSHIP_EXTRACTION_CRITIC` (36 chars) |

Cap is `VARCHAR2(50 CHAR)`. The script rejects a longer code rather than letting Oracle truncate.

## Seeding a brand-new prompt code

The `INSERT ... SELECT` reads the previous version. With no previous row the `SELECT` returns
nothing and **the insert affects 0 rows and raises no error** — the most dangerous failure mode
here, because it looks like success. Always check the update count.

For a new code, insert a version-1 seed row first with explicit config values (model name, token
budget, timeout, temperature and so on — whatever the caller expects), then every later change
uses the normal inheriting insert. The seed is the one and only time config is typed by hand.

## Audit trigger

`TRG_AI_CHAT_PROMPT` — `AFTER INSERT OR UPDATE OR DELETE ... FOR EACH ROW`, writing to
`KYC_DATA_OWNER.AUD_AI_CHAT_PROMPT` via `PKG_AUDIT.GC_EVENT_*`, `PKG_UTIL.OS_USER`,
`PKG_UTIL.UTC_TIME`. Every insert produces an audit row automatically — nothing to do, but be
aware it fires.

The trigger carries only the original columns (`cw_prompt_code`, `cw_prompt_lang`,
`cw_prompt_text`, `created_by`, `create_date`, `last_updated_by`, `last_update_date`) — **not**
`system_instruction` or `cw_response_schema`. So the audit trail does not capture changes to the
system prompt or the response schema. If you need that history, it has to come from version
control, not from the audit table.

## Grants

`CLM_DATA_SUPPORT_TOOL_USER` INSERT/SELECT/UPDATE · `KYC_DATA_USER` SELECT/UPDATE ·
`KYC_DATA_READ` SELECT · `KYC_ANLTCS_READ` SELECT.
