# Monolith — Single-Call KYC Ownership Extraction

A **single-prompt** ownership extractor. It takes a validated client entity plus its
case documents and returns one JSON object describing the entity's complete
ownership & control structure — shareholders, layers, percentages, voting/control
rights, UBO/IBO classification support, conflicts, data gaps, advisory requests and
QA flags.

It is the **one-call alternative** to the multi-agent wave pipeline (Chart Reader →
W1 → Critic → W2 → W3 → W4). Same problem, one model call instead of six agents.

- `system.txt` — role + all extraction/classification rules (KERNEL, ORGCHART, processing order, rules 1–18, OUTPUT-CONTRACT)
- `user.txt` — task + input placeholders
- `schema.json` — Draft-07 schema the output must conform to
- `REVIEW.md` / `prompt-schema-sync-analysis.md` — reconstruction & sync notes

## What it does (in order)

Executes 16 mandatory gates (system.txt `PROCESSING-ORDER`): document inventory →
translation → name normalization → **client anchor validation** → scope binding →
source classification (H1/H2/H3) → local requirement selection → methodology
(Global vs Streamlined) → layer-by-layer ownership extraction → control/voting/
domination → entity-type logic → UBO/IBO decision support → conflict reconciliation
→ advisory → QA flags → completeness validation.

Core principles: **evidence-only** (no external knowledge/inference), **client-anchored**,
**zero data loss** (never drop a node for being below-threshold/0%/missing), **deterministic**,
**fail-closed** (record a gap + QA flag rather than guess).

## Inputs (`user.txt`)

| Placeholder | Meaning |
|---|---|
| `{{clientEntityName}}` | The entity to anchor ownership extraction to |
| `{{entityType}}` | Entity type (drives entity-type-specific logic, rule 11) |
| `{{isStreamlined}}` | Global vs Streamlined pathway selector (rule 8) |
| `{{adoptionLocations}}` | Jurisdiction(s) → local thresholds/overlays (rules 7/13) |
| `{{riskRating}}` | Risk tier (affects some local thresholds, e.g. Qatar) |
| `{{documentClassifications}}` | Provided doc class hints |
| `{{gcsDocumentPaths}}` | Case documents to read |
| `{{kosDocuments}}` | KOS / Local Addenda rule extracts (rules, not evidence) |
| `{{verificationDates}}` | Document currency/recency inputs |
| `{{ownershipInputs}}` | Structured ownership inputs |

## Output — schema field reference & UI display checklist

The output is one JSON object with **5 top-level fields**. Every field below is
listed. **Tick the box to display the field on the UI.** Pre-ticked boxes are a
*suggested* default (headline / reviewer-facing fields); untick or add freely.

> Checkbox legend: `[x]` = suggested for UI · `[ ]` = audit/plumbing, hide by default.

### 1. `clientAnchor` (object) — which client, and was it confirmed?

The at-a-glance answer to "which entity was this run about". No record-hunting needed.

- [x] **`clientAnchor.providedName`** — client name exactly as supplied in `{{clientEntityName}}`
- [x] **`clientAnchor.resolvedName`** — client legal name as matched in the docs (null if unconfirmed)
- [ ] `clientAnchor.clientRecordId` — id of the layer-0 `extracted_records` entry (links anchor → graph)
- [x] **`clientAnchor.validationStatus`** — `VALIDATED` · `VALIDATED_WITH_NORMALIZATION` · `CONFLICTING_CLIENT_NAME` · `CLIENT_ANCHOR_NOT_CONFIRMED`
- [x] **`clientAnchor.ownershipReviewStatus`** — `COMPLETE` · `COMPLETE_WITH_QA_FLAGS` · `INCOMPLETE_ADVISORY_REQUIRED` · `NEED_REVIEW` · `DOC_INVALID`

### 2. `ownershipApproach` (top-level)

- [x] **`ownershipApproach`** — case methodology: `GLOBAL` · `STREAMLINED` · `MIXED` · `NEED_REVIEW`

### 3. `extracted_records[]` — one record per ownership link

Each owned node produces one record **per owner** (N owners → N records, same
`linkedName`, different `nameAsSource`). Every record reads "`nameAsSource` is a
`[relationshipType]` of `linkedName`" — the subject is the OWNER and `linkedName` is the entity
it owns, one layer closer to the client. The client is the record with `layer == 0`.

**Identity & position**
- [x] **`id`** — sequential 1-based, document reading order
- [x] **`itemType`** — `Natural Person` · `Non Natural Person`
- [x] **`nameAsSource`** — name exactly as stated in the source
- [x] **`linkedName`** — the entity this record's party owns (one layer closer to the client); null ONLY for the layer-0 client — an evidenced ultimate parent still names the entity below it and is marked by `isUltimateParent`
- [x] **`layer`** — 0 = client, +1 per step toward owners
- [x] **`relationshipType`** — `shareholder`/`parent`/`member`/`partner`/`general partner`/`limited partner`/`trustee`/`settlor`/`beneficiary`/`protector`/`foundation council`/`nominee shareholder`/`voting controller`/`contractual controller`/`listed parent`/`regulated parent`/`government owner`/`other` (null for client)
- [x] **`roleCapacity`** — `Client`/`Direct Owner`/`Indirect Owner`/`IBO`/`UBO`/`Control-Based IBO/UBO`/`Notional UBO`/`Possible Notional UBO`/`General Partner`/`Limited Partner`/`Trustee`/`Settlor`/`Beneficiary`/`Protector`/`Foundation Controller`/`Listed Parent`/`Regulated Parent`/`Government Owner`/`Exempt Entity`/`Non-Qualifying Owner`/`Information Only`/`Unknown / Needs Review`
- [x] **`isUltimateParent`** — top parent / evidenced endpoint of its path

