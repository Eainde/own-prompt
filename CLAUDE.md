# CLAUDE.md

This repo stores the KYC ownership extraction prompts for the pipeline (Agents 1–4 are LLM agents in this repo; Agents 5–6 and Wave 5 are downstream). Each LLM agent has three files:

- `system.txt` — role statement + XML-structured rules
- `user.txt` — task instruction with `{{camelCase}}` placeholders
- `schema.json` — Draft-07 JSON Schema the model must conform to

## Wave pipeline

- **Chart Reader** (`chart_reader/`, W1 prerequisite): takes GCS document path → outputs a literal visual description of the ownership chart(s): `entities` (with vertical/horizontal position + type_hint + associated_label) and `arrows` (with from/to/label_percentage/evidence/direction_confidence). Pure visual description, no interpretation, no graph construction. Runs BEFORE W1 in production to break the vision-hallucination loop: production saw both extractor AND critic misread the same chart and validate each other; chart_reader produces a single shared ground truth that both W1 and the W1 Critic consume as authoritative (W1.R12). Template vars: `{{gcsDocumentPath}}`. Output is passed as `{{chartStructure}}` to both extractor and critic. Key rules: **CR.R9** — a bracket connecting multiple shareholders to one company MUST be emitted as ONE arrow PER shareholder (never a single "group" arrow). Group arrows force the extractor to guess assignments → guaranteed branch contamination. **CR.R10** — before output, run per-target sum check: each target's incoming arrow percentages should ≈100% (±5%). Sums off (e.g., 70% or 130%) signal missed or cross-assigned shareholders → iterate the chart_reader output until consistent. If chart_reader is not run (empty chartStructure), both fall back to direct PDF reading.
- **Wave 1 / Agent 1** (`ownership_extraction/`): takes GCS document path + chart_structure (from Chart Reader) + critic feedback → extracts a per-document FLAT directed ownership graph of ALL relationships in the document (both directions; no target, no layer, no ownership_chain). Fuses two sources: chart-sourced edges (backed by chart_structure per W1.R12) and text-sourced edges (non-chart prose, cited by quote). Each edge carries `source_type` (`chart_structure` | `document_text`). `entity_type_category` is per-node. No `{{clientEntityName}}`; target-grounding and layer assignment are handled downstream by Agent 5.
- **Wave 1 Critic / Agent 2** (`ownership_extraction_critic/`): takes W1 extraction output + GCS doc path + chart_structure → validates against W1 rules (R1–R12, R13). 9 acceptance criteria (removed "Layer Assignment" and "Ownership Chain Integrity"); 7-step evaluation workflow plus `<chart_structure_authority>` preflight check. Verifies chart-sourced edges against chart_structure entries; verifies text-sourced edges against cited text quotes. No target/subsidiary checks. No `{{clientEntityName}}`. Outputs per-criterion pass/fail + severity-ranked corrections + verdict (PASS/ACCEPT_WITH_NOTES/RETRY).
- **Wave 2 / Agent 3** (`name_normalization/`): takes all Wave 1 JSON outputs → normalizes entity and person names; adds `normalizedName`, `dedupKey`, `asciiDedupKey` to every node; carries per-node `entity_type_category` and edge `source_type` forward; one-in-one-out, no merging
- **Wave 3 / Agent 4 (Merge + Comparison)** (`merge_comparison/`): takes all Wave 2 normalized outputs → builds a composite graph; namespaces node ids as `<source_doc_id>::<slug>`; records `source_document` on every node and edge; tags candidate-duplicate groups in `overlaps`; flags cross-document % disagreements in `potential_conflicts`. DOES NOT MERGE OR RESOLVE — carries cycles and conflicts forward unresolved (`resolution_strategy` may be `"deferred"`).
- **Wave 4 / Agent 5** (`organisation_chart/`): takes Wave 3 composite graph → converts to nested ownership tree; schema v0 pending internal system spec. Dual-run architecture: deterministic Java code (`OrganisationChartBuilder`) runs alongside the LLM agent, controlled by `w4.primary-strategy` flag (`llm`/`code`). `W4DualRunService` orchestrates both, `W4OutputComparator` diffs results asynchronously.
- **Agent 4 — Conflict Detection** (not yet in this repo): takes Wave 3 composite graph → resolves cross-document cycle detection and conflict resolution via source priority; produces `detected_at: "W3"` cycle entries and resolved conflict records.
- **Agent 5 — Grounding** (not yet in this repo): takes client legal name + merged graph → anchors the target entity, builds the upward ownership chain, assigns `layer` and `ownership_chain` to nodes. W1.R6, W1.R8, and W1.R11 (target-not-found halt, upward-only extraction, target authority) are all implemented here, not in W1.
- **Wave 5** (internal system, not in this repo): takes Wave 4 tree → identifies IBOs and UBOs

## Prompt standard

Prompts follow the csm-prompts XML standard:

