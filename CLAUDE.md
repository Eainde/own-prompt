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
  PROCESSING-ORDER, CRITIC-FEEDBACK and OUTPUT-CONTRACT.
- **Monolith Critic** (`monolith_critic/`): takes the monolith's `extracted_records` output plus
  the same case inputs and scores it against **17 acceptance criteria** (all schema-backed —
  there is no advisory-only criterion). Outputs per-criterion pass/fail + observations, a
  severity-ranked `areas_for_improvement`, and a verdict (`PASS` / `ACCEPT_WITH_NOTES` /
  `RETRY`). A `RETRY` feeds back into the extractor via `{{monolithOwnershipCriticFeedback}}`.
  **The critic never sees `monolith/system.txt`.** Its `<reference>` block R1–R8 therefore
  reproduces every extractor rule it is asked to apply — the direction contract, the 22-rung
  `roleCapacity` ladder, rule 10.2's closed list of control bases, the `relationshipType` enum,
  rule 18's eight-part format, the QA-flag names, the working-state names that must never be
  emitted, and the OC.4 container shapes. A rule cited by number and not reproduced there is a
  check nobody can perform; `tests/test_monolith_prompts.py` pins each block.

Core principles: **evidence-only** (no external knowledge or inference), **client-anchored**,
**zero data loss** (never drop a node for being below-threshold / 0% / missing), **deterministic**,
**fail-closed** (record a gap + QA flag rather than guess).

### The critic's 17 criteria (order is contract — `test_critic_criteria_names_match_schema_enum`)

 1. **Overall Goal Adherence**
 2. **Entity Completeness**  _(PRIMARY)_
 3. **Percentage Accuracy**  _(PRIMARY)_
 4. **Client Anchor Validation**
 5. **Ownership Direction**
 6. **Non-Ownership Information Suppressed**
 7. **No External Knowledge / Hallucination**
 8. **Document Handling & Scope**
 9. **Source Classification & Admissibility**
10. **Methodology (Global vs Streamlined)**
11. **Local Overlay Applied**
12. **Entity-Type Specific Logic**
13. **IBO / UBO Classification Accuracy**
14. **Domination & Control Indicators**
15. **Advisory & QA Assessment**
16. **Conflict Preservation**
17. **Structural Validation**

The `<criterion name="...">` list in `monolith_critic/system.txt` must equal the schema enum
exactly, **in this order** — adding or reordering one means editing prompt, schema and test
together.

## The RETRY loop (rule `CRITIC-FEEDBACK`, CF.0–CF.7)

`{{monolithOwnershipCriticFeedback}}` is populated only on a retry. The rule turns on one fact
that shapes everything else: **the extractor is never given its previous output** — only the
critic's findings. So:

- **CF.1 re-extract, never patch.** A feedback-shaped patch would emit only the records a
  finding named and silently drop every other one.
- **CF.2 feedback is not evidence.** The critic is not a case document; a finding's assertions
  never satisfy KERNEL C. A finding may only send you back to re-read a document, and the
  correction cites the *document*. A party created to satisfy a finding is a fabricated party —
  the exact failure the critic exists to catch, now caused by the critic.
- **CF.3 resolve by identity, never by `id`.** ids are reassigned every run from canonical
  order, so an id inside a finding addresses the *previous* run's numbering.
- **CF.5 dispute, don't ignore.** Where the documents don't support a finding, keep the
  evidenced extraction and record `CRITIC_FINDING_DISPUTED` in `qaFlags.records` + the basis in
  `governanceBasis` part [6]. Silently ignoring guarantees the identical RETRY; silently
  adopting fabricates.
- **CF.6 every dispute raises an advisory in the same run.** Termination can't depend on
  counting rounds — the extractor can't see them — so the advisory is what routes a genuine
  disagreement to a human instead of to another retry. Flipping position because a finding
  repeated would make output depend on how many times it was reviewed (KERNEL E breach).

The critic knows about this: a `CRITIC_FINDING_DISPUTED` entry is judged **on the evidence**,
not scored as an ignored finding, and a dispute with no matching advisory is IMPORTANT.

