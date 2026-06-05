# CLAUDE.md

This repo stores the KYC ownership extraction prompts for the 5-wave pipeline (Waves 1–4 are LLM agents; Wave 5 is an internal system, not in this repo). Each LLM wave has three files:

- `system.txt` — role statement + XML-structured rules
- `user.txt` — task instruction with `{{camelCase}}` placeholders
- `schema.json` — Draft-07 JSON Schema the model must conform to

## Wave pipeline

- **Wave 1** (`ownership_extraction/`): takes GCS document path + client entity name → extracts upward-only per-document ownership graph (who OWNS the target — no subsidiaries/children). Agent reads from GCS, no document text in prompt.
- **Wave 2** (`name_normalization/`): takes all Wave 1 JSON outputs → normalizes entity and person names; adds `normalizedName`, `dedupKey`, `asciiDedupKey` to every node; one-in-one-out, no merging
- **Wave 3** (`deduplication/`): takes all Wave 2 normalized outputs → deduplicates entities using `dedupKey`/`asciiDedupKey`, merges relationships, flags cross-document conflicts; produces unified flat graph
- **Wave 4** (`organisation_chart/`): takes Wave 3 flat graph → converts to nested ownership tree; schema v0 pending internal system spec. Dual-run architecture: deterministic Java code (`OrganisationChartBuilder`) runs alongside the LLM agent, controlled by `w4.primary-strategy` flag (`llm`/`code`). `W4DualRunService` orchestrates both, `W4OutputComparator` diffs results asynchronously.
- **Wave 5** (internal system, not in this repo): takes Wave 4 tree → identifies IBOs and UBOs

## Prompt standard

Prompts follow the csm-prompts XML standard:

- `system.txt`: plain-text role sentence, then `<role>`, `<rules>` (with `<rule id="..." priority="..." name="...">`), optional `<section name="...">`, `<directive>`
- `user.txt`: `<task>`, one tag per input variable, `<instructions>` with numbered steps
- Template variables: `{{camelCase}}` double-brace syntax
- Nullable fields in schema: `"type": "string"` (not `["string", "null"]`); `listing_proof` uses `oneOf: [null, object]`

## Graph model (all waves)

Every wave outputs four top-level arrays: `nodes` (entity identity), `edges` (ownership relationships), `cycles` (detected back-edges), `conflicts` (% disagreements). W4 converts edges to a nested `ownership_tree` but carries `cycles` and `conflicts` through unchanged.

- **Nodes**: `id`, `name`, `type`, `layer`, `ownership_chain`, `listing_proof`, `data_gaps`, `exceptions` — no `parent_id`, no `ownership_percentage_direct`
- **Edges**: `owner`, `owned`, `ownership_percentage_direct`, `control`, `source`
- **Cycles**: `cycle_path` (array of ids), `detected_at` (`"W1"` or `"W3"`), `source`
- **Conflicts**: `owner`, `owned`, `conflict_description`, `value_a`, `source_a`, `value_b`, `source_b`, `resolution_strategy` (`use_higher`/`use_lower`/`use_most_recent`/`manual`), `resolved_value`

## Template variable usage

Variables match the Java agent spec (`OwnershipAgentsSpecification`):

- **Wave 1**: `{{gcsDocumentPath}}`, `{{clientEntityName}}` → output: `extractedRecords`
- **Wave 2**: `{{extractedRecords}}` → output: `normalisedEntities`
- **Wave 3**: `{{normalisedEntities}}` → output: `deduplicatedEntities`
- **Wave 4**: `{{deduplicatedEntities}}`, `{{clientEntityName}}` → output: `organisationChart`

XML tags in user.txt use snake_case of the agent output variable (e.g. `<extracted_records>`, `<normalised_entities>`, `<deduplicated_entities>`). Waves 2 and 3 are intentionally blind to client identity — they process the graph structure uniformly. The client node is always identifiable as `layer=0`.

## Test harness

`tests/` contains a pytest suite (`test_schemas.py`) and JSON fixtures (`fixtures/w1_valid.json` through `w4_valid.json`). `conftest.py` provides a custom `Draft7Validator` that accepts `null` for `"type": "string"` fields (project convention). Run with `.venv/bin/pytest tests/ -v`.

## Batch accumulator (large outputs)

Waves 1–3 use `BatchAccumulatorTool` (shared Spring bean) when `nodes` > 200. The LLM calls `submitBatch(json, "nodes")` per batch; each batch is valid schema JSON with corresponding edges/cycles/conflicts. After the last batch the LLM returns a text summary; the `BatchMergerOutputGuardrail` replaces it with the merged JSON.

- `BatchAccumulatorTool.java` at repo root is a standalone reference copy.
- Renumbering is auto-detected: numeric/`id-N` IDs get renumbered; slug-based IDs (ownership agents) are preserved.
- W4 (nested tree) does not use batching — it uses a code-only builder instead.

## W4 code-only tree builder (reference stubs)

W4's transformation is purely mechanical (W4.R6: no data invention), so a deterministic Java implementation replaces LLM output generation. Four reference Java files at repo root (alongside `BatchAccumulatorTool.java`):

- `OrganisationChartBuilder.java` — core algorithm: pre-indexes W3 graph, recursively builds nested tree using `LinkedHashSet` ancestry for O(1) cycle detection
- `W4DualRunService.java` — orchestrator: runs LLM + code concurrently via `CompletableFuture`, picks primary by `w4.primary-strategy` flag, falls back to shadow on failure
- `W4OutputComparator.java` — recursive tree diff with normalization (% formatting, children ordering, null/empty equivalence)
- `W4ComparisonResult.java` — result record: `MATCH`, `MISMATCH`, `LLM_FAILED`, `CODE_FAILED`

Design spec: `docs/superpowers/specs/2026-06-04-w4-code-only-tree-builder-design.md`
Implementation plan: `docs/superpowers/plans/2026-06-04-w4-code-only-tree-builder.md`

## Key accuracy rules (non-obvious)

- **W1.R6**: If the target entity is not mentioned in a document, output a single layer-0 node with null fields and empty `edges`, `cycles`, `conflicts` arrays — never invent relationships.
- **W1.R7**: Cycle detection during extraction — maintain an ancestry path; before expanding entity X's owners, check if X's id is already in the path. If yes, record in `cycles` with `detected_at: "W1"` and stop.
- **W1.R8**: Upward-only extraction — only extract entities that OWN the target; ignore subsidiaries, children, investees. Target entity at layer 0 always appears in `owned` field of its layer-1 edges, never in `owner`. Includes direction-determination test ("who holds shares in whom") and post-extraction validation step.
- **W3.R1 step 3**: Similarity-based entity matching is conservative by default. False separate (same entity as two nodes) is recoverable; false merge (two entities collapsed) is not. Default to keeping separate.
- **W3.R8**: Ownership chain remapping — after merging nodes, remap every ID in each node's `ownership_chain` array to canonical dedupKeys (same remapping as edge owner/owned fields).
- **W3.R7**: Post-dedup DFS cycle detection — after all merges, traverse edges in `owned → owner` direction; any back-edge not already in upstream `cycles` gets a new entry with `detected_at: "W3"` and `source: "cross-document (post-dedup)"`. W4 carries cycles through unchanged — it does not add new cycle entries (the `detected_at` enum only allows `"W1"` and `"W3"`).
- **Conflict resolution**: conflicts always have `resolution_strategy` and `resolved_value` set. Default is `use_higher`. Wave 5 consumes `resolved_value`; raw `value_a`/`value_b` are preserved for audit.
