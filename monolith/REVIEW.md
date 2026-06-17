# Monolith prompt — accuracy review

Source: `source.md` (OCR-extracted from `~/Downloads/ownership/extracted_text.txt`).
Split into `system.txt` / `user.txt` / `schema.json`. `system.txt` and `user.txt` use the doc's **verbatim** wording (only OCR typos fixed and noise removed), slotted into the repo XML tags; rule ids mirror the doc's own section numbers (3, 4.1, 4.2, 4.3, 5, 6, 7, 9). This file records (A) reconstruction decisions and (B) accuracy-gap findings.

---

## 0. Schema update (2026-06-16) — supersedes findings below

`schema.json` has been **replaced** with a purpose-built ownership-extraction schema (`title: "Monolith Ownership Extraction Schema"`), based on the multi-agent pipeline's W1 flat-graph model: `client_entity` + `entity_type_category` + `nodes[]` + `edges[]` + `cycles[]` + `conflicts[]` + `data_gaps[]`, plus top-level `outOfBounds` and `qaFlags` objects carried over from the CSM schema (same structure). Every node and edge carries `document_name` + `document_path`; nodes carry `layer` (grounded) and `entity_classification` (GP/LP/Fund Vehicle); edges carry `ownership_percentage_direct`, `gp_lp_role`, `control`, `source_type`, `direction_proof`.

This resolves **CRITICAL-1, CRITICAL-2, HIGH-1, and MEDIUM-1** below (the CSM/governance mismatch). The original CSM "Classified Candidates Schema" is preserved verbatim in `source.md` if it is ever needed. HIGH-2 (direct visual chart extraction reintroducing the vision-hallucination loop) still stands — it is a prompt-design issue, independent of the schema. The findings below are retained for history.

---

## A. Reconstruction decisions (OCR cleanup)

The source is OCR output and the embedded JSON schema was not valid JSON. Faithful reconstruction choices:

1. **Braces/brackets:** OCR rendered `{`/`}` as `(`/`)` and `]` as `1`. Restored to valid JSON.
2. **Top-level key `extracted records` → `extracted_records`.** Source shows a space in both the property name and the `required` list. A space in a JSON key is almost certainly an OCR artifact; used snake_case to match the repo's XML tag convention (`<extracted_records>`). **Confirm this is the intended key.**
3. **`lastName`:** property is defined `lastName` but the item `required` list spelled it `LastName`. Normalized to `lastName`.
4. **`nullable` annotations preserved verbatim** (`"nullable": true`). This is OpenAPI, not Draft-07 — Draft-07 validators ignore it, so it is harmless but non-standard. Repo convention for nullable strings is bare `"type": "string"` (see CLAUDE.md / conftest). Left as-author-wrote; normalize later if desired.
5. **Descriptions** lightly de-garbled (smart quotes, `SON`→`JSON`, `ga flags`→`QA flags`, broken token templates like `T=0.70({met/not met})`). Meaning preserved; not reworded.
6. **Excluded from system/user.txt:** pure OCR noise — `dow` (line 1), the `AaBbCcDdE…` font sample (line 126), `eview View` (190), stray `I` markers.
7. **OCR typos fixed in the verbatim text:** `regulatory filing except reference` → `excerpt reference`; the `<5%` / `<=0.01%` literals are XML-escaped as `&lt;`. Genuine source artifacts were **left as-is**: the sentence "Do NOT ignore any ownership-relevant document even if" is truncated in the source (kept truncated), and the Category 1 cross-reference to "Section 6C" has no matching 6C section in the doc (kept verbatim).
8. **Two sections numbered `4.3`** in the source (Core Principle, and Percentage Rules). Kept as separate rules with ids `4.3` and `4.3-percentage`.

`schema.json` validates as JSON (33 item properties, top-level required `extracted_records`/`outOfBounds`/`qaFlags`).

---

## B. Accuracy findings (severity-ranked)