**Classification flags**
- [x] **`isUBO`** — is an Ultimate Beneficial Owner
- [x] **`isIBO`** — is an Intermediate Beneficial Owner

**Ownership & voting**
- [x] **`actualOwnershipPercentage`** — direct % as stated (0 if missing → see `qaFlags`)
- [x] **`actualVotingRightsPercentage`** — direct voting % as stated (0 if missing)
- [ ] `dominationOwnership` — domination ownership %, 0 if none (rule 10)
- [ ] `dominationVotingRights` — domination voting %, 0 if none (rule 10)
- [x] **`dominationIndicator`** — `YES`/`NO`; null if not assessed
- [x] **`controlRights`** — documented control rights text (board appointment, veto, GP control…); null if none
- [x] **`ownershipVotingMismatch`** — economic vs voting divergence
- [ ] `controlBasedUBOAssessmentRequired` — control evidence needs a control-based UBO assessment

**Evidence & source**
- [x] **`documentName`** — controlling source document
- [ ] `pageNumber` — page of the controlling source (integer)
- [x] **`sourceClass`** — `H1` (primary) · `H2` (supporting legal) · `H3` (corroborative)
- [x] **`evidenceSnippet`** — verbatim quote grounding the link/%/direction; null → gap logged
- [x] **`listingProof`** (object|null, listed entities only):
  - [x] **`listingProof.exchange`**
  - [x] **`listingProof.ticker`**
  - [x] **`listingProof.isin`**
  - [x] **`listingProof.listedEntityName`**
  - [ ] `listingProof.sourceDocument`

**Local overlay & rules**
- [ ] `localOverlayApplied` — a Local Addendum overlay was applied (rules 7/13)
- [x] **`thresholdApplied`** — applied threshold string (e.g. `"25% or more"`, `"10%"`); null if none
- [ ] `inclusiveThreshold` — threshold is inclusive ("equal to or more than", §13.1)
- [ ] `drillDownRequired` — local rules force drill-down past an otherwise-permitted stop (§13.3)
- [ ] `countryProfileApplied` — `CP-XX` overlay code; null if none
- [ ] `countryOverrideNote` — note when a local rule overrode the global rule; null otherwise
- [ ] `controlsApplied[]` — stop/control/exemption rules applied (listed-entity stop, government exemption, MOS, notional-UBO, local drill-down)
- [ ] `coverageMode` — always `ALL`

**Conflict / reasoning / per-record nesting**
- [x] **`conflictTag`** — `C: clear` · `C: resolved` · `C: unresolved` (rule 14, always emitted)
- [ ] `governanceBasis` — the full 8-part reasoning (rule 18) as one structured string *(reviewer detail / drill-down panel)*
- [x] **`qaFlags[]`** — per-record exception flags (rule 16)
- [ ] `outOfBounds` (per-record scope assessment):
  - [ ] `outOfBounds.isOutOfBounds`
  - [ ] `outOfBounds.reason`
  - [ ] `outOfBounds.status` — `NEED_REVIEW` · `DOC_INVALID` · `COUNTRY_OUT_OF_SCOPE` · `DOCUMENT_NOT_RELEVANT`

### 4. `outOfBounds` (top-level object) — out-of-scope documents/records + data gaps

- [ ] `outOfBounds.summary` — starts with documentName, overall OOB summary
- [ ] `outOfBounds.documents[]` — `fileName`, `reason`, `status` (`DOC_INVALID`/`DOCUMENT_NOT_RELEVANT`)
- [ ] `outOfBounds.records[]` — `id`, `reason`, `status` (`NEED_REVIEW`/`COUNTRY_OUT_OF_SCOPE`); also holds `SOURCE_DATA_NOT_AVAILABLE`/dataGaps

### 5. `qaFlags` (top-level object) — case-level QA summary

- [x] **`qaFlags.summary`** — starts with triggering documentName/trigger; concise
- [x] **`qaFlags.records[]`** — `reason`, `status` (`CONFIDENT`/`NOT_CONFIDENT`/`NEED_REVIEW`/`DOC_INVALID`); unresolved rule-14 conflicts fold into `reason`

### 6. `advisory` (top-level object) — requests for missing information

- [x] **`advisory.summary`**
- [x] **`advisory.records[]`**:
  - [x] **`advisoryType`** — `CLIENT_OUTREACH` · `RM_OUTREACH` · `INTERNAL_DOCUMENT_REQUEST` · `LOCAL_ADVISORY_REVIEW` · `ACO_ESCALATION` · `BLAFC_ESCALATION` · `TRANSLATION_REQUEST` · `NO_ADVISORY_REQUIRED`
  - [x] **`priority`** — `HIGH` · `MEDIUM` · `LOW`
  - [x] **`reason`**
  - [ ] `requestedInformation`
  - [ ] `impactedDecision`
  - [ ] `relatedEntity`
  - [ ] `relatedDocument`
  - [x] **`proposedAdvisoryText`** — ready-to-send advisory message

## Field-count summary

- Top-level: **5** required objects/strings (`clientAnchor`, `extracted_records`, `outOfBounds`, `qaFlags`, `advisory`, `ownershipApproach`).
- `clientAnchor`: **5** fields.
- `extracted_records[]`: **35** fields (incl. nested `listingProof`, per-record `outOfBounds`).
- Every schema field has a documented source in `system.txt` (OUTPUT-CONTRACT OC.1–OC.3); prompt ↔ schema are in sync.
