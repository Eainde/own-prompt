# Monolith prompt — accuracy review

Source: `source.md` (OCR-extracted from `~/Downloads/ownership/extracted_text.txt`).
Split into `system.txt` / `user.txt` / `schema.json`. `system.txt` and `user.txt` use the doc's **verbatim** wording (only OCR typos fixed and noise removed), slotted into the repo XML tags; rule ids mirror the doc's own section numbers (3, 4.1, 4.2, 4.3, 5, 6, 7, 9). This file records (A) reconstruction decisions and (B) accuracy-gap findings.

---

## LATEST (2026-07-27) — prompt now backs the schema

Following the v39 + schema revision below and the sync audit (`monolith/prompt-schema-sync-analysis.md`),
the schema was curated and the prompt rewritten to close the gap in both directions. Design:
`docs/superpowers/specs/2026-07-27-monolith-prompt-backs-schema-design.md`.

**Schema curation:**
- **Removed 9 CSM-only fields** (no prompt basis, orphan/required): `accumulatedOwnership`,
  `accumulatedVotingRights`, `confidenceScore`, `canonicalReasonTokens`, `temporalStatus`,
  `scopeTag`, `currencyTag`, `transparencyCode`, `negativeSignals`.
- **Added 5 Tier-1 per-record fields:** `layer`, `relationshipType`, `roleCapacity`,
  `sourceClass`, `evidenceSnippet`.
- **Added 9 image-directed per-record fields:** `listingProof`, `dominationIndicator`,
  `controlRights`, `ownershipVotingMismatch`, `controlBasedUBOAssessmentRequired`,
  `localOverlayApplied`, `thresholdApplied`, `inclusiveThreshold`, `drillDownRequired`.
- **Added 2 top-level fields:** `ownershipApproach`, `outbound` (block).
- **`itemType` enum** changed `INDIVIDUAL/ORGANIZATION` → `Natural Person`/`Non Natural Person`.

**Prompt:** gained an `OUTPUT-CONTRACT` rule in `system.txt` naming every FINAL schema field
and its instruction — every property now has a documented source in the prompt. Remaining
prompt outputs that don't get their own field are explicitly folded into an existing one: the
full conflict record (value_a/source_a/value_b/source_b/resolution_strategy/resolved_value)
→ `qaFlags`; `dataGaps`/`outboundSummary`/`SOURCE_DATA_NOT_AVAILABLE` → `outOfBounds`; the
eight-part reasoning (§18) → the single `governanceBasis` string. The §19 Final Ownership
Review Summary was subsequently **removed entirely** from the prompt (not needed): its
cross-references in `system.txt` (KERNEL-F, processing-order step 17) and the `user.txt` block
were deleted; `qaFlags.summary` / `outbound.summary` now carry only their native QA / outbound
summaries.

**Known limitation (accepted, not a bug):** `actualOwnershipPercentage` /
`actualVotingRightsPercentage` stay numeric (0–100) per the locked decision, so a missing %
cannot be written as the literal `"Not Available"` — it is emitted as `0` plus a
`SOURCE_DATA_NOT_AVAILABLE` entry in `outOfBounds` and a `MISSING_PERCENTAGE` qaFlag to
distinguish it from a real 0%.

**`schema.json` now intentionally diverges from `schema.source.txt`** — the source file stays
the raw supplied record; `schema.json` is a deliberately curated extension of it, documented
here rather than a straight reconstruction.

---

## LATEST (2026-07-27) — prompt v39 + schema revision

The user supplied v39 of the prompt (`OWNERSHIP_EXTRACTOR_CLASSIFIER_V39 - KOS v8.0a aligned`)
and a minor schema revision. Applied to the directory:
- `source.md` = v39 prompt verbatim; `schema.source.txt` = the supplied schema raw (verbatim, garble kept).
- `system.txt` = v39 split: `<role>` + `<rules>` (KERNEL invariants A–G, ORGCHART upward-default,
  INPUT-VALIDATION, PROCESSING-ORDER, §1–§18) + `<section name="EXAMPLES">` (§20) + `<directive>`.
- `user.txt` = `<task>` + the 10 input tags + `<instructions>` (§19 Final Ownership Review Summary + JSON-only).
- `schema.json` = supplied revision, cleanly reconstructed.

**What v39 adds vs v36:** Cross-Agent Invariants Kernel (no external knowledge / strict client
anchor / documentary-evidence-only / zero data loss / determinism / output discipline / H1-H2-H3
source language); 10 required inputs (was 2); 17-step processing order; Global-vs-Streamlined
methodology; KOS/Local-Addenda overlay + jurisdiction threshold guide; dilution & domination;
expanded entity-type logic (listed stop rule, MOS, Gov/SOE, Trust, Foundation, LP GP look-through,
SPV); new roleCapacity taxonomy (Notional UBO / Control-Based / etc.); document reconciliation &
conflict handling; Outbound Assessment; exception-only QA flags; completeness gate; eight-part
reasoning; §19 Final Ownership Review Summary; §20 examples.

**Schema revision applied (minor):** `parentName` → `linkedName`; `itemType` `PERSON` → `INDIVIDUAL`
(`["INDIVIDUAL","ORGANIZATION"]`); `confidenceScore` formula `round(clamp(sum(positives) -
sum(negatives),0,1) + consensus_multiplier, 2)` ("5D" scoring); descriptions reworded
PERSON→INDIVIDUAL. OCR garble in the supplied source (`_CHANGING`, `•CP-XX*•`, `MANTIS: branch`,
truncated `canonicalReasonTokens`, mangled `controlsApplied`) restored to clean forms in
`schema.json`; the raw garble is preserved in `schema.source.txt`.

