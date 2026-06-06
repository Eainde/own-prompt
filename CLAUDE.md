# CLAUDE.md

This repo stores the KYC ownership extraction prompts for the 5-wave pipeline (Waves 1–4 are LLM agents; Wave 5 is an internal system, not in this repo). Each LLM agent has three files:

- `system.txt` — role statement + XML-structured rules
- `user.txt` — task instruction with `{{camelCase}}` placeholders
- `schema.json` — Draft-07 JSON Schema the model must conform to

## Wave pipeline

- **Chart Reader** (`chart_reader/`, W1 prerequisite): takes GCS document path → outputs a literal visual description of the ownership chart(s): `entities` (with vertical/horizontal position + type_hint + associated_label) and `arrows` (with from/to/label_percentage/evidence/direction_confidence). Pure visual description, no interpretation, no graph construction. Runs BEFORE W1 in production to break the vision-hallucination loop: production saw both extractor AND critic misread the same chart and validate each other; chart_reader produces a single shared ground truth that both W1 and the W1 Critic consume as authoritative (W1.R12). Template vars: `{{gcsDocumentPath}}`. Output is passed as `{{chartStructure}}` to both extractor and critic. If chart_reader is not run (empty chartStructure), both fall back to direct PDF reading.
- **Wave 1** (`ownership_extraction/`): takes GCS document path + client entity name + chart_structure (from Chart Reader) + critic feedback → extracts upward-only per-document ownership graph (who OWNS the target — no subsidiaries/children). Per W1.R12, every node and edge must be backed by an entry in chart_structure (entities/arrows); LLM may NOT invent arrows or reverse direction. Edges' direction_proof must quote the chart_structure arrow's evidence string. Agent reads from GCS, no document text in prompt.
- **Wave 1 Critic** (`ownership_extraction_critic/`): takes W1 extraction output + GCS doc path + client entity name + chart_structure (from Chart Reader) → validates against all W1 rules (R1–R12). 11 acceptance criteria (including Branch Isolation), 7-step evaluation workflow plus `<chart_structure_authority>` and `<target_entity_authority>` preflight checks. Per W1.R12, critic uses chart_structure as the SHARED GROUND TRUTH (no independent visual re-reading) — this is what breaks the production failure mode where extractor and critic both hallucinated the same wrong chart reading and validated each other. Checks: entity completeness (LIST A owners vs LIST B subsidiaries — LIST B now includes the document's named client and chain intermediates when they sit below target_entity, per W1.R11), ownership direction + subsidiary exclusion (with arrow-citation evidence + named-client-as-parent trap detection, common error #17), chain connectivity + orphan detection, edge accuracy (% + control + direction_proof), person-owns-person prohibition, hallucination, entity classification (Cat 1/2/3 + "not confirmed" fallback), W1.R6 halt validation, cycle/conflict completeness, Category 2 exceptions, Category 3 SPV/Trust roles. Outputs per-criterion pass/fail + severity-ranked corrections + verdict (PASS/ACCEPT_WITH_NOTES/RETRY). Template vars: `{{extractionOutput}}`, `{{gcsDocumentPath}}`, `{{clientEntityName}}`.
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

- **Chart Reader**: `{{gcsDocumentPath}}` → output: `chartStructure` (entities + arrows + spatial layout). Runs once per document, output cached and passed to both extractor and critic.
- **Wave 1**: `{{gcsDocumentPath}}`, `{{clientEntityName}}`, `{{chartStructure}}` (from Chart Reader), `{{criticFeedback}}` → output: `extractedRecords`. Critic feedback loop: Java agent runs Chart Reader → extraction → critic → if RETRY, re-runs extraction with critic's areas_for_improvement injected via `{{criticFeedback}}` (empty string on first run). chart_structure is stable across retries (Chart Reader is deterministic for the same document).
- **Wave 1 Critic**: `{{extractionOutput}}`, `{{gcsDocumentPath}}`, `{{clientEntityName}}`, `{{chartStructure}}` (same as extractor) → output: verdict + evaluation_results + areas_for_improvement
- **Wave 2**: `{{extractedRecords}}` → output: `normalisedEntities`
- **Wave 3**: `{{normalisedEntities}}` → output: `deduplicatedEntities`
- **Wave 4**: `{{deduplicatedEntities}}`, `{{clientEntityName}}` → output: `organisationChart`

XML tags in user.txt use snake_case of the agent output variable (e.g. `<extracted_records>`, `<normalised_entities>`, `<deduplicated_entities>`). Waves 2 and 3 are intentionally blind to client identity — they process the graph structure uniformly. The client node is always identifiable as `layer=0`.

## Test harness

`tests/` contains a pytest suite (`test_schemas.py`) and JSON fixtures (`fixtures/w1_valid.json` through `w4_valid.json`). `conftest.py` provides a custom `Draft7Validator` that accepts `null` for `"type": "string"` fields (project convention). Run with `venv/bin/pytest tests/ -v`.

## Topology test suite

`tests/topologies/` contains 6 PDF ownership chart test cases with golden expected JSON outputs, targeting specific structural patterns that trip up the LLM:

- **T1** (`t1_simple_chain`): linear 4-node chain, 100% at each level
- **T2** (`t2_wide_fan`): 5 direct owners at 20% each (flat fan)
- **T3** (`t3_diamond`): shared parent (Omega Corp) owns both branches
- **T4** (`t4_shared_person`): same person (Khan Rashid) owns shares in 2 different holdings — primary bug case for cross-branch contamination
- **T5** (`t5_mid_chart`): target in middle of hierarchy with subsidiaries below that must be ignored
- **T6** (`t6_deep_asymmetric`): one branch 5 layers deep, another 1 layer

`tests/branch_validator.py` provides `validate_branches(actual, expected=None)` with 6 structural checks: chain consistency, direction validation, person-owns-person (W1.R9), subsidiary leakage, shared entity handling, cross-branch contamination. Returns `BranchError(check_name, message, severity)` list. `tests/test_topologies.py` validates all golden fixtures against schema + branch validator. `tests/topologies/generate_pdfs.py` regenerates all 6 PDFs deterministically.

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
- **W1.R9**: No person-owns-person edges — a Natural Person cannot be "owned" by another Natural Person. Multiple persons with percentages near each other in a diagram are peer shareholders of the same corporate entity. Extraction uses a two-pass workflow: entity inventory first (scan all entities before creating edges), then relationship mapping. Validation cross-checks inventory completeness and rejects person→person edges.
- **W1.R10**: Branch isolation — when the target has multiple direct owners, process each holding's shareholders independently. A person appearing in multiple branches gets ONE node but SEPARATE edges to each holding. The entity inventory step includes branch mapping (grouping shareholders by their holding company) before creating nodes.
- **W1.R11**: Target entity authority — the `target_entity` (`{{clientEntityName}}`) input is authoritative and overrides the document's title, file name, email subject lines, and prose emphasis. Documents are often KYC/Periodic Review files prepared FOR a different named client whose name dominates the text; that named client may be a subsidiary, parent, or unrelated entity to `target_entity`. If the document's named client sits BELOW `target_entity` in the chart (chain: target → A → B → named_client), all of A, B, and named_client are subsidiaries and must be excluded — never invert the chain to make the document's named client appear as an ancestor. Direction table evidence MUST cite specific arrows; percentage-label position is no longer an acceptable direction fallback.
- **W1.R12**: Chart_structure is authoritative — when the upstream Chart Reader has run and `{{chartStructure}}` is non-empty, both W1 and W1 Critic MUST treat its `entities` and `arrows` as ground truth. Every node and edge in W1 output must be backed by a chart_structure entry; edges' `direction_proof` must quote the arrow's `evidence` string verbatim ("From chart_structure arrow: '<evidence>'"). Direction is determined by arrow `from`→`to`, not re-derived. Arrows with `direction_confidence == "UNKNOWN"` produce no edge (record in `data_gaps`); `LOW` confidence produces an edge with a `data_gaps` warning. The critic verifies every extractor node/edge against chart_structure rather than re-reading the chart visually — this is what breaks the production failure mode where extractor and critic both hallucinated the same wrong reading and validated each other.
- **W3.R1 step 3**: Similarity-based entity matching is conservative by default. False separate (same entity as two nodes) is recoverable; false merge (two entities collapsed) is not. Default to keeping separate.
- **W3.R8**: Ownership chain remapping — after merging nodes, remap every ID in each node's `ownership_chain` array to canonical dedupKeys (same remapping as edge owner/owned fields).
- **W3.R7**: Post-dedup DFS cycle detection — after all merges, traverse edges in `owned → owner` direction; any back-edge not already in upstream `cycles` gets a new entry with `detected_at: "W3"` and `source: "cross-document (post-dedup)"`. W4 carries cycles through unchanged — it does not add new cycle entries (the `detected_at` enum only allows `"W1"` and `"W3"`).
- **Conflict resolution**: conflicts always have `resolution_strategy` and `resolved_value` set. Default is `use_higher`. Wave 5 consumes `resolved_value`; raw `value_a`/`value_b` are preserved for audit.
