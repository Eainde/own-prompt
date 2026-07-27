# Monolith prompt ↔ schema sync analysis

**Date:** 2026-07-27
**Prompt:** v39 (`OWNERSHIP_EXTRACTOR_CLASSIFIER_V39`, KOS v8.0a) — `system.txt` + `user.txt` (source of truth `source.md`)
**Schema:** `schema.json` — "Ownership Structure Candidates Schema" (raw `schema.source.txt`)

This document maps every schema field to the prompt, and every major prompt output to the schema, and marks each **in sync**, **partial**, or **out of sync**. Section references (§N) are to the v39 prompt.

---

## 0. Executive summary

**Overall: substantially OUT OF SYNC.** The schema is a pre-v39 **CSM / senior-manager governance-classification** shape with an "Ownership Structure" title. The identity skeleton fits, but:

- **13 of 27 required per-record fields have ZERO references in the prompt** — the model must emit them with no guidance, using governance vocabulary foreign to the ownership task. This forces fabrication, breaking Kernel C (evidence-only) and Kernel E (determinism).
- **~15 distinct v39 outputs have no schema field at all** (outbound, roleCapacity taxonomy, methodology, source class, layer, listing proof, dataGaps, client-anchor status, review status, §19 summary, eight-part reasoning, cycles, …).
- **11 direct contradictions** (not just gaps) — e.g. 8-part vs 7-part reasoning, a `confidenceScore` inclusion threshold vs Kernel D zero-data-loss, `"Not Available"` literals vs numeric-only % fields, single `linkedName` vs multi-path preservation.

Counts:

| | Count |
|---|---|
| Per-record schema properties | 30 (27 required + 3 optional) |
| — In sync | 8 |
| — Partial / weak | 8 |
| — Orphan (0 prompt guidance) | 14 |
| Prompt outputs with **no** schema home | ~15 |
| Direct contradictions | 11 |

---

## 1. Schema → prompt (field-by-field, per-record `extracted_records[]`)

Legend: ✅ in sync · 🟡 partial/weak · ❌ orphan (prompt gives no basis) · **R** = required by schema