- **CF.4a structural findings are applied at emission, not re-derived.** A finding about the SHAPE of
  the output (a container as an array, `outOfBounds.reason` on an in-scope record, a `thresholdApplied`
  string, a FIELD-VOCABULARY C key) asks nothing of the evidence, and CF.1's re-extraction cannot answer
  it — re-running the gates regenerates the same default emission shape, so the defect returns untouched
  and the identical RETRY is guaranteed. Observed: the `"In scope"` defect raised, ignored, re-raised
  verbatim. Structural findings are collected into a pre-emission checklist and answered by conforming
  to the OUTPUT-CONTRACT; CF.2 does not apply (no document can support or refute "this field must be
  null"), and a structural finding that contradicts the contract is disputed per CF.5 with the OC clause
  named, CF.7 governing.

**Orchestration note (outside the prompts):** because every dispute carries an advisory, the
caller can terminate the loop on `advisory.records` containing a dispute rather than on a retry
counter. Nothing in either prompt caps the number of rounds.

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
`{{extractedRecords}}` (the extractor's output). Both agents take the **plural**
`{{gcsDocumentPaths}}`. They disagreed until 2026-08-21 — the critic took a singular
`{{gcsDocumentPath}}`, so a caller wiring both from one config handed the critic a single path
while both of its PRIMARY criteria assume a full document sweep. `test_critic_user_variables`
now pins them equal so they cannot drift apart again.

## Output shape (monolith/schema.json)

Six top-level fields, all required: `clientAnchor`, `extracted_records`, `outOfBounds`,
`qaFlags`, `ownershipApproach`, `advisory`.

- **`extracted_records`** is the only top-level ARRAY — one record per ownership LINK, so a node
  owned by N owners produces N records sharing `linkedName` (the owned node) but differing in
  `nameAsSource` / `relationshipType`. Never collapse multiple owners into one record.
  Conversely one party owning M entities produces M records sharing `nameAsSource`.
- **OC.4 container shapes**: `clientAnchor`, `outOfBounds`, `qaFlags` and `advisory` are JSON
  OBJECTS and must be emitted as objects even when empty — a top-level object emitted as `[]`
  is the single most common deserialization failure. Empty `qaFlags`/`advisory` =
  `{"summary": null, "records": []}`; empty `outOfBounds` =
  `{"summary": null, "documents": [], "records": []}`.
- Same name, different type: the **per-record** `qaFlags` IS an array; the **top-level**
  `qaFlags` is an object.
- `governanceBasis` carries rule 18's full eight-part reasoning as ONE structured string,
  never as separate fields.
- **Gap tokens are reasons, never statuses.** `outOfBounds.records.status` accepts only
  `NEED_REVIEW` / `COUNTRY_OUT_OF_SCOPE`, and `outOfBounds.documents.status` only `DOC_INVALID` /
  `DOCUMENT_NOT_RELEVANT`. "Add a `SOURCE_DATA_NOT_AVAILABLE` entry" therefore means: required
  integer `id` of the owning record, the token at the START of `reason`, `status = NEED_REVIEW`.
  A gap with no owning record cannot go here at all (the `id` is required) — it belongs in
  `qaFlags.records`, same as §9.0's unlinked party.
- **Rule 16's category list is the flag vocabulary** — every flag any rule orders must appear in
  it, spelled identically. Three did not (`OWNERSHIP_DIRECTION_REVIEW_REQUIRED`,
  `DILUTED_VOTING_INCOMPLETE`, `NOTIONAL_UBO_ASSESSMENT_REQUIRED`, the last also duplicated as
  `NOTIONAL_UBO_REQUIRED`); `tests/test_monolith_prompts.py` now pins this both ways.
- **`id` ordering**: canonical order, never document reading order. The schema description used
  to say the exact opposite of OC.1 — it is the last thing a model reads before emitting.
- **`documentClassifications` is the PROVIDER's label, not the answer.** Rule 6 classifies from
  the document. Derive, then compare: on divergence the document controls and the divergence is
  recorded in `governanceBasis` part [2]. The critic scores the derivation, not the label — a
  correct derivation that contradicts `documentClassifications` is a Pass.
- **"NO OWNERSHIP EVIDENCE IDENTIFIED IN THE REVIEWED DOCUMENTATION."** is emitted as
  `qaFlags.summary` (plus a matching record, client anchor only, `ownershipReviewStatus =
  NEED_REVIEW`) — never as bare prose, which the JSON-only directive forbids.

## Field vocabulary and numbering

The prompts name concepts in working language; **only `schema.json` names may be emitted.** Rule
`FIELD-VOCABULARY` (CRITICAL, placed before rules 1 and 9 so it is read first) is the single
authority mapping one to the other:

- **Renames** — `ownerName` → `nameAsSource`, `ownedEntityName` → `linkedName`,
  `directOwnershipPercentage` → `actualOwnershipPercentage`, `pageOrSection` → `pageNumber`, …
- **Fold-ins** (no dedicated key) — `dataGaps` → `outOfBounds.records`; `documentConflicts` and
  each rule 14 conflict record → `qaFlags.records[].reason` as text; `stopRuleApplied` →
  `controlsApplied[]`; `outOfBoundsDocuments` → `outOfBounds.documents`.
- **Working state, never emitted** — the rule 1 document inventory (13 of its 15 attributes have
  no schema home), the §9.0 entity inventory, `qualifyingInterest`, `confidenceStatus`. Emitting
  any of these as a schema key is a contract violation and fails deserialization.

⚠️ **Direction contract (settled 2026-08-20, on the schema reading)**: `nameAsSource` is the
record's SUBJECT and the **OWNER**; `linkedName` is the entity that subject **owns**, one layer
CLOSER to the client. Every record reads **"nameAsSource is a [relationshipType] of
linkedName"**. Rule 9 lists `ownerName` first and `ownedEntityName` second and the record keeps
that order, so the mapping is **positional** — the earlier prompt inverted it, and three other
rules break when it is inverted: §9.3 sums records **by `linkedName`** to total one target's
shareholders, OC.1 sets a record's `layer` = its `linkedName` record's `layer` + 1 (an owner is
one layer *further* from the client), and §12.3 multiplies along the `linkedName` chain **down
to** the client. `linkedName` is null **only** on the layer-0 client; an evidenced ultimate
parent still names the entity below it and is marked by `isUltimateParent` instead. Before this
was settled, `monolith/system.txt`, `monolith_critic/user.txt` and `schema.json` each stated it
differently — whichever way the extractor emitted, some check fired. It is now stated in **five**
places that must move together: `monolith/system.txt` (FIELD-VOCABULARY A, rule 9, OC.1/OC.3),
`monolith/schema.json` (`nameAsSource` / `linkedName` / `isUltimateParent` descriptions),
`monolith/README.md`, and both critic files (R1). `tests/consistency.py` renders edges the same
way (`nameAsSource --relationshipType--> linkedName`).

**Step vs rule numbering**: `PROCESSING-ORDER`'s steps 1–12 map to rules 1–12, then diverge —
step 13 = rule 14, … step 16 = rule 17. Rule 13 (local threshold guide) and rule 18 (reasoning
format) are not steps; `FIELD-VOCABULARY`, `KERNEL`, `ORGCHART`, `INPUT-VALIDATION` and
`CRITIC-FEEDBACK` bind at every step. `CRITIC-FEEDBACK` is read before step 1 on a retry — it
changes what must be got right, never which steps run.

## Key accuracy rules (non-obvious)

- **A blank `isStreamlined` was defaulting to STREAMLINED — the worst-shaped bug this repo has had**
  (settled 2026-08-27, business rule 7X). `isStreamlined` is tested as a BOOLEAN at a dozen
  load-bearing sites (§8.1, §8.2, §8.3, §10.1's applicability guard, §10.2.0, §12.3, §12.4, rule 16's
  `STREAMLINED_NOT_SUPPORTED` mapping, OC.1's two dilution fields, and two schema descriptions). A blank
  value is neither TRUE nor FALSE, so **every one of those guards was undefined** — including §10.1's
  "where `isStreamlined = TRUE`, do not dilute". §8.1 reached FALSE and §8.2 reached TRUE; nothing
  reached blank, `INPUT-VALIDATION` logged it as a gap but named no pathway, and the model resolved the
  hole by guessing STREAMLINED. That guess terminates every branch at the first owner holding 50% or
  less (§8.3.1) and deletes the entire structure above it. New **§8.0** is a positive mapping — explicit
  Streamline → candidate, FALSE/Global → GLOBAL, **anything else → GLOBAL** — plus one normalization
  clause ("a blank, missing or unrecognised value READS AS FALSE for every other `isStreamlined` test
  in this prompt"), which is what fixes twelve sites at once rather than twelve edits. The default
  settles the *methodology* only: the gap, `MISSING_MANDATORY_INPUT` and the advisory are still
  recorded. `ownershipApproach` is now expressly the pathway **actually applied** after §8.2's
  conditions, and OC.2's `NEED_REVIEW` was narrowed (input present but irreconcilable with a Local
  Addendum) so that a blank input no longer has two defensible answers.
- **…and the critic was blessing the truncation.** Entity Completeness's STOP-RULE GUARD limb (iii)
  says every party above a 50%-or-less owner is *correctly absent* under STREAMLINED — it verified the
  **stop**, never the **declaration**, so a wrongly-declared STREAMLINED made the reviewer certify the
  deletion as correct by rule. Limb (iii) is now gated on validating the declaration against
  `is_streamlined` (critic **R10** reproduces §8.0's mapping, since the critic never sees the extractor
  prompt), and a STREAMLINED declared on a non-explicit input is **CRITICAL**, not IMPORTANT. The
  converse is deliberately asymmetric: GLOBAL on a Streamline input is IMPORTANT, because it
  over-extracts rather than dropping evidence. General lesson: when a guard excuses an absence, check
  what authorises the guard — an unverified precondition turns a safety check into a laundering step.
- **Rule 9.2.3 — SEC Form ADV ownership codes are percentage evidence** (added 2026-08-27; table
  replaced 2026-08-28). Form ADV states ownership as a lettered code. The business team's final table
  gives each code a disclosure RANGE and a deemed PERCENTAGE — NA `Less Than 5%` → 4.99, A
  `5% But Less Than 10%` → 6.00, B `10% But Less Than 25%` → 11.00, C `25% But Less Than 50%` → 25.01,
  D `50% But Less Than 75%` → 50.01, E `75% or More` → 75.01 — and **the eight "special allocation
  rules" of the first draft were withdrawn outright**. That withdrawal removed three interlocks the
  first implementation needed: a code's value no longer depends on the holder set (the same `C` was
  25.01, 25.00 or 49.99), allocation is no longer order-dependent, and D no longer flips between
  domination and none (a "two D holders" case allocated 50.00 each, which is not a majority). One code,
  one percentage, always. `tests/test_monolith_prompts.py` now pins the allocations as **absent**, so
  they cannot be reintroduced from the superseded instruction.
  Three things still had to be built around the table. (1) **The RANGE is not the figure.** D's range
  starts *at* 50%, so reading the range instead of the percentage would make a D holder's domination
  arguable; both prompts say the percentage column is the only value produced, and 50.01 is what
  §10.2.0 tests. (2) **§9.2.1's closing clause said the opposite** — "no other two-letter database code
  is interpreted by inference… any other code is NOT ownership evidence on its own" — so the two
  clauses gave contrary answers to the same character on the same page; it now names three defined
  sets. (3) Single letters collide with everything on a page, so §9.2.3 carries §9.2.1's
  **RESOLVE-DON'T-PATTERN-MATCH** discipline and fails closed to 0 + `MISSING_PERCENTAGE` on an
  unresolved code. **A banded set does not total 100** — two D holders make 100.02, four C holders
  100.04, a single E holder 75.01 — and that is an artefact of banding, not double-counting: the target
  is exempt from §9.3 under `explicitly-partial-source`, `TargetSum` reads "exempt", and
  `OWNERSHIP_PERCENTAGE_CONFLICT` is never raised for it. Critic **R9** carries the whole table, or it
  would score a correct band as invented.
- **A Form ADV table is exempt from §9.3's sum check under the EXISTING `explicitly-partial-source`
  token.** Schedule A/B disclose holders down to a threshold band and not below it, so such a table is
  partial by its nature and satisfies that category **by rule** — no printed statement of partiality
  need be found. A single E holder totals 75.01, which would otherwise raise a false
  `INCOMPLETE_SHAREHOLDER_SET` on every ADV case. Deliberately NOT a third token: adding one would have
  forced edits to §12.4's "either", the critic's "which of the **two** … categories", and
  `test_target_sum_names_both_exemption_categories`.
- **Rule 9.0A — a label is not always a party** (added 2026-08-27). A collective ownership label
  (Passive Investors, Other Shareholders, Contractual partnership, Limited Partners, Investor Consortium…)
  is a DESCRIPTOR
  standing for underlying parties. Resolved from any supplied source — legend, footnote, note, client
  email, maker/checker comment — the underlying parties are emitted and **the label is not a party at
  all**, emitted in no container. That required an express **third exception to §9.0 pass 1's
  inventory**, alongside the EVIDENCED STOP RULE: pass 2's routing is a closed two-container rule that
  says in terms "the two routes are NOT interchangeable", so a removed label had nowhere to go.
  Percentages are read as printed, never computed — a non-reconciling or partial resolution keeps both
  facts and flags rather than deriving a residual holder. Unresolved, the label is RETAINED with the
  printed percentage, ladder rung 3 "Information Only", and `COLLECTIVE_OWNER_UNRESOLVED`.
- **Keeping the unresolved label's percentage is load-bearing in two directions.** Rule 6's
  attribution-control text bars deriving a percentage *from* a label — a different thing from a figure
  printed on the chart for that link — and dropping it would both break §9.3's sum for the target and
  collapse §15.1's headroom for the branch to zero, which makes the branch IMMATERIAL and suppresses the
  very advisory §9.0A exists to force. §15.1 now carries a "MATERIAL BY RULE, whatever the headroom"
  carve-out, mirrored in the critic, which is otherwise positively forbidden from demanding an advisory
  §15.1 excuses.
- **The collective label's ladder rung had to be pinned, because two rungs read on the same words.**
  Rung 2 "Unknown / Needs Review" fires on "party identity is unresolved" and PRECEDES rung 3, so
  first-match-wins would have decided every collective label by which sentence was read first. Ruling:
  rung 2 is for an unresolved question **between candidates** (a §9.1 direction, or §9.0 pass 1's two
  near-identical labels); an unresolved collective label takes **rung 3**. Stated in both rungs, in
  critic R2, and in the schema's `roleCapacity` description.
- **The critic's sweep would have demanded a resolved collective label back — and every existing guard
  said yes.** Its independent document sweep finds "Passive Investors" printed on the chart as an
  owner; the holder roster has no line for it; the near-identical-name check does not reach it
  ("Passive Investors" and "Fund A" share no stem); and NAME-IT-AND-QUOTE-IT is *satisfied*, because
  the chart really does print it as a holder of that target. A fully-evidenced, completely wrong
  CRITICAL, answerable only by re-emitting the label §9.0A required removing — the documented
  unclearable-RETRY shape. A **COLLECTIVE-LABEL GUARD** now runs second, right after the SCOPE GUARD.
  Criterion 6 needed a matching carve-out too: it scored "not as an `Information Only` record standing
  in for an owner" as CRITICAL **by name**, which is exactly the required output for an unresolved label.
- **`itemType` and `relationshipType` are required non-nullable enums, so a collective record asserts
  something whatever you do.** Pinned: `itemType = "Non Natural Person"` is a two-valued CONTAINER TYPE
  carrying no assertion of legal personality (the non-entity finding rides on rung 3 + the flag), and
  `relationshipType = "other"`. Left unpinned this is a coin flip on every collective record. Same shape
  as the `thresholdApplied` hole §9.0A exposed: its closed grammar allowed null only for the client and
  for a stop-rule halt, so it forced a threshold string onto rung-2/rung-3 records that were never
  threshold-tested — now permitted null, in prompt, schema and critic.
- **`pageNumber` is a required integer with no null and no "unknown" form**, and §9.0A makes legend-,
  email- and comment-sourced evidence routine. Ruling: an unpaginated source takes `pageNumber = 1`,
  with the real locator (the email's date and subject, the legend's position, the comment's author) in
  `governanceBasis` part [2]. Do not drop the record for want of a page, and do not invent a plausible one.

- **Rule 9.0A — a contractual partnership is resolved IN PLACE, not deleted** (added 2026-09-04, business
  answers Q1–Q6 in `QUESTIONS-2026-09-04-contractual-partnership.md`). The business asked for one word —
  `Contractual partnership` added to §9.0A's example list — and that word alone would have deleted the
  partnership from every chart it appears on, because §9.0A's RESOLVED limb emits the label in NO container
  and re-points its parties at the entity below. Four rules answered the same box differently: §11.7 wants
  GP / LPs / partnership percentages / GP look-through, §12.1 makes a partnership an IBO, ladder rungs 13/14
  label the partners, and the critic scores the look-through. The business settled it as **A across the
  board**, which is the narrowest reading and repeals nothing:
  - **Scope is the GENERIC descriptor only** — a box labelled "Contractual partnership", "Partnership",
    "JV Partners", "Consortium". A NAMED vehicle (`ABC Fund L.P.`, `X GmbH & Co. KG`, `Y LLP`) stays a party
    under §11.7 and is never resolved away. Without this sentence the list is "illustrative, not a closed
    list" and generalises to every LP on the chart.
  - **The node is RETAINED** and the partners are emitted as owners OF IT (`linkedName` = the block) — the
    one carve-out to "the label is then NOT A PARTY". A forward pointer sits in the RESOLVED bullets so the
    delete rule is never applied before the carve-out is read.
  - **Partner figures are the INTERNAL split, transcribed**; §9.3 sums them against the BLOCK, not the client.
  - **The share of the client comes from the chain** — `dilutionOwnershipPercentage` (§12.3) or §8.3's deemed
    position — and is NEVER written into `actualOwnershipPercentage`. This is what keeps the business's
    "multiply through" answer (Q2) compatible with §9.2.4's PRINTED/ASSIGNED/ABSENT provenance and with
    §9.0A's own settled "NEVER COMPUTED, AND ARE NOT MULTIPLIED": the multiplication was always there, in the
    dilution field, so no new arithmetic was added to a direct figure.
  - **A controlling GP survives a zero economic share** — §10.2.0 D3, rung 10 (Q4).
  - **Unresolved is unchanged**: Information Only, `isIBO` false, `COLLECTIVE_OWNER_UNRESOLVED`, NEED_REVIEW,
    advisory — and §11.7's look-through then has nothing to look through, which is not a defect (Q5).
  - Owner-side only; the client's own `entityType` is untouched and would need a separate §11.9 (Q6).
  Critic side mirrors all of it (R11 carve-out, COLLECTIVE-LABEL GUARD, criterion 12), or the reviewer demands
  the removal of the node the extractor is required to keep — the documented unclearable-RETRY shape.


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
- **The critic must WRITE the holder roster before asserting absence** (settled 2026-08-22). A run
  raised a CRITICAL demanding `TCV Luxco Mollie S.à r.l.` be added "as a layer 3 shareholder of Mollie
  Holding Ltd. with actualOwnershipPercentage of 8.90" — against a record set whose **id 11 was exactly
  that record**: identical name, target, layer and figure. No transcription error and no near-match, so
  [[the near-identical-name check]] does not reach it. The critic answered "is this party present?" from
  recollection of a 13-record array whose bulk is `governanceBasis`, and recall failed; absence-from-memory
  became a CRITICAL. Its own §9.3 guard would have caught it — the roster totals 99.99 with TCV Luxco and
  91.09 without, and either figure exposes the error — but that guard sits as prose deep in a long
  paragraph and was simply skipped. The fix makes it PRODUCE the list rather than remember it: before any
  missing-entity finding, walk `extracted_records` id 1..N and write out, in the observation, one line per
  record whose `linkedName` is the target — `id | nameAsSource | actualOwnershipPercentage` — scanning by
  `nameAsSource` alone and explicitly NOT reading `governanceBasis`, where attention is lost. The roster is
  the only thing a candidate may be checked against, a finding must reproduce it, and its total IS the §9.3
  sum, so it is one action rather than two. General lesson: a procedural requirement buried as prose in a
  long paragraph does not run — it has to be a written artefact the model must produce.
- **Positive templates beat prohibitions.** `"reason": "In scope"` was forbidden in THREE places in the
  deployed build — OC.1, OC.4 and the schema field description — and every record of a live run still
  carried it. Negative instructions are weak. OC.1 now leads with two copyable templates and one question
  to choose between them (in-scope: copy `{"isOutOfBounds": false, "reason": null, "status": null}`
  literally; out-of-scope: the populated form), and both schema descriptions now open `DEFAULT VALUE: null.`
  before saying anything about what is wrong. The ban text stays, but it is no longer what the model reads
  first. Worth reaching for the same reframe on any rule that keeps being violated despite being stated.
- **A near-identical name is not a missing entity — the critic's worst failure mode**
  (settled 2026-08-22). One critic response cited `"Molehill Holding B.V." (ID 5)` under Structural
  Validation while its point-1 CRITICAL declared *"the extraction omits the entity 'Mollie Holding
  B.V.'"*. The party was present, correctly spelled; the critic had mis-transcribed the chart label,
  string-matched `extracted_records` — `monolith_critic/user.txt` said "matched by nameAsSource", which
  licenses exactly that — found nothing, and raised CRITICAL. **This is what drove the oscillation
  across four runs.** A CRITICAL is mandatory to resolve under CF.4, and the only way to add a party you
  already hold under another name is to emit it twice plus an invented link between the two spellings:
  the fabricated intermediate layer attempts 1 and 2 produced. The next round flags that link, the
  extractor removes it, and the case ping-pongs between two wrong outputs with neither agent able to
  stop. It also defeats §9.0's identity rule from the other side — the extractor is told not to conflate
  the names, and the critic forces the conflation back in. The fix runs FIRST, ahead of the sum
  reconciliation and the quote requirement: scan every `nameAsSource` / `linkedName` for a near-identical
  name; where one exists it is NEVER a missing entity, and re-reading the PAGE decides between "same
  party, my transcription was wrong, no finding" and "two distinct parties — a naming finding quoting
  BOTH strings". Plus a self-consistency rule: a party named or cited by id anywhere in your own response
  is present and cannot be reported absent elsewhere in it.
- **`qaFlags.records[]` entries carry no `id`.** R8 reproduced `outOfBounds.records[].id` as REQUIRED and
  said nothing about the sibling container, so the critic generalised across and scored a compliant entry
  as "lacking the required `id` field" — complying would have emitted an undeclared key. The schema
  defines exactly `reason` + `status` there, which is *why* §9.0 pins an unlinked inventoried party to
  `qaFlags.records`: it has no record and so no id to give. R8 now reproduces both entry shapes.
- **Ordering findings must show the comparison.** The canonical sort is reproduced correctly in the
  critic, so an ordering finding fails on the COMPARISON — observed: a demand that "Molehill Holding B.V."
  sort AFTER "Rucio Investment S.à r.l.", which its own key order contradicts (m < r). Same shape as the
  `thresholdApplied` phantom: the finding must quote both strings, name the first differing key, and state
  which sorts first — then re-read it, because a comparison supporting the existing order is not a finding.
- **The critic must not fabricate either — a rounding residual is not a missing party**
  (settled 2026-08-22, second occurrence). The eight evidenced shareholders of an intermediate sum to
  **99.99**; the critic raised a CRITICAL "missing 0.01% shareholder" and gave it the name of a
  DOWNSTREAM subsidiary read off the same chart. It did to the extractor exactly what CF.2 forbids the
  extractor from doing to it. The reconciliation guard existed — it was added after the SAME critic
  invented a **0.81%** holder against the SAME 99.99 sum — and it failed because its operative reason
  was *"the added percentage would push the target past 100%"*: true of 0.81 (100.80), false of 0.01
  (exactly 100). The critic followed the justification rather than the rule. Two fixes. **The ±5% band
  is now dispositive** — a sum inside it means the set is COMPLETE, full stop, regardless of whether the
  arithmetic would accommodate one more holder — and a residual of a fraction of a percent is named as
  SOURCE ROUNDING (published percentages are 2dp; 99.98 / 99.99 / 100.01 are complete sets).
  **NAME IT AND QUOTE IT, OR IT DOES NOT EXIST** — the critic's own KERNEL C: every missing-entity
  finding must carry the document, the page and the printed text showing THAT party as a holder of THAT
  target, and the percentage must be the PRINTED figure, never the difference between a sum and 100.
  A party derived from a shortfall, or borrowed from the nearest plausible label on the page, is a
  fabricated party. CF.2 protected the extractor from the critic's assertions; nothing protected the
  case from the critic's own inventions until this clause. The SCOPE GUARD is a second line but cannot
  carry it alone: asserting the party is a shareholder of an in-scope entity makes it in-scope by the
  guard's own logic.
- **The critic's SCOPE GUARD — completeness is bounded by the client's upward structure**
  (settled 2026-08-22). Criterion 2 opened with *"every person, every organization, and every
  org-chart box/node appearing in the documents is present as a record"* — and `monolith_critic/user.txt`
  said the same. A group chart routinely draws the client's own subsidiaries, sisters, merger vehicles
  and unrelated service entities; OC.2 sends exactly those to `outOfBounds.summary` and BARS them from
  `outOfBounds.records` (no `id`) and from `qaFlags.records` (reserved for §9.0 parties *inside* the
  upward structure). So the critic was demanding output the extractor is forbidden to emit, on every
  case with a full chart. A third guard now runs **before** the batching and stop-rule guards:
  **in scope** = the client plus every party the documents evidence as holding a directed ownership or
  control path **to** the client — *including every co-shareholder of every in-scope entity at every
  layer*, however small and whether or not it lies on the path to a UBO (without that clause, narrowing
  would have made a live run's seven omitted 6.47–9.76% holders a non-finding); **out of scope** =
  downstream subsidiaries, sisters, affiliates and parties with no evidenced ownership relation.
  Two carve-outs keep the guard honest. **Unresolved is not out of scope** — only an *evidenced*
  downstream position removes a party; one whose relation cannot be established is an unresolved
  in-scope candidate and its silent disappearance is CRITICAL, or a strict path test would invert
  fail-closed. And **criterion 2 owns the boundary in BOTH directions**: an out-of-scope party emitted
  with `isOutOfBounds = false` is over-inclusion (IMPORTANT); one legitimately retained with
  `isOutOfBounds = true` + reason + status is permitted by OC.1 and is not scored. Criterion 8(c) covers
  DOCUMENT scope only and cross-references rather than scoring, per "score each defect ONCE".
  Extractor side, §9.0 Pass 2 now routes an unlinked party by where rule 5 places it — inside →
  `qaFlags.records`, outside → `outOfBounds.summary` and stop there — closing the same ambiguity that
  let Pass 1's deliberately broad inventory oblige ten `qaFlags.records` entries against OC.2.
- **Rule 9.2.2 — the voting % DEFAULTS to the ownership % (REVERSED 2026-09-04, business rule)**. The
  earlier rule (settled 2026-08-22, from a run-to-run flip on `"100% Shares"`) said a share percentage
  evidences ownership ONLY and voting must be separately stated. Charts state shares, so **every live
  run came back with `actualVotingRightsPercentage = 0` on every record** — the rule was working and the
  output was useless. The KOS text owner reversed it: shares and votes are presumed to move together,
  and the presumption is defeated only by a document that says otherwise. The replacement is a **ladder**,
  not a sentence — a sentence is what produced the whole-set 0:
  **V1 stated** (a voting %, a voting column, votes per share class, a shareholders' agreement, a voting
  cap) → that figure. **V2 expressly non-voting** ("non-voting shares", a STAK depositary receipt whose
  votes sit with the foundation) → `0`, **evidenced**, no flag / no gap / no advisory. **V3 deemed** (no
  document says either way) → this record's own `actualOwnershipPercentage`, unchanged, whatever supplied
  it — a printed figure, §9.2's wholly-owned wordings, §9.2.1's `WO`, §9.2.3's ADV band (a `D` holder
  carries 50.01 in **both** fields). **V4 unknown** (no stated voting AND no ownership figure to deem
  from) → `0` + `MISSING_VOTING_RIGHTS`.
  Four things had to move with it or the change is inert or harmful. **A structure is not a statement** —
  dual-class, preference, golden shares, a STAK, a shareholders' agreement are reasons to *look* for V1/V2
  evidence, but standing alone with no voting position stated they do **not** defeat the default; only a
  document that states the figure, or states there are no votes, does. **The rule governs EVERY voting
  field, not just the direct one** (the business's own follow-up): `dominationVotingRights` takes the
  ladder's figure where it clears 50 — so a majority owner with no stated voting carries both percentage
  fields at the same figure, `DominationBasis` still naming D1, and *the determination never moves*,
  because D1 matched first on the identical number — and `dilutionVotingRightsPercentage` multiplies the
  ladder's figures, so a path deemed end-to-end yields exactly `dilutionOwnershipPercentage`. That
  equality is **arithmetic, not substitution**; it is named as correct in both prompts and in the schema,
  because it is precisely what §10.1.1 used to forbid and is now the most likely false CRITICAL.
  **Both flags are re-scoped to V4 alone** — `MISSING_VOTING_RIGHTS` on a deemed record asserts a gap
  beside the figure that fills it — and `ownershipVotingMismatch` is FALSE on V3 by construction.
  **A deemed figure needs a marker or the default is unauditable**: rule 18's `VotingRightsPercent` token
  carries the rung (`52.27 (deemed)` / `(stated)` / `(non-voting)` / `(unknown)`), and OC.1 says expressly
  that a deemed non-zero figure is grounded by the **ownership** record's `evidenceSnippet` — no separate
  voting quote exists to demand. §9.2.4 gains a **P4 DEEMED** provenance available to the voting field and
  to no other; ownership still has three and no fourth.
  The critic never sees `monolith/system.txt`, so **R12 reproduces the whole ladder**: without it a
  deemed figure is, in its own words, an invented percentage — CRITICAL on every record, and unanswerable,
  since CF.5a bars the extractor from withdrawing a value a rule requires. R12 scores it in both
  directions: a stated figure or an express non-voting statement overridden by the default is CRITICAL,
  and a voting field left at `0` beside an ownership figure is a **missed V3**.
  Decision trail: `QUESTIONS-2026-09-01-voting-rights-default.md` (Q0–Q6 with the business's answers).
- **`TargetSum` is computed from the emitted records, never asserted.** Attempt 1 of the Mollie run
  wrote `TargetSum=[100]` across a set summing 52.27 and declared the case fully validated: §9.3 was
  reported as run and passed on a set it would have failed, so the omission the gate exists to expose
  was certified absent. §9.3 and rule 18's token definition now bind the figure to the records in the
  same output, extend the rule to any narrative "sums to 100%" claim, and the critic **recomputes rather
  than reads** — CRITICAL where the asserted figure sits inside 95–105% and the computed one does not.
- **Critic: score each defect ONCE, and never ZERO times.** "Score it once" is about where a defect is
  written up, not a licence to Pass a criterion whose own observation records a violation of its own
  subject. Client Anchor Validation observed that `ownershipReviewStatus = COMPLETE` was wrong over a
  set with seven missing parties, returned **Pass**, and deferred to Entity Completeness — which never
  scored it. `ownershipReviewStatus` is a `clientAnchor` field (OC.2) and Client Anchor Validation owns
  it. A deferral must name the criterion that will carry the defect.
- **Party IDENTITY is checked separately from party PRESENCE** (settled 2026-08-22, from a live Mollie run).
  A chart carrying both `Molehill Holding B.V.` and `Mollie Holding B.V.` came back with the 52.27%
  link assigned to the wrong one and a fabricated `Molehill --100%--> Mollie Holding B.V.` edge
  inserted so both labels could be kept — promoting a downstream subsidiary to IBO at 52.27%. Every
  numeric gate passed: §9.3 sums from figures alone and is unchanged by a wrong NAME, and the critic's
  reconcile-against-your-own-sum guard then closes the question. Three additions: **§9.0 pass 1**
  requires character-exact labels and treats a STEM difference as two parties (rule 3's list is
  formatting only), barring the invented reconciling edge outright; **§9.0 reconciliation** forbids one
  party holding two positions — named in `outOfBounds.summary` as downstream AND emitted as an ancestor
  — which is also the only place a cycle surfaces when the closing edge points DOWN and rule 5 puts it
  out of scope, where §17's loop detection cannot reach; and the critic gets a matching per-record
  identity check. New flag `PARTY_IDENTITY_UNRESOLVED` (rule 16 + R6, now 46 names) — confirm with the KOS
  text owner.
- **A single 100% holder passes §9.3 by construction**, so passing certifies nothing: an invented
  intermediate layer is normally invented in exactly that shape. Where a target has one holder at 100%,
  the sum is not corroboration — the `evidenceSnippet` must state the 100% (or a §9.2/§9.2.1
  wholly-owned wording) for that owner-and-target PAIR, to §9.2.1's "WO" standard.
- **ORGCHART — how to quote a chart.** OC.1's "contiguous, copied exactly" is unsatisfiable on a
  box-and-connector chart, so the model composed a sentence one run (`"X owns 100% of Y"`) and an edge
  notation the next (`"X -> Y 100% Shares"`) — paraphrase both times, and the critic's verbatim
  spot-check passed both, leaving the control inert. The snippet is now ONE contiguous printed run:
  connector label, else shareholder-table row, else legend. The parties are already in `nameAsSource` /
  `linkedName`; the snippet carries the FIGURE.
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
- **§9.2.4 / §9.3's overshoot limb — a target totalling 100.1% is prevented, not rescaled**
  (settled 2026-08-28, from a live run whose per-target totals came in over 100 by 0.1 and 0.001).
  The ±5% band absorbed it in silence: nothing anywhere said what a FRACTIONAL overshoot meant, so
  it was neither explained nor caught, and the only "fix" available to a model — trimming a holder
  or scaling the set to 100 — is the fabrication the whole prompt exists to prevent. Two halves.
  **§9.2.4 stops the extractor's own arithmetic reaching a direct figure**: every
  `actualOwnershipPercentage` / `actualVotingRightsPercentage` has one of three provenances and no
  fourth — PRINTED (transcribed digit for digit), ASSIGNED by §9.2 / §9.2.1 / §9.2.3, or ABSENT
  (0 + `MISSING_PERCENTAGE` + the gap) — so a residual, an apportionment, a rescale and a share
  count divided by a total are all barred, calculation being confined to the two `dilution*` fields.
  Two live holes drove this. §12.4's *"report rounded half-up to 2 decimal places"* was stated with
  no scope, so it read as authority to re-round a printed 33.333 — harmless on one record, and
  across a shareholder set it moves the target's total off the figure the document supports; it is
  now expressly confined to the dilution fields **in §12.4 itself and in both schema descriptions**,
  since that is where a model reads it at emission. And §9.2.2 lists "a share COUNT" among ownership
  evidence while nothing said whether a percentage may be divided out of one — an undecided question
  is a coin flip (KERNEL E), and the worst available answer is dividing by the total of the holdings
  found, which forces the set to exactly 100 and conceals whatever is missing. Ruled: a share count
  with no printed percentage is `MISSING_PERCENTAGE` + advisory — **confirm with the KOS text owner**.
  **§9.3's new limb says what an overshoot MEANS**, in three steps rather than as prose: (1) name the
  cause from a closed list of four — a "Total Ownership" aggregate recorded as direct, one holder
  counted twice under two spellings, two rule 14 conflict versions added together, a resolved
  collective label emitted beside the parties it resolved to — each of which produces an overshoot of
  *any* size, so none may be skipped because the excess is small; (2) no cause, and the excess within
  half a unit of the printed precision per holder (never more than 1 point) → SOURCE ROUNDING, the
  set is complete, no flag, `TargetSum` carries the real figure; (3) beyond that bound with no cause →
  `OWNERSHIP_PERCENTAGE_CONFLICT` + gap + advisory **without waiting for 105%** — 95–105% is where the
  set is the right SIZE, not a licence to absorb an unexplained overshoot sitting inside it. The critic
  carries both halves (a rescale demand is now a named false finding, precision-in-the-figure is scored
  both ways, and a sub-105% conflict flag is CORRECT rather than over-flagging), or a correctly
  transcribed 100.1 comes back as a CRITICAL demanding the one fix §9.2.4 forbids.
- **Rules 10.2.0 / 10.2.1 — domination is a determination, not an observation** (settled 2026-08-21,
  from a run-to-run flip on a live case). The same record — a 100% direct shareholder — came back
  with `dominationOwnership` = 100 on one run and 0 on the next, `dominationIndicator` = YES on both,
  the 0 moving in lockstep with `controlRights` going null. Both readings survived the prompt:
  §10.2 defined domination as control *"even without majority economic ownership"* while OC.1 said
  only *"0 if no domination is evidenced"*, so a plain majority shareholder had no **separate**
  control basis and 0 was defensible. Three things now close it. **§10.2.0** is a mandatory ladder
  run on EVERY record on BOTH pathways — D1 majority share ownership (including ORBIS "MO" and
  §9.2's wholly-owned wordings, which carry no figure) → D2 majority voting rights → D3 an evidenced
  basis from §10.2's closed list → D4 none — first match wins; `dominationIndicator` is null ONLY on
  the layer-0 client, and "not assessed" is gone as an outcome. **§8.3.1's majority test and §10.2's
  control indicators are two ROUTES to one determination**, not two concepts, so `majority share
  ownership (more than 50%)` joins §10.2's closed list and critic R3 — without it a D1 domination
  scored as an invented criterion. The determination is pathway-independent; only the CONSEQUENCE
  differs (GLOBAL feeds §12.2.1 / §12.4's control-based routes; STREAMLINED additionally drives
  §8.3 inheritance and drill-down). **§10.2.1** defines the two percentage fields, which previously
  had no computation rule anywhere: `dominationOwnership` = this record's `actualOwnershipPercentage`
  on a D1 basis, `dominationVotingRights` = its `actualVotingRightsPercentage` on a D2 basis, both 0
  on a D3-only basis (where `controlRights` must then name it) — and **never** the deemed inherited
  position, which stays in `dilutionOwnershipPercentage`. `dominationIndicator = YES` with both
  fields 0 *and* `controlRights` null is invalid: it asserts domination with no basis anywhere in
  the record. Rule 18 part [1] carries `DominationBasis=`. Two assumptions to confirm with the KOS
  text owner: the direct-figure semantics, and assessing on both pathways.
- **The control-based route is an obligation, not a permission.** §12.2.1 criteria 3/4 and §12.4's
  CONTROL-BASED IBO paragraph were both phrased "may still qualify"; where §10.2.0 returns YES the
  qualification IS met and `isUBO`/`isIBO` follows. Ladder precedence still assigns the LABEL — rung
  8/9 win on first match where a threshold is also met, and rung 10 "Control-Based IBO/UBO" is the
  residual case — or every streamlined UBO would flip to rung 10. §12.2.1's criteria 3 and 4 now
  partition §10.2.0's ladder (3 = D3 control, 4 = D1/D2 majority) instead of both pointing at one
  undifferentiated list, and §12.4 CITES §10.2's list rather than restating it — the restatement had
  silently dropped `trustee / protector control` while saying "do not invent criteria beyond those
  rule 10.2 already lists".
- **`CRITIC-FEEDBACK` CF.5a — retraction is the third barred response.** CF.5 barred ignoring a
  finding and CF.2 barred fabricating to satisfy one; neither covered *withdrawing* an evidenced
  value so a finding has nothing to bite on. Zeroing a percentage, dropping YES to NO, or emitting a
  field at its default rather than its computed value is the same KERNEL E breach as adopting an
  unevidenced assertion — the answer would change because the output was reviewed.
- **The critic could not see any of this.** Criterion 14 named `dominationOwnership` /
  `dominationVotingRights` as "schema-backed" and gave no test for them, and policed only
  over-assertion — so zeroing the fields strictly REDUCED the finding count. It now scores in both
  directions (a >50% link recorded NO is a missed domination; CRITICAL where it changed
  `isIBO`/`isUBO` or truncated a streamlined branch) and carries the three concrete field findings.
  Criterion 14 also demanded `dilutionOwnershipPercentage` = the product with no streamlined
  carve-out while criterion 10 demanded the deemed inherited position — with "score each defect
  ONCE", a correct streamlined run passed or failed depending on which criterion was reached first.
  The carve-out is now in criterion 14, in `critic/user.txt`, and in the schema descriptions of both
  `dilution*Percentage` fields (the schema had said "the product … across every link", full stop —
  and the schema is what is bound at emission time).
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
  MISSING_PERCENTAGE/SOURCE_DATA_NOT_AVAILABLE gap. Under the settled direction contract a null
  `linkedName` identifies exactly one record — the layer-0 client — so in practice that is the
  only record permitted a null quote. A party that cannot be quoted must not be asserted.
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
- **Rule 9.2.1 — "WO" is the abbreviated form of the same evidence.** Registry and database
  extracts often print the relationship as a two-letter CODE and nothing else, so the code is the
  only statement of the percentage on the page. `WO` / `W.O.` / `WO (Wholly Owned)` evidences
  **exactly 100%**, identical in force to §9.2's sentences: `actualOwnershipPercentage = 100`, no
  `MISSING_PERCENTAGE`, no `SOURCE_DATA_NOT_AVAILABLE`, no advisory, and specifically **not**
  `ORBIS_MO_PERCENTAGE_NOT_DISCLOSED` — nothing is missing. It is a §10.2.0 **D1** basis with
  `dominationOwnership = 100` (the figure is KNOWN here, unlike MO where it stays 0), and a WO
  target's §9.3 sum is 100 by definition rather than exempt. **WO and MO are the hazard pair**:
  MO evidences only ">50%" undisclosed (rule 6), WO evidences exactly 100%; reading WO as MO
  discards a known 100 and logs a false gap, reading MO as WO invents a figure. Because "WO" is
  two letters, §9.2.1 requires it be RESOLVED — legend, relationship column, or adjacent heading,
  for that owner-subsidiary pair — never pattern-matched; an unresolved code fails closed to
  0 + `MISSING_PERCENTAGE` + the gap + D4, and the critic is told not to score that fail-closed
  output as a missed 100. No other two-letter code is interpreted by inference. Both critic files
  carry the carve-out, or a correct WO-derived 100 returns as a CRITICAL invented percentage.
- **Rule 6 — ORBIS "MO" is not a percentage**: "MO" / "Majority Owned" / "Majority Control" /
  "Majority-Owned Subsidiary" / "Majority Shareholder" with no stated figure evidences only
  >50%. Never infer 51%, never upgrade to 100% (that is 9.2's job, and only for sole/entire
  ownership wordings). Emit 0 + `MISSING_PERCENTAGE` + `SOURCE_DATA_NOT_AVAILABLE`, flag
  `ORBIS_MO_PERCENTAGE_NOT_DISCLOSED`, and raise an advisory for the exact percentage.
- **Rules 10.1 / 12.3 — dilution (GLOBAL pathway only)**: capture every direct link at its DIRECT percentage in
  `actualOwnershipPercentage`, and additionally record the computed product along the path in
  `dilutionOwnershipPercentage`. IBO/UBO thresholds are tested against CUMULATIVE (diluted)
  ownership, not a single direct link.
- **Rule 10.1.1 — voting dilutes over the ladder's figures**: every link has a voting operand, because
  §9.2.2 gives every record one, so the ordinary case is a genuinely-computed product — and where every
  link was deemed, the result equals `dilutionOwnershipPercentage` and is CORRECT. Emit
  `dilutionVotingRightsPercentage = 0` with `DILUTED_VOTING_INCOMPLETE` **only** where some link took
  §9.2.2 V4 and has no operand at all; a V2 evidenced zero is a real operand and takes no flag.
- **Missing percentages**: percentage fields are numeric. Missing → emit `0`, add
  `MISSING_PERCENTAGE` to that record's `qaFlags`, plus a `SOURCE_DATA_NOT_AVAILABLE` entry in
  the top-level `outOfBounds.records` — see "Gap tokens are reasons, never statuses" above for
  that entry's required shape. Same unknown-vs-real-0 principle as above.
- **Rules 7 / 13 — local overlay beats global**: apply the Local Addendum threshold BEFORE the
  global test (e.g. South Africa 5%+ for UBO, US inclusive ≥25% for IBO). Multiple adoption
  locations → apply the stricter one. Missing local rules → data gap + QA flag.
- **Rule 11.2 — listed entity stop rule**: only when entityType is Listed Entity AND listing
  evidence is present, validated and bound to the client anchor AND the applicable rules permit
  it. Then do not drill down, do not determine UBO/IBO, and set
  `ownershipReviewStatus = COMPLETE`. **Client-listed ≠ mid-chain**: the boxed stop rule fires
  only where the listed entity IS the client and halts UBO/IBO determination entirely, so
  `isIBO`/`isUBO` false across the set is then CORRECT; a mid-chain listed parent takes ladder
  rung 4's label via §11.1 and inherits none of that halt. Both prompts carry the distinction —
  without it the critic scores a correct client-listed stop as the ladder zeroing the booleans.
- **Stop rules bound SCOPE; zero-data-loss applies only inside it** (settled 2026-08-21). Three
  places stop extraction short of the top: §11.2's client-listed stop, §11.3 MOS / §11.4
  government-SOE, and §8.3.1's STREAMLINED branch termination at a non-dominating (≤50%) owner.
  Parties BEYOND an evidenced, recorded, permitted stop are emitted **nowhere** — not
  `extracted_records`, and not as §9.0 Pass 2 unlinked-party `qaFlags.records` entries. They are
  out of scope by rule, not omitted. This is the **only** exception to §9.0's "inventory every
  party REGARDLESS", and it had to be stated because rule 9 is `priority="CRITICAL"` while rules
  8 and 11 are `HIGH` — a bare priority reading defeats every stop rule. The critic carries a
  matching **STOP-RULE GUARD** in criterion 2, a second carve-out alongside the batching guard:
  it verifies the STOP (evidenced via `listingProof` / `controlsApplied` / `stopRuleEvidence` /
  the §8.3.1 governanceBasis note) and never the structure above it, and its independent
  document sweep drops stopped-branch parties before scoring. An unevidenced or barred stop is
  still CRITICAL — under Entity-Type Logic for §11, under Methodology for a truncated GLOBAL run.
  Without this the critic demanded the full hierarchy on every streamlined run, criterion 2
  (PRIMARY, "CRITICAL whatever the reason offered") overrode criteria 10 and 12 which expect
  exactly that absence, and the RETRY could never clear: CF.5 could not settle it either, since
  the dispute is over rule scope rather than evidence. Root cause was a mis-scoped citation —
  the critic justified absolute completeness with "section 10.1 / Kernel D zero-data-loss", but
  §10.1 carries an express "applies to the GLOBAL pathway" guard and the other statement of
  preserve-every-intermediate-layer sits in §12.1 **GLOBAL** IBO DEFINITION. Zero-data-loss
  forbids dropping a party from a path you DO traverse; it never requires traversing a path the
  Domination Methodology closes.
- **Rule 11.1.1 — notional UBO**: where no natural person is found at threshold, do NOT classify
  anyone as Notional UBO. Emit `NOTIONAL_UBO_ASSESSMENT_REQUIRED`, identify the Senior Most CSM
  by designation only, and take ladder rung 12 "Possible Notional UBO". Rung 11 "Notional UBO"
  is reachable ONLY where a Local Addendum expressly requires the affirmative classification, in
  which case `countryOverrideNote` must name it. (Rule 16 previously spelled this flag
  `NOTIONAL_UBO_REQUIRED` as well — the duplicate is gone; `NOTIONAL_UBO_ASSESSMENT_REQUIRED` is
  the only spelling.)
- **Rule 16 — QA flags are exception-only**: emit only when an issue materially affects decision
  correctness, ownership completeness, audit reliability, or the ability to apply local rules /
  classify. Normal complexity that is fully explained in the reasoning gets no flag.
- **Rule 16 — picking a flag where a rule does not name one**: ~9 rules say "add a QA flag"
  without naming which, and a free choice across a 40-item list is run-to-run variance. Rule 16
  now carries a **binding mapping** for each of those sites (rule 1 → `DOCUMENT_INSTANCE_NOT_REVIEWED`,
  rule 2 → `DOCUMENT_TRANSLATION_REQUIRED`, rule 4 → `CLIENT_ANCHOR_NOT_CONFIRMED`,
  rule 5 → `INCOMPLETE_OWNERSHIP_CHAIN`, rule 7 → `LOCAL_RULE_MISSING`, INPUT-VALIDATION →
  `MISSING_MANDATORY_INPUT`, …) plus the general rule: most specific category wins, never invent
  a name. `DOCUMENT_INSTANCE_NOT_REVIEWED` was **added** — rule 1's condition matched no existing
  category, and a silent mis-mapping is worse than a new name. Confirm with the KOS text owner.
- **`conflictTag` is REQUIRED on every record** (schema `required` + "Always emitted"). `"C: clear"`
  is a positive assertion that this record's evidence does not conflict — not a default.

## Known open questions (not decided in-repo)

Entries marked CLOSED are a DECISIONS REGISTER, kept so the same question is not put to the business a
second time. As at 2026-08-28 the only genuinely OPEN item in this section is `countryProfileApplied`,
which predates the 7X / 9.2.3 / 9.0A workstream.

- **The 2026-08-27 business questions are CLOSED** (answered 2026-08-28). Recorded here because the
  answers changed the build: the **special allocation rules were withdrawn** and replaced by a ranged
  base table, so the 74.99 / 49.99 / 50.00 figures and every combination case are gone;
  **`PARTY_IDENTITY_UNRESOLVED` was confirmed removed** from §9.0A; **maker / checker comments were
  withdrawn** as an evidence source (rule 6 H3 and §9.0A's sweep) — note KERNEL F's unrelated "audit,
  maker, checker and QA review" is a reviewer-role sentence and stays; **§8.2's gate wins** over a
  Streamline input where a Local Addendum requires drill-down; **removal of a resolved collective label
  is intended**; and the §9.0A example now uses the business's own figures (block 100%, funds
  10/20/30/20/20). Resolved collective percentages are **not multiplied** by the block's percentage.
- **"Multiply only if Owners are same" was overtaken and is CLOSED** (2026-08-28 12:54). The first
  answer read *"Multiply only if Owners are same, if owners are different, do not multiply"*; a second
  reframed the operation as CLUBBING; the third settled it — *"Even everything is same it should not
  clubbed / As they are two different funds."* So neither multiplication nor merging is performed, the
  same-owner limb never had to be built, and the narrower same-holder rule written earlier that day was
  removed. See the NEVER-CLUBBED entry above; nothing here is outstanding.
- **A `D` code always evidences domination - CLOSED by implication** (2026-08-28). With the special
  allocations withdrawn, D is 50.01 on every run, which is more than 50%, so §10.2.0 D1 fires and every
  D holder carries `dominationIndicator = YES` - which can set `isIBO` on the control-based route. The
  tension: D's own range, "50% But Less Than 75%", admits exactly 50%, which is not a majority. Put to
  the KOS text owner and answered obliquely - *"We have removed the allocation rule n should add the
  table in word doc"* - so it is settled by IMPLICATION, not by a literal yes, and that is recorded
  rather than dressed up as a confirmation. The implication is sound: if the table is the whole rule the
  PERCENTAGE column is the operative figure, and reading the RANGE back in would restore the exact
  ambiguity the withdrawal removed, leaving every D holder's domination arguable. Both prompts pin the
  percentage and bar reasoning from the range. If it is ever reversed the change is contained - §9.2.3's
  domination bullet, critic R9 consequence (iii), one test.
- **Resolved collective-owner parties are NEVER clubbed** (settled 2026-08-28). §9.0A resolves a
  collective label into the parties behind it; those parties are emitted one record each and are never
  merged - not for a shared label, similar names, a shared manager / sponsor / GP / fund family /
  address, and **not even where two names appear identical**, which the KOS text owner ruled is two
  different funds rather than one party listed twice. Clubbing is the direction that can invent a
  classification (two 15% funds merged become a 30% IBO neither is alone), so it is barred outright
  rather than conditioned on identity; refusing to club loses nothing, since both holdings survive and
  both reach §9.3's sum. Unresolved identity keeps the entries separate with `PARTY_IDENTITY_UNRESOLVED`,
  never merges them. One collision had to be closed in terms: §9.3's "each distinct HOLDER once" exists
  for rule 14's conflict versions - two SOURCES describing ONE link - and is NOT a licence to merge two
  named parties within one source.
- **`ownershipApproach = MIXED` is retained deliberately and must NOT be removed** (settled
  2026-08-28). No rule produces it: §8.0 maps the KYC level to a GLOBAL or STREAMLINED candidate and
  §8.2 decides, both case-level, so there is no stated route to MIXED and it may never be emitted. It
  stays in the schema enum anyway because the downstream code carries a matching enum, and dropping the
  value there would force a code change on the consuming side. This is a deliberate orphan, confirmed by
  the KOS text owner - do NOT "clean it up", and do NOT invent a trigger rule for it either. OC.2 and the
  schema description already set an evidence bar high enough that a model will not reach for it, and the
  critic's R10 check would flag a MIXED emitted against an explicit is_streamlined input.
- **`countryProfileApplied` has no vocabulary.** Schema says `'CP-XX' code`; no rule defines the
  codes, so neither agent can populate or check it against anything.

## Batching (large outputs)

`monolith/user.txt` declares a `<definition_of_too_big>` block: more than **50** elements under
`extracted_records`. The LLM must not truncate unless the `submitBatch` tool is present.
(The old pipeline agents used a 200-`nodes` threshold — different field, different number.)
The **critic carries a matching batching guard**: a partial batch (ids not 1..N contiguous, or a
set truncated mid-structure) is scored `Mixed_Partial_Pass_Partial_Fail`, never a CRITICAL
missing-entity finding. Without it the critic RETRYs every batch but the last.

## Test harness

`tests/` contains pytest suites covering the two remaining agents:

- `test_monolith_schema.py` — both schemas are valid Draft-07; monolith top-level field set;
  OC.4 container fields typed as objects; critic verdict enum
- `test_monolith_prompts.py` — template-variable sets for both `user.txt` files; the
  `<definition_of_too_big>` batch threshold; every schema record property is named in
  `system.txt` (prompt-backs-schema invariant); critic `<criterion name="...">` list matches the
  schema enum exactly (17, in order); the "Total Ownership" rule is present in both prompts; the
  direction contract is consistent across both prompts and the schema; each critic `<reference>`
  block R1–R8 is present, with the 22 ladder rungs and the `relationshipType` enum checked
  against the schema enums themselves; every `CRITIC-FEEDBACK` clause CF.0–CF.7; rule 16
  enumerates every flag another rule mandates (checked both ways, against the critic too); gap
  tokens are reasons and never statuses; `id` ordering reads the same in prompt and schema; the
  provider's `documentClassifications` is reconciled rather than obeyed; the batching guard; that
  no record may rest on a governance role alone; criterion names spelled identically in both
  critic files (`&` not `&amp;`, or the enum is violated); every `<reference>` block R1–R8 is
  actually cited; and CLAUDE.md itself lists every criterion in schema order and names every rule
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

## Database deployment

`DATABASE.md` is the contract for getting these prompts into Oracle
(`KYC_DATA_OWNER.AI_CHAT_PROMPT`) — **read it before touching any `insert_prompt.sql`.**
Short version: the prompts are executed through a Java `PreparedStatement` that takes ONE
statement, so each `<agent>/insert_prompt.sql` is a single `INSERT ... SELECT` with no
PL/SQL, no comments and no trailing semicolon. `system.txt` → `SYSTEM_INSTRUCTION`,
`user.txt` → `CW_PROMPT_TEXT`, `schema.json` → `CW_RESPONSE_SCHEMA`; every other column is
inherited from the previous `PROMPT_VERSION` (a prompt change is always a NEW version row,
never an in-place update). Two hazards drive the whole design: Oracle's **4000-BYTE**
literal cap (not 32767 — that is PL/SQL only), handled by `TO_CLOB(..)||TO_CLOB(..)`; and
`&` `;` `:` `?`, which the client consumes before Oracle sees them (`:word` is a named
bind) — handled by encoding each as a sentinel char and restoring it with one `TRANSLATE`
per piece. The `.sql` files are GENERATED: edit prompts, then run
`python3 tools/gen_insert_sql.py`, never hand-edit the SQL.

## Docs

`docs/superpowers/specs/` and `docs/superpowers/plans/` retain the monolith design history
(single-prompt design, single-parent-schema, critic design, prompt-backs-schema, v39 update).

`CHANGES-2026-08-27-business-rules.md` is the clause-by-clause trace of business rules **7X**
(methodology governance / the blank-`isStreamlined` bug), **9.2.3** (SEC Form ADV ownership codes) and
**9.0A** (collective owners and legends): what was implemented and where, what was NOT and why, every
question put to the business team with the line number in their own file, and their answers across four
rounds. Read it before revisiting any of those three rules — several clauses in it were deliberately
NOT implemented as supplied (an off-list `DominationBasis` token, a flag with a conflicting trigger, a
worked example carrying an inverted edge), and the reasons are recorded there rather than in the prompt.