- `system.txt`: plain-text role sentence, then `<role>`, `<rules>` (with `<rule id="..." priority="..." name="...">`), optional `<section name="...">`, `<directive>`
- `user.txt`: `<task>`, one tag per input variable, `<instructions>` with numbered steps
- Template variables: `{{camelCase}}` double-brace syntax
- Nullable fields in schema: `"type": "string"` (not `["string", "null"]`); `listing_proof` uses `oneOf: [null, object]`

## Graph model (all waves)

Every wave outputs four top-level arrays: `nodes` (entity identity), `edges` (ownership relationships), `cycles` (detected back-edges), `conflicts` (% disagreements). W4 converts edges to a nested `ownership_tree` but carries `cycles` and `conflicts` through unchanged.

- **Nodes**: `id`, `name`, `type`, `entity_type_category` (per-node, W1 onward), `listing_proof`, `data_gaps`, `exceptions` — no `parent_id`, no `ownership_percentage_direct`. `layer` and `ownership_chain` are absent until the Grounding agent (Agent 5) assigns them.
- **Edges**: `owner`, `owned`, `ownership_percentage_direct`, `control`, `source`, `source_type` (`chart_structure` | `document_text`, W1 onward)
- **Cycles**: `cycle_path` (array of ids), `detected_at` (`"W1"` or `"W3"`), `source`
- **Conflicts**: `owner`, `owned`, `conflict_description`, `value_a`, `source_a`, `value_b`, `source_b`, `resolution_strategy` (`use_higher`/`use_lower`/`use_most_recent`/`manual`/`deferred`), `resolved_value`. Conflicts are carried forward unresolved through W3 (`resolution_strategy: "deferred"`); resolved by Agent 4.
- **W3 additions**: `overlaps` (candidate-duplicate node groups with namespaced ids `<source_doc_id>::<slug>`) and `potential_conflicts` (cross-document % disagreements, unresolved).

## Template variable usage

Variables match the Java agent spec (`OwnershipAgentsSpecification`):

- **Chart Reader**: `{{gcsDocumentPath}}` → output: `chartStructure` (entities + arrows + spatial layout). Runs once per document, output cached and passed to both extractor and critic.
- **Wave 1**: `{{gcsDocumentPath}}`, `{{chartStructure}}` (from Chart Reader), `{{criticFeedback}}` → output: `extractedRecords`. No `{{clientEntityName}}` — W1 is ungrounded. Critic feedback loop: Java agent runs Chart Reader → extraction → critic → if RETRY, re-runs extraction with critic's areas_for_improvement injected via `{{criticFeedback}}` (empty string on first run). chart_structure is stable across retries (Chart Reader is deterministic for the same document).
- **Wave 1 Critic**: `{{extractionOutput}}`, `{{gcsDocumentPath}}`, `{{chartStructure}}` (same as extractor) → output: verdict + evaluation_results + areas_for_improvement. No `{{clientEntityName}}`.
- **Wave 2**: `{{extractedRecords}}` → output: `normalisedEntities`
- **Wave 3**: `{{normalisedEntities}}` → output: `mergedEntities`
- **Wave 4**: `{{mergedEntities}}`, `{{clientEntityName}}` → output: `organisationChart`

XML tags in user.txt use snake_case of the agent output variable (e.g. `<extracted_records>`, `<normalised_entities>`, `<merged_entities>`). Waves 1–3 are intentionally blind to client identity — they process graph structure uniformly. Target anchoring happens in Agent 5 (Grounding).

## Test harness

`tests/` contains pytest suites (`test_schemas.py`, `test_prompt_invariants.py`, `test_topologies.py`, `test_branch_validator.py`) and JSON fixtures (`fixtures/w1_valid.json` through `w4_valid.json`). `test_prompt_invariants.py` asserts the W1/critic prompts stay de-grounded (no `{{clientEntityName}}`, no removed rule ids `W1.R6/R8/R11`). `conftest.py` provides a custom `Draft7Validator` that accepts `null` for `"type": "string"` fields (project convention). Run with `venv/bin/pytest tests/ -v`.

## Topology test suite

`tests/topologies/` contains 6 PDF ownership chart test cases with golden expected JSON outputs, targeting specific structural patterns that trip up the LLM:

- **T1** (`t1_simple_chain`): linear 4-node chain, 100% at each level
- **T2** (`t2_wide_fan`): 5 direct owners at 20% each (flat fan)
- **T3** (`t3_diamond`): shared parent (Omega Corp) owns both branches
- **T4** (`t4_shared_person`): same person (Khan Rashid) owns shares in 2 different holdings — primary bug case for cross-branch contamination
- **T5** (`t5_mid_chart`): entity in the middle of a hierarchy with entities both above AND below. Extraction is now ungrounded (no target, both directions), so the golden includes the downward edges too — they are NOT excluded (subsidiary exclusion is an Agent 5 / Grounding concern)
- **T6** (`t6_deep_asymmetric`): one branch 5 levels deep, another 1 level

