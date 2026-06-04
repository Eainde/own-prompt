# CLAUDE.md

This repo stores the KYC ownership extraction prompts for the 5-wave pipeline (Waves 1–4 are LLM agents; Wave 5 is an internal system, not in this repo). Each LLM wave has three files:

- `system.txt` — role statement + XML-structured rules
- `user.txt` — task instruction with `{{camelCase}}` placeholders
- `schema.json` — Draft-07 JSON Schema the model must conform to

## Wave pipeline

- **Wave 1** (`ownership_extraction/`): takes a GCS document path + client entity name → extracts per-document ownership graph
- **Wave 2** (`name_normalization/`): takes all Wave 1 JSON outputs → normalizes entity and person names; adds `normalizedName`, `dedupKey`, `asciiDedupKey` to every node; one-in-one-out, no merging
- **Wave 3** (`deduplication/`): takes all Wave 2 normalized outputs → deduplicates entities using `dedupKey`/`asciiDedupKey`, merges relationships, flags cross-document conflicts; produces unified flat graph
- **Wave 4** (`organisation_chart/`): takes Wave 3 flat graph → converts to nested ownership tree; schema v0 pending internal system spec
- **Wave 5** (internal system, not in this repo): takes Wave 4 tree → identifies IBOs and UBOs

## Prompt standard

Prompts follow the csm-prompts XML standard:

- `system.txt`: plain-text role sentence, then `<role>`, `<rules>` (with `<rule id="..." priority="..." name="...">`), optional `<section name="...">`, `<directive>`
- `user.txt`: `<task>`, one tag per input variable, `<instructions>` with numbered steps
- Template variables: `{{camelCase}}` double-brace syntax
- Nullable fields in schema: `"type": "string"` (not `["string", "null"]`); `listing_proof` uses `oneOf: [null, object]`

## Graph model (all waves)

Every wave outputs four top-level arrays: `nodes` (entity identity), `edges` (ownership relationships), `cycles` (detected back-edges), `conflicts` (% disagreements). W4 converts edges to a nested `ownership_tree` but carries `cycles` and `conflicts` through unchanged.

- **Nodes**: `id`, `name`, `type`, `layer`, `listing_proof`, `data_gaps`, `exceptions` — no `parent_id`, no `ownership_percentage_direct`
- **Edges**: `owner`, `owned`, `ownership_percentage_direct`, `control`, `source`
- **Cycles**: `cycle_path` (array of ids), `detected_at` (`"W1"` or `"W3"`), `source`
- **Conflicts**: `owner`, `owned`, `conflict_description`, `value_a`, `source_a`, `value_b`, `source_b`, `resolution_strategy` (`use_higher`/`use_lower`/`use_most_recent`/`manual`), `resolved_value`

## Template variable usage

`{{clientEntityName}}` appears in Wave 1 and Wave 4 user.txt only. Waves 2 and 3 are intentionally blind to client identity — they process the graph structure uniformly. The client node is always identifiable as `layer=0`.

## Test harness

`tests/` contains a pytest suite (`test_schemas.py`) and JSON fixtures (`fixtures/w1_valid.json` through `w4_valid.json`). `conftest.py` provides a custom `Draft7Validator` that accepts `null` for `"type": "string"` fields (project convention). Run with `.venv/bin/pytest tests/ -v`.

## Key accuracy rules (non-obvious)

- **W1.R6**: If the target entity is not mentioned in a document, output a single layer-0 node with null fields and empty `edges`, `cycles`, `conflicts` arrays — never invent relationships.
- **W1.R7**: Cycle detection during extraction — maintain an ancestry path; before expanding entity X's owners, check if X's id is already in the path. If yes, record in `cycles` with `detected_at: "W1"` and stop.
- **W3.R1 step 3**: Similarity-based entity matching is conservative by default. False separate (same entity as two nodes) is recoverable; false merge (two entities collapsed) is not. Default to keeping separate.
- **W3.R7**: Post-dedup DFS cycle detection — after all merges, traverse edges in `owned → owner` direction; any back-edge not already in upstream `cycles` gets a new entry with `detected_at: "W3"` and `source: "cross-document (post-dedup)"`. W4 carries cycles through unchanged — it does not add new cycle entries (the `detected_at` enum only allows `"W1"` and `"W3"`).
- **Conflict resolution**: conflicts always have `resolution_strategy` and `resolved_value` set. Default is `use_higher`. Wave 5 consumes `resolved_value`; raw `value_a`/`value_b` are preserved for audit.
