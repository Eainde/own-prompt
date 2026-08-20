# CLAUDE.md

This repo stores the **monolith** KYC ownership extraction prompt and its critic. Both are
single-call LLM agents. Each has three files:

- `system.txt` — role statement + XML-structured rules
- `user.txt` — task instruction with `{{camelCase}}` placeholders
- `schema.json` — Draft-07 JSON Schema the model must conform to

The multi-agent wave pipeline (Chart Reader → W1 → W1 Critic → W2 → W3 → W4) that this repo
used to carry has been removed — it is no longer in use. Only the two agents below remain.

## Agents

- **Monolith** (`monolith/`): takes a validated client entity name plus its case documents and
  returns ONE JSON object describing the complete ownership & control structure — shareholders,
  layers, percentages, voting/control rights, UBO/IBO classification support, conflicts, data
  gaps, advisory requests and QA flags. Executes 16 mandatory gates in a fixed `PROCESSING-ORDER`
  (document inventory → translation → name normalization → client anchor validation → scope
  binding → source classification H1/H2/H3 → local requirement selection → methodology
  Global-vs-Streamlined → layer-by-layer extraction → control/voting/domination → entity-type
  logic → UBO/IBO decision support → conflict reconciliation → advisory → QA flags →
  completeness validation). Rules are numbered 1–18 plus KERNEL, ORGCHART, INPUT-VALIDATION,
  PROCESSING-ORDER and OUTPUT-CONTRACT.
- **Monolith Critic** (`monolith_critic/`): takes the monolith's `extracted_records` output plus
  the same case inputs and scores it against **14 acceptance criteria** (all schema-backed —
  there is no advisory-only criterion). Outputs per-criterion pass/fail + observations, a
  severity-ranked `areas_for_improvement`, and a verdict (`PASS` / `ACCEPT_WITH_NOTES` /
  `RETRY`). A `RETRY` feeds back into the extractor via `{{monolithOwnershipCriticFeedback}}`.

Core principles: **evidence-only** (no external knowledge or inference), **client-anchored**,
**zero data loss** (never drop a node for being below-threshold / 0% / missing), **deterministic**,
**fail-closed** (record a gap + QA flag rather than guess).

## Prompt standard

Prompts follow the csm-prompts XML standard:

- `system.txt`: plain-text role sentence, then `<role>`, `<rules>` (with
  `<rule id="..." priority="..." name="...">`), optional `<section name="...">`, `<directive>`
- `user.txt`: `<task>`, one tag per input variable, `<instructions>` with numbered steps
- Template variables: `{{camelCase}}` double-brace syntax
- Nullable fields in schema: `"type": "string"` (not `["string", "null"]`)

## Template variables

**Monolith** (`monolith/user.txt`): `{{gcsDocumentPaths}}` (plural), `{{clientEntityName}}`,
`{{entityType}}`, `{{adoptionLocations}}`, `{{adverseMedia}}`, `{{dueDiligenceLevel}}`,
`{{documentClassifications}}`, `{{kosDocuments}}`, `{{verificationDates}}`,
`{{monolithOwnershipCriticFeedback}}`, `{{isStreamlined}}`.

