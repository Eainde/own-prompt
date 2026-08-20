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

## Field vocabulary and numbering

The prompts name concepts in working language; **only `schema.json` names may be emitted.** Rule
`FIELD-VOCABULARY` (CRITICAL, placed before rules 1 and 9 so it is read first) is the single
authority mapping one to the other:

- **Renames** — `ownerName` → `linkedName`, `ownedEntityName` → `nameAsSource`,
  `directOwnershipPercentage` → `actualOwnershipPercentage`, `pageOrSection` → `pageNumber`, …
- **Fold-ins** (no dedicated key) — `dataGaps` → `outOfBounds.records`; `documentConflicts` and
  each rule 14 conflict record → `qaFlags.records[].reason` as text; `stopRuleApplied` →
  `controlsApplied[]`; `outOfBoundsDocuments` → `outOfBounds.documents`.
- **Working state, never emitted** — the rule 1 document inventory (13 of its 15 attributes have
  no schema home), the §9.0 entity inventory, `qualifyingInterest`, `confidenceStatus`. Emitting
  any of these as a schema key is a contract violation and fails deserialization.

⚠️ **Direction hazard, called out in the rule itself**: rule 9 lists `ownerName` first and
`ownedEntityName` second, but the emitted record is the reverse — `nameAsSource` is the OWNED
subject and `linkedName` points at its OWNER. Mapping them positionally inverts every ownership
edge. Every record must read "nameAsSource is owned by linkedName".

**Step vs rule numbering**: `PROCESSING-ORDER`'s steps 1–12 map to rules 1–12, then diverge —
step 13 = rule 14, … step 16 = rule 17. Rule 13 (local threshold guide) and rule 18 (reasoning
format) are not steps; `FIELD-VOCABULARY`, `KERNEL`, `ORGCHART` and `INPUT-VALIDATION` bind at
every step.

## Key accuracy rules (non-obvious)

- **Rule 9.1 check 2 — "Total Ownership" is indirect**: any percentage reported under a
  "Total Ownership" heading is an AGGREGATE interest held through one or more intermediate
  entities. Report it as Indirect Ownership only; never record it as a direct link percentage
  in `actualOwnershipPercentage`. The critic re-checks this under Percentage Accuracy.
- **ORGCHART — an ambiguous chart is a hypothesis, not evidence**: read the chart upward as the
  working hypothesis, but chart position NEVER by itself supports an emitted link. Where the
  hypothesis cannot be confirmed from documentary evidence, §9.1 check 5 governs and ORGCHART
  yields to it: flag `OWNERSHIP_DIRECTION_REVIEW_REQUIRED` and leave the direction unresolved.
  Emitting a presumed edge from position alone is barred by KERNEL C.
- **Rule 9.1 direction validation**: ownership direction must never be inferred from visual
  placement, SmartArt layout, OCR order, PDF rendering sequence or chart proximity. Where a
  Natural Person and a Corporate Entity are linked, the person is the owner unless evidence
  says otherwise. Undeterminable direction → `OWNERSHIP_DIRECTION_REVIEW_REQUIRED` +
  `confidenceStatus = NEED_REVIEW`, and do not finalise classification.
- **Rule 6 — ORBIS attribution control**: an ORBIS Report is H1 evidence, but a database label
  such as "Global Ultimate Owner (GUO)", "Ultimate Parent" or "Head of Group" is an
  ATTRIBUTION, not ownership evidence. Absent independent ownership documentation, record it as
  `roleCapacity = "Information Only"` and/or a data gap — never a threshold-bearing edge.
- **Rule 9.0 — inventory before links**: extraction is two-pass. Enumerate every party in every
  document first, then build links only between inventoried parties. A party absent from the
  inventory must not appear in `extracted_records`; an inventoried party with no link is recorded
  as a `qaFlags.records` entry (reason naming the party, status `NEED_REVIEW`) — **not**
  `outOfBounds`, whose `records` entries require an integer `id` an unlinked party cannot supply. This is the completeness guarantee for *which parties get found*.
- **Rule 9.3 — per-target sum check**: for each `linkedName` target, sum the direct percentages
  **counting each distinct HOLDER once** — rule 14 legitimately retains several records for one
  link, and those versions are one holder, so sum the controlling version only and never add them
  together. The total should reach ≈100% (±5%). Below 95% means a shareholder was likely missed → re-scan, then
  `INCOMPLETE_SHAREHOLDER_SET` plus a data gap, never an invented shareholder. Above 105% means
  double-counting or cross-assignment (commonly a "Total Ownership" aggregate wrongly recorded as
  a direct link) → `OWNERSHIP_PERCENTAGE_CONFLICT`. Exempt: targets whose shareholders carry
  unknown percentages, or sources that positively state they are partial/extracted/illustrative
  (silence is not an exemption). A sum materially below 100% is positive evidence of an
  omission — the check that catches what re-reading alone does not.
- **Rule 12.0 — `roleCapacity` precedence ladder**: 22 rungs, first match wins, no weighing and no
  skipping. `Direct Owner`/`Indirect Owner` (rungs 20/21) are threshold failures;
  `Non-Qualifying Owner` (rung 22) is reserved for links that aren't beneficially
  ownership-bearing, never a threshold-failure outcome. Assumption A1 in the spec — confirm with
  the KOS text owner.
- **Rule 12.4 — arithmetic contract**: thresholds test `qualifyingInterest` (cumulative product on
  GLOBAL, deemed inherited position on STREAMLINED), never a single direct link. Compare
  unrounded, report rounded half-up to 2dp, never round intermediates. Write the operands before
  the result (`DilutionChain=[80 × 60 × 50 = 24.00]`) so the figure is recomputable; in rule 18's
  `[1]` line the trace tokens must precede Classification/Conclusion, and `Classification` carries
  the ladder's `roleCapacity` value verbatim rather than a separate vocabulary.