| # | Schema field | R | Status | Prompt basis / problem |
|---|---|---|---|---|
| 1 | `id` | R | ✅ | Sequential reading-order id; consistent with document-order extraction. |
| 2 | `itemType` (INDIVIDUAL/ORGANIZATION) | R | 🟡 | §9 `ownerType` has many kinds (Natural Person, Government, Listed, Regulated, Trust, Partnership, Foundation, Unknown). Two-value enum collapses all non-person kinds into ORGANIZATION. |
| 3 | `nameAsSource` | R | ✅ | §9 `ownerName` / entity name as stated. |
| 4 | `linkedName` | R | 🟡❌ | Single scalar parent link. **Direction undocumented** (owner→owned vs owned→owner). Cannot represent multi-path/convergence (Kernel D, §10). Term never appears in prompt. |
| 5 | `pageNumber` (integer ≥1) | R | 🟡 | §9 `pageOrSection`. Prompt permits `unknown` (§18 SourceRegister `pageNumber=[.../unknown]`); schema forbids non-integer → cannot record unknown page. |
| 6 | `documentName` | R | ✅ | §9 `sourceDocumentName`, §1 inventory. |
| 7 | `isUltimateParent` | R | 🟡 | Concept exists (§8 "top parent entity", endpoints) but term not used; fine as a derived boolean. |
| 8 | `isIBO` | R | 🟡 | §12 IBO is ONE of ~20 `roleCapacity` values; boolean loses the rest. |
| 9 | `isUBO` | R | 🟡 | Same as isIBO. No slot for Notional UBO / Possible Notional UBO / Control-Based IBO-UBO (§12, §11.1.1). |
| 10 | `actualOwnershipPercentage` (num 0–100) | R | 🟡 | Maps to §9 direct %, BUT cannot hold `"Not Available"` (§9) or `"Negligible"` — numeric only. See Contradiction C3. |
| 11 | `actualVotingRightsPercentage` (num 0–100) | R | 🟡 | Maps to §9 voting %; same literal limitation. |
| 12 | `accumulatedOwnership` (num 0–100) | R | ❌ | Prompt never asks for "accumulated". §10 says extractor captures **direct** links; the *system* may calculate dilution — not the extractor. Required field with no basis. |
| 13 | `accumulatedVotingRights` (num 0–100) | R | ❌ | Same as #12. |
| 14 | `dominationOwnership` (num 0–100) | — | 🟡❌ | §10 domination is control indicators + a YES/NO `dominationIndicator`, not a 0–100 number. Mismatched shape. |
| 15 | `dominationVotingRights` (num 0–100) | — | 🟡❌ | Same as #14. |
| 16 | `conflictTag` (C: clear/resolved/unresolved) | — | 🟡 | §14 needs "retain ALL versions" with both values + sources + `resolution_strategy` (often `deferred`). A single tag records that a conflict exists but **cannot store the two values/sources** → data loss. |
| 17 | `confidenceScore` (0–1, "0.70 for inclusion") | R | ❌ | Prompt defines **no** 5D scoring, no positives/negatives, no consensus multiplier, no 0.70 threshold. Also the "threshold for inclusion" contradicts Kernel D (Contradiction C2). |
| 18 | `governanceBasis` ("7-part") | R | ❌ | §18 mandates an **EIGHT-part** reasoning format, and its parts differ. Schema's 7 parts are CSM governance ("GOVERNANCE ROLE", "SIGNING NARRATIVE", `signatoryType`). See C1. |
| 19 | `canonicalReasonTokens` | R | ❌ | CSM token grammar (`GOV=` `SRC=` `ESEL=` `signatoryType`). No prompt basis. |
| 20 | `temporalStatus` (current/former/unknown) | R | ❌ | Owner-tenure "current/former" is a CSM role concept; the ownership prompt has no such per-node field. |
| 21 | `countryProfileApplied` ('CP-XX') | R | ❌ | Prompt uses `adoptionLocations` + Local Addenda overlay (§7/§13); never "CP-XX" profile codes. |
| 22 | `countryOverrideNote` | R | ❌ | No prompt basis (paired with #21). |
| 23 | `scopeTag` ('S: branch') | R | 🟡❌ | §5 has branch/entity-scope binding, but the "S: branch" tag format is CSM. |
| 24 | `currencyTag` ('U: low authority source') | R | 🟡❌ | §5/§14 discuss currency/quality, but this tag grammar is CSM. |
| 25 | `transparencyCode` (CONFIDENT…MKF_APPLIED) | R | ❌ | CSM enum ("MKF_APPLIED = executive body override"). No ownership basis. |
| 26 | `coverageMode` (ALL) | R | ✅ | "All emitted regardless of currency" is consistent with Kernel D zero-data-loss. |
| 27 | `negativeSignals[]` | R | ❌ | CSM scoring signals ("former-only roles (-0.40)"). Tied to the absent scoring model (#17). |
| 28 | `controlsApplied[]` | R | ❌ | CSM governance control-rule tags — **not** ownership control (§10 control rights). Misleading overlap of the word "control". |
| 29 | `qaFlags[]` (per-record) | R | ✅ | §16 QA flags (exception-only). Good match. |
| 30 | `outOfBounds` (obj) | R | ✅ | §5 entity-scope binding / out-of-bounds routing. Good match. |

**Per-record tally:** ✅ 8 · 🟡 8 · ❌ 14 (of which orphan-and-required = 13).

---

## 2. Schema → prompt (top-level fields)

| Schema field | Status | Prompt basis |
|---|---|---|
| `extracted_records[]` | ✅ | The flat record list. |
| `outOfBounds` {summary, documents, records} | ✅ | §5 out-of-bounds documents/records; §1 inventory. Good match. |
| `qaFlags` {summary, records} | ✅ | §16 qaFlags summary ("begin with documentName…"). Good match. |

Top-level structure is the **most in-sync part** of the schema.

---

## 3. Prompt → schema (v39 outputs with NO schema home)

These are prompt-mandated outputs that have nowhere to be written:

| Prompt output | § | In schema? |
|---|---|---|
| `layer` (Layer 0…N per node) | §9, processing order | ❌ no `layer` field |
| `relationshipType` (shareholder/parent/member/partner/GP/LP/trustee/settlor/beneficiary/protector/foundation council/nominee/voting controller/contractual controller/listed parent/regulated parent/government owner) | §9 | ❌ none |
| `roleCapacity` taxonomy (~20 values incl. Notional UBO, Possible Notional UBO, Control-Based IBO/UBO, GP, LP, Trustee, Settlor, Beneficiary, Protector, Foundation Controller, Listed/Regulated Parent, Government Owner, Exempt, Non-Qualifying, Information Only) | §12 | ❌ only `isUBO`/`isIBO` booleans |
| Ownership approach GLOBAL / STREAMLINED | §8 | ❌ none |
| Source classification H1 / H2 / H3 + admissibility (`sourceClass`, `sourceUse`) | §6 | ❌ none (only free text inside `governanceBasis`) |
| `clientValidationStatus` (VALIDATED / VALIDATED_WITH_NORMALIZATION / CONFLICTING / NOT_CONFIRMED) | §4 | ❌ none |
| `ownershipReviewStatus` (COMPLETE / INCOMPLETE_OUTBOUND_REQUIRED / NEED_REVIEW / …) | §17 | ❌ none |
| Listing proof (exchange, ticker/ISIN, listed entity name) + `stopRuleApplied` / `stopRuleEvidence` | §11.2 | ❌ none |
| `evidenceSnippet` / `direction_proof` (verbatim quote per link) | §9 / §9.1 | ❌ none |
| Domination detail: `dominationIndicator` YES/NO, `controlRights`, `ownershipVotingMismatch`, `controlBasedUBOAssessmentRequired` | §10 | ❌ (`dominationOwnership/VotingRights` are 0–100 numbers, wrong shape) |
| Full conflict record (`value_a`/`source_a`/`value_b`/`source_b`/`resolution_strategy`/`resolved_value`) | §14 | ❌ (`conflictTag` only flags existence) |
| Outbound Assessment (`outboundRequired`, `outboundType` enum, `priority`, `reason`, `requestedInformation`, `impactedDecision`, `relatedDocument`, `relatedEntity`, `proposedOutboundText`) | §15 | ❌ none (`outOfBounds` is a *different* concept: scope exclusion) |
| `dataGaps` / `outboundSummary` / `SOURCE_DATA_NOT_AVAILABLE` | Kernel A, §17 | ❌ no `dataGaps` field |
| Local overlay applied / `thresholdApplied` / `inclusiveThreshold` / `drillDownRequired` | §7 / §13 | ❌ (`countryProfileApplied` is a CP-code, not a threshold) |
| Eight-part reasoning `[0]`–`[7]` | §18 | ❌ (`governanceBasis` is CSM 7-part) |
| §19 Final Ownership Review Summary (19.1–19.7, incl. Final Agent Conclusion) | §19 | ❌ none |
| Cycles / ownership loops ("detected, preserved, not repeatedly traversed") | §17 | ❌ no cycle field |

---

## 4. Direct contradictions (beyond missing fields)

| # | Contradiction | Where |
|---|---|---|
| C1 | **8 vs 7:** §18 requires an EIGHT-part reasoning format; `governanceBasis` description says "7-part" — and the 7 are CSM parts, not the prompt's eight. | §18 vs `governanceBasis` |
| C2 | **Inclusion threshold vs zero-data-loss:** `confidenceScore` says "Threshold: 0.70 for inclusion", but Kernel D forbids dropping any node for being below threshold / non-qualifying / 0%. | schema #17 vs Kernel D |
| C3 | **Literal % vs numeric field:** §9 says emit `"Not Available"` when % is missing (and §10/output uses `"Negligible"`), but `actual*Percentage` is `number` 0–100 → forces a fake `0`, which then collides with "0% is a real value, don't drop." | §9 vs schema #10/#11 |
| C4 | **Multi-path collapse:** Kernel D + §10 require preserving every path separately (a party in two branches; convergence), but one scalar `linkedName` per record holds a single parent. | Kernel D / §10 vs schema #4 |
| C5 | **JSON-only vs narrative:** Title "JSON ONLY" + `user.txt` "single JSON per schema", yet §18/§19 describe narrative/structured prose with no JSON home. | title/user.txt vs §18/§19 |
| C6 | **Entity kind lost:** `itemType` = INDIVIDUAL/ORGANIZATION only, but §9/§11 classify Government/Listed/Regulated/Trust/Partnership/Foundation. | schema #2 vs §9/§11 |
| C7 | **Role flattening:** `isUBO`/`isIBO` booleans cannot encode Notional UBO, Possible Notional UBO, or Control-Based IBO/UBO (§12, §11.1.1). | schema #8/#9 vs §12 |
| C8 | **Task mismatch (vocabulary):** `governanceBasis` ("GOVERNANCE ROLE", "SIGNING NARRATIVE"), `canonicalReasonTokens` (`signatoryType`), `transparencyCode` ("executive body override"), `temporalStatus` (current/former), `negativeSignals` ("former-only roles"), `controlsApplied` (governance rules) describe a **senior-manager / signatory governance** task, not ownership extraction. | schema #18–#28 vs whole prompt |
| C9 | **`linkedName` direction undocumented** — "Name of Linked/Parent Organization" does not say whether it is the entity this record owns or the entity that owns this record; §9.1 direction validation has no field to assert direction. | schema #4 vs §9.1 |
| C10 | **Unknown page not representable:** §18 allows `pageNumber=unknown`; schema requires integer ≥ 1. | §18 vs schema #5 |
| C11 | **Conflict values not storable:** §14 "retain all versions" (both values + sources); `conflictTag` records only that a conflict exists → audit data loss. | §14 vs schema #16 |

---

## 5. What IS in sync (the working skeleton)

- **Identity:** `id`, `nameAsSource`, `documentName`, `pageNumber` (modulo unknown-page), `itemType` (2 kinds).
- **Core percentages:** `actualOwnershipPercentage`, `actualVotingRightsPercentage` ≈ §9 direct % and voting % (modulo the "Not Available" literal).
- **Basic role flags:** `isUBO`, `isIBO`, `isUltimateParent` (partial — booleans only).
- **Conflict existence flag:** `conflictTag` ≈ §14 (existence only).
- **QA:** per-record `qaFlags[]` + top-level `qaFlags` {summary, records} ≈ §16.
- **Scope exclusion:** per-record `outOfBounds` + top-level `outOfBounds` {summary, documents, records} ≈ §5.
- **Coverage:** `coverageMode: ALL` consistent with Kernel D.

So a bare "who appears, in which doc, at what %, UBO/IBO?" extraction is representable. **Everything v39 added on top of that is not.**

---

## 6. Root cause & recommendation

**Root cause:** `schema.json` descends from the CSM "Classified Candidates" governance schema; the v39 rewrite happened only on the **prompt** side. The two now describe different tasks that happen to share id/name/%/UBO fields.

**Two coherent ways forward (pick one):**

1. **Build a v39-shaped ownership schema** (recommended). Add: `layer`; an owners/edges array (multi-path); `relationshipType`; `roleCapacity` enum; `entityType`; ownership/voting % as string-or-number to allow `"Not Available"`/`"Negligible"`; `sourceClass` (H1/H2/H3) + `sourceUse`; `ownershipApproach` (GLOBAL/STREAMLINED); listing-proof block; `dataGaps`; an outbound block (§15); `clientValidationStatus`; `ownershipReviewStatus`; cycle representation; the eight-part reasoning block; and a §19 summary object. Drop the CSM-only fields (`governanceBasis`→replace, `canonicalReasonTokens`, `transparencyCode`, `temporalStatus`, `countryProfileApplied`, `countryOverrideNote`, `scopeTag`, `currencyTag`, `negativeSignals`, `controlsApplied`, `confidenceScore` inclusion threshold, `accumulated*`).
2. **Keep the CSM schema and narrow the prompt** back to "identity + one parent + % + UBO/IBO" — abandoning v39's streamlined/outbound/roleCapacity/local-overlay/§19 ambitions.

Until one is chosen, the model is forced to **both** drop most v39 outputs **and** fabricate ~13 required governance fields — which violates the prompt's own Kernel C (evidence-only) and Kernel E (determinism).

_See also `monolith/REVIEW.md` (2026-07-27 section) for the higher-level version of this gap._