### CRITICAL-1 — The schema is for a different task than the prompt
The prompt is an **ownership-extraction** task (layered owners, direct %, GP/LP, control, UBO/IBO, paths). The supplied schema (`title: "Classified Candidates Schema"`) is a **CSM / senior-manager governance-classification** schema: `governanceBasis`, `canonicalReasonTokens`, `transparencyCode`, `signatoryType`, `independenceStatus`, `jobTitle`, `temporalStatus`, `coverageMode`, `controlsApplied` (governance control rules, not ownership control).

The schema has **no field** for the prompt's core outputs:
- **No ownership percentage field at all** → §4.3 Percentage Rules ("extract ACTUAL %") cannot be emitted.
- **No layer field** → §4.1 (layer-by-layer Layer 0…N) cannot be emitted.
- **No owner→owned edge / relationship with direction** → the graph / "ownership flow Client → UBO" cannot be emitted.
- **No control/domination field** → §9 evidence-based control is lost (`controlsApplied` is governance-rule tags, not ownership control).
- **No structured conflict field** (both values + sources) → §4.3 Data Conflict Rule cannot be represented; `qaFlags` is only `{reason, status}` strings.
- **No listing-proof fields** (exchange/ticker/ISIN/source) → Category 2 (§6) cannot be represented.
- **No entity-category / GP-LP / fund-vehicle classification** → `itemType` is only `PERSON`/`ORGANIZATION`; §4.3 Entity Classification / §6 tagging is lost.
- **No cycle representation.**

The only ownership-relevant fields are `isUBO`, `isIBO`, and `parentName`. **As-is, the model cannot produce the ownership structure the prompt demands within this schema.** This is the headline issue: either the wrong schema was pasted, or the prompt needs an ownership-shaped schema built.

### CRITICAL-2 — `parentName` is single-valued → cannot represent multi-path / convergence
§4.3 (MULTI-PATH & CONVERGENCE RULE) mandates "capture ALL paths independently; do NOT collapse or overwrite paths." A single scalar `parentName` per record forces one parent → guaranteed collapse when an entity has multiple owners or an owner sits in multiple paths. Needs an array of (owner, %) edges, not one parentName.

### HIGH-1 — Schema assumes pre-extracted input records; prompt consumes raw documents
Many fields say "Carry from input unchanged" (`originalId`, `dedupKey`, `asciiDedupKey`, `dedupNote`, `nameAsSource`) and the items description says "Count must equal input count." That implies this schema belongs to a **downstream stage that classifies an already-extracted candidate list**. The monolith prompt's input is raw documents + client name — there is no input record list to carry these from. Those fields would be unsourced. Reinforces CRITICAL-1: this schema is from a later pipeline stage.

### HIGH-2 — Direct visual chart extraction reintroduces the vision-hallucination loop
The prompt instructs direct visual reading: "VISUAL EXTRACTION: Extract EVERY org chart box and connection." The existing pipeline deliberately removed this via the two-stage `chart_reader` (one shared visual ground truth consumed by extractor + critic) precisely because extractor and critic were both misreading charts and validating each other. The monolith collapses that safeguard. Expect the branch-contamination / direction-inversion failure modes (the reason R10/R12/bracket_members exist) to return. If charts are in scope, consider keeping a visual-grounding step or constraining visual claims.

### MEDIUM-1 — Hard-won extraction guards have no representation
R9 (no person-owns-person), R10 (branch isolation), R12 (chart authority / bracket_members), R13 (chart-vs-text reconciliation) are neither stated in the prompt nor expressible in the schema (no edges). If accuracy on shared-shareholder / bracketed charts matters, these need restating.

### LOW
- Output section 8 describes a human-readable **table** + flow, then mandates JSON — the schema holds neither; align the narrative to the schema.
- `nullable` convention deviates from repo (see Reconstruction #4).

---

## Recommendation
The prompt and schema are mismatched (CRITICAL-1/HIGH-1): the schema is a CSM governance-classification schema from a downstream stage, not an ownership-structure schema. Before this monolith can run, decide one of:
1. **Supply the correct ownership-output schema** (with layer, edges/paths with direct %, control, entity category, listing proof, conflicts) — preferred; or
2. **Confirm the CSM schema is intended** and rewrite the prompt to match a senior-manager classification task (drop the ownership/layer/% language).

Until that is resolved, the split files faithfully mirror the source but inherit the mismatch.