- **Rule 12.4 — STREAMLINED, ≤50%, and control-based IBO**: "non-dominating" governs whether an
  owner **inherits an upstream** position, never whether a direct owner holds its own. A layer-1
  streamlined owner IS threshold-tested on its own percentage even below 50% (§8.3.2's worked
  example turns on this — DEF Ltd is an IBO at 30%). Only at **layer 2+** does a ≤50% owner have no
  inherited `qualifyingInterest`: it then emits `QualifyingInterest = "n-a"`,
  `dilutionOwnershipPercentage = 0` and `NO_INHERITED_POSITION` — a defined outcome, never
  `MISSING_PERCENTAGE`. Separately, a **Non-Natural Person** may qualify as an IBO on rule 10.2's
  evidenced control/domination bases below the ownership threshold (ladder rung 10), mirroring
  §12.2.1 criteria 3/4/5 for natural persons; the basis must be named in `governanceBasis`.
- **Canonical record order**: `id` is assigned after sorting by layer → linkedName →
  nameAsSource → relationshipType → documentName → pageNumber, NFC-normalized and case-folded
  (raw-value tie-break on linkedName/nameAsSource first). A FINAL TIE-BREAK on the canonical JSON
  serialization (id excluded, keys sorted, code-point order) makes the order total, so `id`
  assignment is never ambiguous — mirrored by `tests/consistency.py`'s `sort_key()`. Records no
  longer appear in document order.
- **No assertion without a quote (`evidenceSnippet`)**: any record asserting a non-zero direct
  ownership/voting percentage or a `linkedName` needs a non-null verbatim `evidenceSnippet` and a
  numeric `pageNumber`. `evidenceSnippet` may be null only when ALL THREE hold: `linkedName` is
  also null, AND no percentage is asserted, AND the record carries a
  MISSING_PERCENTAGE/SOURCE_DATA_NOT_AVAILABLE gap. A party that cannot be quoted must not be
  asserted.
- **Rule 8.3 — Streamlined means Domination ONLY (KOS 8.0a)**: when `isStreamlined = TRUE`, never
  multiply percentages across layers and never compute a cumulative indirect figure. A holder of
  >50% (ownership, voting, or control by other means) dominates and INHERITS the dominated
  entity's full position in the client — 55% of an entity holding 70% of the client makes you a
  UBO at a deemed 70%, not 38.5%. A holder of 50% or less inherits nothing and that branch stops.
  `dilutionOwnershipPercentage` carries the deemed inherited position, not a product. Rules 10.1
  and 12.3 carry GLOBAL-only applicability guards so the two methodologies never mix in one path.
- **Rule 9.2 — wholly-owned wordings ARE percentage evidence**: "wholly owned subsidiary of",
  "sole member", "single shareholder", "sole shareholder", "entirely owned by", "fully owned and
  controlled by" each mean exactly 100%. Assign `actualOwnershipPercentage = 100` and quote the
  wording in `evidenceSnippet` — do NOT emit 0 + `MISSING_PERCENTAGE` just because no number is
  printed.
- **Rule 6 — ORBIS "MO" is not a percentage**: "MO" / "Majority Owned" / "Majority Control" /
  "Majority-Owned Subsidiary" / "Majority Shareholder" with no stated figure evidences only
  >50%. Never infer 51%, never upgrade to 100% (that is 9.2's job, and only for sole/entire
  ownership wordings). Emit 0 + `MISSING_PERCENTAGE` + `SOURCE_DATA_NOT_AVAILABLE`, flag
  `ORBIS_MO_PERCENTAGE_NOT_DISCLOSED`, and raise an advisory for the exact percentage.
- **Rules 10.1 / 12.3 — dilution (GLOBAL pathway only)**: capture every direct link at its DIRECT percentage in
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
- `consistency.py` — run-to-run variance harness. Normalises away record order and free wording,
  then buckets variance into **parties / links / numbers / classification / derived / toplevel**
  (all six count toward `substantive_agreement`) plus `narrative` (reported, never counted).
  Calibrated in both directions: records group under the 3-part link key and the whole group is
  compared, so a changed `pageNumber` surfaces as a difference rather than dropping the record
  from comparison; free prose (`summary`, advisory text) is excluded while `reason` is reduced to
  its `UPPER_SNAKE` token signature so flag identity survives; unordered arrays are sorted before
  comparison. Run over N saved outputs of the same case:
  `venv/bin/python tests/consistency.py runs/case42/*.json`. Exit code 0 = substantive agreement.
- `test_consistency.py` — tests the harness against synthetic fixtures with known answers

Run with `venv/bin/pytest tests/ -v`.

**`tests/` is gitignored repo-wide** (`.gitignore`) — `git add` of any file under `tests/` needs
`-f`, or it silently stays untracked. Has surprised every implementer on this plan.

**Known defect (xfail, strict)**: `monolith/example.json` is stale — it still uses the pre-v39
nested `owners` record shape (`owner_id`, `gp_lp_role`, `direction_proof`, `cycle_path`) and does
not validate against the current flat record schema. Regenerate or delete it, then drop the
`xfail` marker on `test_example_records_conform_to_record_schema`.

## Docs

`docs/superpowers/specs/` and `docs/superpowers/plans/` retain the monolith design history
(single-prompt design, single-parent-schema, critic design, prompt-backs-schema, v39 update).
