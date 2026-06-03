# CLAUDE.md

This repo stores the KYC ownership extraction prompts for the Wave 1/2/3 pipeline. Each wave has three files:

- `system.txt` — role statement + XML-structured rules
- `user.txt` — task instruction with `{{camelCase}}` placeholders
- `schema.json` — Draft-07 JSON Schema the model must conform to

## Wave pipeline

- **Wave 1** (`wave1/`): takes a GCS document path + client entity name → extracts per-document ownership graph
- **Wave 2** (`wave2/`): takes all Wave 1 JSON outputs → normalizes entity and person names; adds `normalizedName`, `dedupKey`, `asciiDedupKey` to every node; one-in-one-out, no merging
- **Wave 3** (`wave3/`): takes all Wave 2 normalized outputs → deduplicates entities using `dedupKey`/`asciiDedupKey`, merges relationships, flags cross-document conflicts; produces unified flat graph
- **Wave 4** (`wave4/`): takes Wave 3 flat graph → converts to nested ownership tree; schema v0 pending internal system spec
- **Wave 5** (internal system, not in this repo): takes Wave 4 tree → identifies IBOs and UBOs

## Prompt standard

Prompts follow the csm-prompts XML standard:

- `system.txt`: plain-text role sentence, then `<role>`, `<rules>` (with `<rule id="..." priority="..." name="...">`), optional `<section name="...">`, `<directive>`
- `user.txt`: `<task>`, one tag per input variable, `<instructions>` with numbered steps
- Template variables: `{{camelCase}}` double-brace syntax
- Nullable fields in schema: `"type": "string"` (not `["string", "null"]`); `listing_proof` uses `oneOf: [null, object]`