`tests/branch_validator.py` provides `validate_branches(actual, expected=None)` with target-INDEPENDENT structural checks only: edge/id consistency (no dangling owner/owned endpoints), person-owns-person (W1.R9), shared-entity handling (one node, separate edges), cross-branch contamination (by edge-set comparison). The old target-relative checks (chain consistency, direction-vs-target, subsidiary leakage) were REMOVED — they are Grounding-stage (Agent 5) concerns. Returns `BranchError(check_name, message, severity)` list. `tests/test_topologies.py` validates all golden fixtures against the W1 schema + branch validator; `tests/test_branch_validator.py` unit-tests the checks. `tests/topologies/generate_pdfs.py` regenerates all 6 PDFs deterministically.

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

- **W1.R7**: Structural DFS cycle detection — during flat-graph extraction, maintain a traversal ancestry path. Before expanding entity X's neighbours, check if X's id is already in the path. If yes, record in `cycles` with `detected_at: "W1"` and stop. No target reference — purely structural.
- **W1.R9**: No person-owns-person edges — a Natural Person cannot be "owned" by another Natural Person. Multiple persons with percentages near each other in a diagram are peer shareholders of the same corporate entity. Extraction uses a two-pass workflow: entity inventory first (scan all entities before creating edges), then relationship mapping. Validation cross-checks inventory completeness and rejects person→person edges.
- **W1.R10**: Branch isolation — process each holding's shareholders independently. A person appearing in multiple branches gets ONE node but SEPARATE edges to each holding. The entity inventory step includes branch mapping (grouping shareholders by their holding company) before creating nodes.
- **W1.R12**: Chart_structure is authoritative for chart-sourced edges — when `{{chartStructure}}` is non-empty, W1 must back every chart-sourced node and edge against a chart_structure entry; edges' `direction_proof` must quote the arrow's `evidence` string verbatim ("From chart_structure arrow: '<evidence>'"). Direction is determined by arrow `from`→`to`. Arrows with `direction_confidence == "UNKNOWN"` produce no edge (record in `data_gaps`); `LOW` produces an edge with a `data_gaps` warning. Non-chart text contributes additional text-sourced edges (`source_type: "document_text"`) cited by verbatim quote. The critic verifies chart-sourced edges against chart_structure and text-sourced edges against cited text — neither re-reads the chart visually. Target-grounding (W1.R6, W1.R8, W1.R11) moved to Agent 5 (Grounding).
  - **CR.R12 bracket_members commitment**: every arrow carries a required `bracket_members` array listing all co-bracket shareholder names. All arrows pointing to the same target must have identical bracket_members; bracket_members must equal the set of `from` values for that target and match `sum_check.per_target_sums[target].shareholder_count`. Placeholders ("and N others", "...", "etc.") forbidden. The extractor verifies these invariants and refuses to create edges for targets with bracket_members inconsistencies. The critic re-verifies. This catches compensating-error contamination invisible to sum_check alone (one column +X% offset by adjacent column -X% from misassigned shareholders — both sums ≈100% but assignments wrong).
  - **Group-arrow rejection**: arrows where `arrow.from` is a collective noun ("group of individuals", "cluster", etc.) violate CR.R9. W1 must NOT decompose these by inference; affected shareholders get nodes but no edges, and `data_gaps` cites the CR.R9 failure. Critic flags this as a chart_reader bug and recommends re-run.
  - **Mechanical branch isolation (W1.R10 + W1.R12)**: For each holding H, valid shareholders = `{arrow.from where arrow.to == H}`. Extractor derives branch assignments by strict arrow filtering, NEVER by horizontal_position, surname matching, or visual proximity. Critic verifies set equality and per-holding sum ≈100%.
- **W1.R13**: Chart-vs-text reconciliation — for each edge present in both sources: if % values differ by ≤2%, take the text value; if >2% (material), create a `conflicts` entry with `resolution_strategy: "deferred"` and do NOT emit an edge with an invented resolved value. Union on existence: an edge in only one source is kept as-is with its `source_type` noted.
- **W3.R1 step 3**: Similarity-based entity matching is conservative by default. False separate (same entity as two nodes) is recoverable; false merge (two entities collapsed) is not. Default to keeping separate.
- **W3.R7**: Cross-document cycle detection moved to Agent 4 (Conflict Detection) — W3 (Merge + Comparison) does not run DFS or add `detected_at: "W3"` entries itself; it outputs the composite graph with namespaced ids for Agent 4 to traverse.
- **W3.R8**: Dropped — W3 no longer merges nodes, so ownership_chain remapping does not apply. `ownership_chain` is not present until Agent 5 (Grounding).
- **Conflict resolution**: W1 may emit `resolution_strategy: "deferred"` for material chart-vs-text % disagreements. W3 carries all conflicts forward unresolved. Agent 4 performs final resolution (`use_higher`/`use_lower`/`use_most_recent`/`manual`). Agent 5 (and ultimately Wave 5) consumes `resolved_value`; raw `value_a`/`value_b` are preserved for audit.