**Critic** (`monolith_critic/user.txt`): the same case inputs minus the feedback loop, plus
`{{extractedRecords}}` (the extractor's output). Note the critic uses `{{gcsDocumentPath}}`
**singular** while the monolith uses `{{gcsDocumentPaths}}` **plural** — this mismatch is
asserted in the tests so it stays visible.

## Output shape (monolith/schema.json)

Six top-level fields, all required: `clientAnchor`, `extracted_records`, `outOfBounds`,
`qaFlags`, `ownershipApproach`, `advisory`.

- **`extracted_records`** is the only top-level ARRAY — one record per ownership LINK, so a node
  owned by N owners produces N records sharing `nameAsSource` but differing in
  `linkedName` / `relationshipType`. Never collapse multiple owners into one record.
- **OC.4 container shapes**: `clientAnchor`, `outOfBounds`, `qaFlags` and `advisory` are JSON
  OBJECTS and must be emitted as objects even when empty — a top-level object emitted as `[]`
  is the single most common deserialization failure. Empty `qaFlags`/`advisory` =
  `{"summary": null, "records": []}`; empty `outOfBounds` =
  `{"summary": null, "documents": [], "records": []}`.
- Same name, different type: the **per-record** `qaFlags` IS an array; the **top-level**
  `qaFlags` is an object.
- `governanceBasis` carries rule 18's full eight-part reasoning as ONE structured string,
  never as separate fields.

## Key accuracy rules (non-obvious)

- **Rule 9.1 check 2 — "Total Ownership" is indirect**: any percentage reported under a
  "Total Ownership" heading is an AGGREGATE interest held through one or more intermediate
  entities. Report it as Indirect Ownership only; never record it as a direct link percentage
  in `actualOwnershipPercentage`. The critic re-checks this under Percentage Accuracy.
- **Rule 9.1 direction validation**: ownership direction must never be inferred from visual
  placement, SmartArt layout, OCR order, PDF rendering sequence or chart proximity. Where a
  Natural Person and a Corporate Entity are linked, the person is the owner unless evidence
  says otherwise. Undeterminable direction → `OWNERSHIP_DIRECTION_REVIEW_REQUIRED` +
  `confidenceStatus = NEED_REVIEW`, and do not finalise classification.
- **Rule 6 — ORBIS attribution control**: an ORBIS Report is H1 evidence, but a database label
  such as "Global Ultimate Owner (GUO)", "Ultimate Parent" or "Head of Group" is an
  ATTRIBUTION, not ownership evidence. Absent independent ownership documentation, record it as
  `roleCapacity = "Information Only"` and/or a data gap — never a threshold-bearing edge.
- **Rules 10.1 / 12.3 — dilution**: capture every direct link at its DIRECT percentage in
  `actualOwnershipPercentage`, and additionally record the computed product along the path in
  `dilutionOwnershipPercentage`. IBO/UBO thresholds are tested against CUMULATIVE (diluted)
  ownership, not a single direct link.
- **Rule 10.1.1 — voting dilutes separately**: never substitute ownership % for voting %. When
  any link on the path lacks a stated voting %, emit `dilutionVotingRightsPercentage = 0` with a
  `DILUTED_VOTING_INCOMPLETE` qaFlag — the flag is what distinguishes unknown from a genuine 0.
- **Missing percentages**: percentage fields are numeric. Missing → emit `0`, add
  `MISSING_PERCENTAGE` to that record's `qaFlags`, plus a `SOURCE_DATA_NOT_AVAILABLE` entry in
  the top-level `outOfBounds.records`. Same unknown-vs-real-0 principle as above.
- **Rules 7 / 13 — local overlay beats global**: apply the Local Addendum threshold BEFORE the
  global test (e.g. South Africa 5%+ for UBO, US inclusive ≥25% for IBO). Multiple adoption
  locations → apply the stricter one. Missing local rules → data gap + QA flag.
- **Rule 11.2 — listed entity stop rule**: only when entityType is Listed Entity AND listing
  evidence is present, validated and bound to the client anchor AND the applicable rules permit
  it. Then do not drill down, do not determine UBO/IBO, and set
  `ownershipReviewStatus = COMPLETE`.
- **Rule 11.1.1 — notional UBO**: where no natural person is found at threshold, do NOT classify
  anyone as Notional UBO. Emit `NOTIONAL_UBO_ASSESSMENT_REQUIRED` and identify the Senior Most
  CSM by designation only.
- **Rule 16 — QA flags are exception-only**: emit only when an issue materially affects decision
  correctness, ownership completeness, audit reliability, or the ability to apply local rules /
  classify. Normal complexity that is fully explained in the reasoning gets no flag.

## Batching (large outputs)

`monolith/user.txt` declares a `<definition_of_too_big>` block: more than **50** elements under
`extracted_records`. The LLM must not truncate unless the `submitBatch` tool is present.
(The old pipeline agents used a 200-`nodes` threshold — different field, different number.)

## Test harness

`tests/` contains pytest suites covering the two remaining agents:

- `test_monolith_schema.py` — both schemas are valid Draft-07; monolith top-level field set;
  OC.4 container fields typed as objects; critic verdict enum
- `test_monolith_prompts.py` — template-variable sets for both `user.txt` files; the
  `<definition_of_too_big>` batch threshold; every schema record property is named in
  `system.txt` (prompt-backs-schema invariant); critic `<criterion name="...">` list matches the
  schema enum exactly (14, in order); the "Total Ownership" rule is present in both prompts
- `conftest.py` — `load_schema` / `load_prompt` / `load_example` helpers plus a custom
  `Draft7Validator` that accepts `null` for `"type": "string"` fields (project convention)

Run with `venv/bin/pytest tests/ -v`.

**Known defect (xfail, strict)**: `monolith/example.json` is stale — it still uses the pre-v39
nested `owners` record shape (`owner_id`, `gp_lp_role`, `direction_proof`, `cycle_path`) and does
not validate against the current flat record schema. Regenerate or delete it, then drop the
`xfail` marker on `test_example_records_conform_to_record_schema`.

## Docs

`docs/superpowers/specs/` and `docs/superpowers/plans/` retain the monolith design history
(single-prompt design, single-parent-schema, critic design, prompt-backs-schema, v39 update).