**Persisting prompt↔schema gap (revision does NOT close it):** `schema.json` is still the CSM-style
"Ownership Structure Candidates Schema" with a single `linkedName` link and a 7-part `governanceBasis`.
It has no field for: Global/Streamlined methodology, Outbound Assessment, KOS/local overlay +
thresholds, the roleCapacity taxonomy (Notional UBO / Control-Based), the **eight-part** reasoning
(§18), the **§19 Final Ownership Review Summary**, H1/H2/H3 source classification, client-anchor
validation status, dilution links (multi-path/convergence still collapses to one `linkedName`),
listing-proof fields (exchange/ticker/ISIN), and cycle/loop representation. The title says
"JSON ONLY" yet §18/§19 describe narrative/structured prose the schema cannot hold — unresolved.

**Critic:** refreshed to v39 vocabulary with **full v39 criteria**; because the extractor schema
is unchanged in structure, criteria targeting v39 concepts with no schema field are **advisory**
until a v39-shaped schema exists.

**Recommendation:** supply a v39-shaped ownership output schema (layer/edges with direct %, control,
roleCapacity, listing proof, conflicts, outbound, eight-part reasoning, §19 summary). `example.json`
stays stale until then (and is now staler due to the `parentName`→`linkedName` rename).

---

## LATEST (2026-06-23) — prompt v36 + "Ownership Structure Candidates Schema"

The user supplied a new canonical prompt (`ownership_prompt_36.txt`) and schema (`extracted_schema.txt`). Applied to the directory:
- `source.md` = prompt v36 (verbatim); `schema.source.txt` = the new schema (raw OCR, verbatim).
- `system.txt` / `user.txt` = v36 split into XML (verbatim wording; v36 adds the "Holding Company" entity tag and a `Control | Source` output column).
- `schema.json` = the new schema reconstructed to valid Draft-07 (`title: "Ownership Structure Candidates Schema"`).

**This new schema replaces the single-parent `owners[]` model** (sections 0/A/B below are now historical). Key shape: flat `extracted_records[]` keyed by integer `id`, hierarchy via a **single `parentName`** per record; numeric ownership fields (`actualOwnershipPercentage`, `actualVotingRightsPercentage`, `accumulated*`, `domination*`, `denominator*`, all `number` 0–100); `isUltimateParent` / `isIBO` / `isUBO`; governance/audit fields (`governanceBasis`, `canonicalReasonTokens`, `transparencyCode`, `confidenceScore`, `conflictTag`, `negativeSignals`, `controlsApplied`); per-record `qaFlags[]` + `outOfBounds`; and top-level `outOfBounds` + `qaFlags` objects.

**Open concerns to flag (not blockers):**
1. **Single `parentName` cannot represent multi-path / convergence** — an entity owned by two parents, or an owner holding stakes in two entities, needs more than one parent link. This contradicts the prompt's own MULTI-PATH & CONVERGENCE rule (4.3). Confirm whether duplicate records are intended, or whether the link should be an array.
2. **Numeric `actualOwnershipPercentage` (number 0–100) cannot hold `"Not Available"` / `"Negligible"`** — but the prompt (4.3 / section 10) mandates those literals. Either a sentinel value or a companion string field is needed.
3. **No entity-classification field** — the prompt tags entities (Corporate Entity / Holding Company / GP / LP / Fund Vehicle / Listed Parent / Individual) but `itemType` only carries PERSON/ORGANIZATION; the tag has nowhere to go.
4. **No `layer` and no client field** — layering is implicit via `parentName` chains and `isUltimateParent`; there is no explicit client anchor.
5. **`parentName` direction is ambiguous** — "Name of Parent Organization" + "ownership percentage … in the entity" needs confirming (does `parentName` = the entity this record owns, or the entity that owns this record?).
6. **Reconstruction notes:** preserved author's `"nullable": true` (OpenAPI, ignored by Draft-07); standardized the top-level key casing to `outOfBounds` (source OCR mixed `outofBounds`); item-level `outOfBounds.status` enum taken as `[NEEDS_REVIEW, DOC_INVALID, COUNTRY_OUT_OF_SCOPE, DOCUMENT_NOT_RELEVANT]`; `dominationOwnership`/`dominationVotingRights`/`conflictTag` are optional (not in the source's item `required` list).

`example.json` is now **stale** (built for the old `owners[]` schema; 221 validation errors against the new one). Pending a decision on whether to regenerate it for the new schema (see concern #5 first).

---

## 0. Schema update — supersedes findings below (HISTORICAL)

`schema.json` has been **replaced** with a purpose-built ownership-extraction schema (`title: "Monolith Ownership Extraction Schema"`). Current shape (single-parent, batchable — see `docs/superpowers/specs/2026-06-17-monolith-single-parent-schema-design.md`):

- **One parent array** `extracted_records[]`; no `nodes`/`edges`. The client entity is the record with `layer: 0`.
- Each **record** is self-contained: identity (`id`, `name`, `type`, `entity_classification`, `entity_type_category`, `layer`, `document_name`, `document_path`, `isUBO`, `isIBO`, `listing_proof`, `classification_reasoning`), `owners[]` (embedded ownership links replacing edges, each with `ownership_percentage_direct`, `gp_lp_role`, `control`, `source`, `source_type`, `direction_proof`, and per-link `conflicts[]`), and per-record audit (`cycle_path`, `data_gaps`, `exceptions`, `qaFlags[]`, `outOfBounds`).
- Folding everything into the one batched array keeps batches self-contained: `BatchAccumulatorTool` concatenates arrays but clobbers top-level objects, so `outOfBounds`/`qaFlags` are now per-record (mirrors CSM item-level capture).

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
