# Business rules 7X / 9.2.3 / 9.0A — what was implemented, and what was not

**Source:** `ownership_new_requierement.txt` (business team, 2026-08-27)
**Applies to:** `monolith/` (extractor) and `monolith_critic/` (critic)
**Business response:** received 2026-08-28 (screenshot `IMG_4740.HEIC`) — see [§8](#8-round-2--business-response-of-2026-08-28)
**Status:** implemented, including the round-2 changes; `254 passed, 1 xfailed`; `insert_prompt.sql` regenerated for both agents

This document is a clause-by-clause trace. Every requirement in the business file appears
below exactly once, marked:

| Mark | Meaning |
|---|---|
| **IN** | Implemented as supplied |
| **IN+** | Implemented, plus a ruling the business text left open (the gap is named) |
| **IN\*** | Implemented in substance, but the supplied *wording* was changed — reason given |
| **OUT** | Not implemented — reason given |
| **HAD** | Already in the prompt before this change; nothing added |

A short summary of the exclusions is in [§4](#4-what-was-not-included-and-why); the
questions that need a business answer are in [§6](#6-open-questions-back-to-the-business-team) — each citing the
line number in the business file that raises it.

---

## 0. Where the three rules landed

The business supplied three standalone blocks (`<rule id="7X">`, `9.2.3`, `9.0A`). The repo's
extractor is a numbered rule set 1–18 plus named rules, with a `PROCESSING-ORDER` that maps
steps to rules. Rather than add parallel rules, each block was folded into the rule that
already owns its subject — because 7X in particular restates rules 7 and 8 in different
words, and two texts governing one question is the run-to-run variance this prompt keeps
having to fix.

| Business block | Landed as | Rationale |
|---|---|---|
| **7X** STEP 1 (adoption location / addenda precedence) | `rule 7` — two new bullets, one new overlay category | Rule 7 already *is* Local Requirement Selection, and already runs at `PROCESSING-ORDER` step 7, before methodology at step 8 |
| **7X** STEP 2 (KYC level → methodology) | new **`§8.0`** at the head of rule 8 | Rule 8 is the methodology-selection gate |
| **7X** GLOBAL methodology | `§8.1` — two new bullets | Where the Global Approach requirements already live |
| **7X** STREAMLINED layer 1 / layer 2+ | new **`§8.3.0`** | Sits directly above `§8.3.1`, the domination definition it qualifies |
| **9.2.3** | new **`§9.2.3`** (number preserved) | Joins the `§9.2` family: `§9.2` wordings, `§9.2.1` "WO", `§9.2.2` share-vs-voting |
| **9.0A** | new **`§9.0A`** (number preserved) | Sits between `§9.0`'s two passes, which is exactly when it must run |

`9.2.3` and `9.0A` kept their business numbers verbatim, so a reviewer can cite either
document and land in the same place. `7X` is the one id that did not survive — it had no
single home, because it legislates for two different existing rules.

---

## 1. Rule 7X — DBCLM KYC LEVEL, LOCAL ADDENDA AND OWNERSHIP METHODOLOGY GOVERNANCE

### 1.1 Preamble

| # | Business requirement | | Where it landed |
|---|---|---|---|
| 7X.1 | Methodology determined using BOTH DBCLM KYC Level AND Adoption Location / Local Addenda | **IN** | `§8.0` final paragraph + `rule 7` new bullet |
| 7X.2 | "Ownership methodology cannot be selected until both evaluations are completed" | **IN** | `§8.0` — *"THE GATE SELECTS A CANDIDATE; RULE 7 AND §8.2 SELECT THE PATHWAY EMITTED"* |

### 1.2 STEP 1 — Identify adoption location

| # | Business requirement | | Where it landed |
|---|---|---|---|
| 7X.3 | Identify all Adoption Locations and review all applicable Local Addenda before ownership extraction | **HAD** | `rule 7` opening line — unchanged |
| 7X.4 | Where multiple Adoption Locations exist, apply the strictest unless the Addendum says otherwise | **HAD** | `rule 7` bullet 3 — unchanged |
| 7X.5 | Addenda categories: ownership thresholds | **HAD** | `rule 7` category 2 |
| 7X.6 | …inclusive thresholds | **HAD** | `rule 7` category 1 |
| 7X.7 | …mandatory drill-down | **HAD** | `rule 7` category 3 |
| 7X.8 | …**exemption restrictions** | **IN** | `rule 7` **new category 7** |
| 7X.9 | …notional UBO requirements | **HAD** | `rule 7` category 4 |
| 7X.10 | …**ownership reporting requirements** | **IN** | `rule 7` category 5, reworded to name it explicitly |
| 7X.11 | …**special entity-type requirements** | **IN** | `rule 7` category 5, reworded to name it explicitly |
| 7X.12 | Local Addenda applied before ownership qualification, IBO, UBO, **stop-rule application**, drill-down decisions, classification | **IN** | `rule 7` new bullet — the six-item list reproduced as supplied |
| 7X.13 | Where a Local Addendum is stricter than Global KOS, the Local Addendum prevails | **IN** | `rule 7` new bullet (the principle existed; it is now stated in its own sentence) |

### 1.3 STEP 2 — Determine ownership approach *(this is the reported bug)*

| # | Business requirement | | Where it landed |
|---|---|---|---|
| 7X.14 | KYC Level = Global → `ownershipApproach` = GLOBAL | **IN** | `§8.0` mapping row 2 |
| 7X.15 | KYC Level = Streamline → STREAMLINED | **IN\*** | `§8.0` row 1 — selects a **STREAMLINED CANDIDATE**, then `§8.2`'s conditions decide. See [4.1](#41-streamline-selects-a-candidate-not-the-emitted-value) |
| 7X.16 | **Missing / Blank / Null / Unavailable → GLOBAL** | **IN+** | `§8.0` row 3 — widened to "ANYTHING ELSE", and given a normalization clause. See below |
| 7X.17 | "Never assume STREAMLINED unless DBCLM explicitly identifies KYC Level as Streamline" | **IN** | `§8.0` — *"NEVER SELECT STREAMLINED UNLESS THE INPUT EXPLICITLY IDENTIFIES THE KYC LEVEL AS STREAMLINE. Silence is GLOBAL."* |

**Why 7X.16 needed more than the supplied sentence.** `isStreamlined` is tested as a *boolean*
at twelve load-bearing sites — `§8.1`, `§8.2`, `§8.3`, `§10.1`'s applicability guard, `§10.2.0`,
`§12.3`, `§12.4`, rule 16's `STREAMLINED_NOT_SUPPORTED` mapping, `OC.1`'s two dilution fields,
and two `schema.json` descriptions. A blank value is neither TRUE nor FALSE, so **every one of
those guards was undefined**, including `§10.1`'s *"where `isStreamlined = TRUE`, the Dilution
Methodology must NOT be used"*. Writing "blank → GLOBAL" into `§8.0` alone would have fixed the
pathway label and left eleven other guards still undefined. So `§8.0` carries a **normalization
clause**: *"For ALL of them, a blank, missing, or unrecognised `isStreamlined` READS AS FALSE.
There is no third state anywhere in this prompt."*

Two additions the business text did not cover, both flagged as **IN+**:

- **The default does not discharge `INPUT-VALIDATION`.** A reader of 7X alone concludes there is
  no gap, because a default exists. `INPUT-VALIDATION` already lists "missing `isStreamlined`
  indicator" as a mandatory-input gap, and the critic scores an absorbed missing input as
  IMPORTANT. `§8.0` therefore states that the GLOBAL default settles the *methodology* only —
  the gap, `MISSING_MANDATORY_INPUT` and the advisory are still recorded.
- **`ownershipApproach` = `NEED_REVIEW` was narrowed.** `OC.2` defined it as "methodology cannot
  be determined", which a blank input reads onto directly — so after 7X a blank input had *two*
  defensible answers, which is the variance 7X exists to remove. `NEED_REVIEW` now means: input
  present and explicit, but irreconcilable with an applicable Local Addendum.

### 1.4 GLOBAL ownership methodology

| # | Business requirement | | Where it landed |
|---|---|---|---|
| 7X.18 | Apply Global Methodology, Local Addenda, Dilution, Domination, Voting Rights, Control | **IN** | `§8.1` new bullet *"GLOBAL IS NOT DILUTION-ONLY"* |
| 7X.19 | "Global methodology is NOT dilution-only" | **IN** | `§8.1` — heading kept verbatim |
| 7X.20 | Qualification must consider dilution / domination / voting / control / local overlays | **IN+** | `§8.1`, as the qualification **routes** — expressly *not* a redefinition of `qualifyingInterest`. See [4.5](#45-7xs-qualification-list-does-not-redefine-qualifyinginterest) |
| 7X.21 | Domination Assessment under rule 10.2 mandatory for **every** ownership relationship | **HAD** | `§10.2.0` already says this for both pathways; `§8.1` now cites it |
| 7X.22 | Ownership > 50% → `dominationIndicator = YES`, D1 | **IN\*** | `§10.2.0` rung D1 — cited, not restated. Token casing normalised |
| 7X.23 | Voting rights > 50% → YES, D2 | **IN\*** | `§10.2.0` rung D2 — same |
| 7X.24 | Control by other means → YES, **D3 Control Basis** | **IN\*** | `§10.2.0` rung D3. **The literal token `D3 Control Basis` was rejected** — see [4.2](#42-dominationbasis--d3-control-basis-was-not-adopted) |
| 7X.25 | A party may dominate even where its final diluted interest falls below an IBO/UBO threshold | **IN** | `§8.1` new bullet, cross-referencing `§12.2.1` criteria 3/4 and `§12.4` |

### 1.5 STREAMLINED ownership methodology

| # | Business requirement | | Where it landed |
|---|---|---|---|
| 7X.26 | Layer 1 owners retain their directly evidenced ownership position | **IN** | `§8.3.0` |
| 7X.27 | Layer 1 `QualifyingInterest` = `actualOwnershipPercentage` | **HAD** + restated | `§12.4` already said this; `§8.3.0` now states it where the layer split is defined |
| 7X.28 | Layer 1 thresholds applied against the direct position | **HAD** + restated | `§12.4`, `§8.3.0` |
| 7X.29 | Layer 1: do not apply domination inheritance | **IN+** | `§8.3.0` — plus the clarification that the **assessment** still runs. See [4.6](#46-layer-1-suspends-inheritance-not-the-assessment) |
| 7X.30 | Layer 2+: apply Domination Inheritance Methodology only | **IN** | `§8.3.0` |
| 7X.31 | Layer 2+: no dilution, no multiplication, no cumulative indirect | **HAD** + restated | `§8.3` Prohibitions; restated in `§8.3.0` |
| 7X.32 | A dominating owner (ownership >50% / voting >50% / control) inherits the full position | **HAD** | `§8.3.1` — unchanged |
| 7X.33 | Worked example: ABC 30% of Client, JKL 60% of ABC → JKL `QualifyingInterest` = 30% | **IN** | `§8.3.0` worked example, written in the `nameAsSource → linkedName` direction contract |
| 7X.34 | "The result must never be diluted to 18%" | **IN** | `§8.3.0` — *"It is NEVER diluted to 18% (30 x 60)"* |

---

## 2. Rule 9.2.3 — FORM ADV OWNERSHIP CODE CONVERSION

| # | Business requirement | | Where it landed |
|---|---|---|---|
| ADV.1 | Applies wherever percentages are sourced from SEC Form ADV ownership disclosures | **IN** | `§9.2.3` APPLICABILITY, extended to name Schedule A / Schedule B |
| ADV.2 | Base table: NA 4.99 / A 6.00 / B 11.00 / C 25.01 / D 50.01 / E 75.01 | **SUPERSEDED → IN** | Replaced 2026-08-28 by the RANGED table (code + disclosure range + percentage). Reproduced in `§9.2.3` and critic **R9** |
| ADV.3 | All eight special allocation rules | **WITHDRAWN** | Removed by the business team 2026-08-28. Absence is now pinned by `test_form_adv_special_allocations_are_gone` |
| ADV.4 | ADV percentages are deemed ownership evidence for extraction, qualification, IBO, UBO, domination, inheritance | **IN** | `§9.2.3` *"THE BANDED FIGURE IS THE QUANTITY EVERY DOWNSTREAM RULE USES"* |
| ADV.5 | Do not raise `MISSING_PERCENTAGE` / `SOURCE_DATA_NOT_AVAILABLE` / percentage advisory solely because the exact figure is undisclosed | **IN\*** | `§9.2.3` *"DO NOT LOG A GAP THAT DOES NOT EXIST"* — **scoped to the ownership figure only**. See [4.7](#47-adv-suppression-is-scoped-to-the-ownership-figure) |
| ADV.6 | A more precise percentage in another admissible document prevails | **IN** | `§9.2.3` *"AN EXACT PERCENTAGE PREVAILS OVER A CODE"* |
| ADV.7 | `governanceBasis` must identify the ownership code relied upon | **IN+** | New rule 18 part [1] token **`OwnershipCodeApplied=[...]`**. Rule 18's form is closed ("emit no text outside the eight labelled parts"), so a free-text note would have had no legal home and would have varied every run |

### Rulings the business text left open (all **IN+**)

| Gap | Ruling |
|---|---|
| **Combinations not in the special list** (two E holders, three C holders, C+D, E+D…) | Each holder takes its **base table** value; no interpolation, no reasoning by analogy, no rescaling. A set over 100% emits `OWNERSHIP_PERCENTAGE_CONFLICT` + advisory. *Confirmed with you before implementation.* Without a stated fallback this is an open choice, i.e. run-to-run variance |
| **Allocation ordering** | Allocation is a **per-target SET operation**, run after the target's holder set is closed. The value of a code depends on who else holds one — the same `C` is 25.01, 25.00 or 49.99 — so assigning code-by-code down the page gives a reading-order-dependent answer |
| **Code resolution** | `§9.2.3` carries `§9.2.1`'s **RESOLVE-DON'T-PATTERN-MATCH** discipline. Single letters A–E collide with list markers, schedule letters, section labels and initials. Unresolved → fail closed to 0 + `MISSING_PERCENTAGE` + the gap + advisory |
| **Voting rights** | An ADV *ownership* code evidences ownership only (`§9.2.2`). Never copy the band into `actualVotingRightsPercentage`, never read it as a D2 basis |
| **`§9.3` per-target sum** | A Form ADV table is a threshold-bounded disclosure and satisfies `§9.3`'s **existing** `explicitly-partial-source` exemption **by rule**. Without this a single E holder totals 75.01 and raises a false `INCOMPLETE_SHAREHOLDER_SET` on every ADV case. Deliberately *not* a third exemption token — that would have forced edits to `§12.4`'s "either", the critic's "which of the **two** categories", and a pinned test |
| **Domination boundary** | The ladder runs on the **allocated** figure, never the letter. Base D = 50.01 takes D1; the "two D holders" allocation makes each 50.00, which is *not* a majority, so both take D4 |
| **Threshold boundary** | Base C = 25.01 clears a strict `greater than 25%` IBO test; the 25.00 produced by "E + C" and "four C" allocations fails it but passes an inclusive `25% or more` local threshold |

---

## 3. Rule 9.0A — COLLECTIVE OWNER, PASSIVE INVESTOR AND LEGEND RESOLUTION

| # | Business requirement | | Where it landed |
|---|---|---|---|
| CO.1 | Before creating any relationship, determine whether a node is (A) an entity/person or (B) a collective label | **IN** | `§9.0A` opening |
| CO.2 | All 17 example collective labels | **IN** | `§9.0A` A) — all 17 reproduced, marked "illustrative, not a closed list" |
| CO.3 | These labels must not automatically be treated as legal entities | **IN** | `§9.0A` A) |
| CO.4 | Mandatory resolution sweep across all 12 named sources | **IN+** | `§9.0A` MANDATORY RESOLUTION SWEEP — all 12, scoped by `KERNEL A` (only material supplied as a case document) |
| CO.5 | If resolved: extract underlying parties, replace the collective label, preserve evidence | **IN+** | `§9.0A` RESOLVED — plus the ruling that a resolved label is **not a party** and is not inventoried. See [4.8](#48-a-resolved-label-needed-an-express-inventory-exception) |
| CO.6 | If not resolved: preserve the label, `roleCapacity = Information Only`, do not infer | **IN** | `§9.0A` UNRESOLVED |
| CO.7 | If not resolved: raise `PARTY_IDENTITY_UNRESOLVED` (business lines 216, 283) | **OUT** | Replaced by `COLLECTIVE_OWNER_UNRESOLVED`. See [4.3](#43-party_identity_unresolved-was-not-used-for-90a) and [6.8](#68-three-different-qa-flags-are-named-for-one-condition) |
| CO.8 | Legend resolution for symbols and markers | **IN\*** | `§9.0A` B) — two glyphs written as words. See [4.4](#44-two-symbol-glyphs-were-written-as-words) |
| CO.9 | Review 6 named sources for a legend explanation | **IN** | `§9.0A` B) |
| CO.10 | Apply the explanation to all affected relationships; record in `governanceBasis`; use the interpreted relationship | **IN** | `§9.0A` B) |
| CO.11 | "Never ignore a legend merely because the explanation appears outside the organisation chart" | **IN** | `§9.0A` B) — near-verbatim |
| CO.12 | Legend precedence: legends, footnotes, notes, client emails are ownership evidence | **IN** | `§9.0A` LEGEND PRECEDENCE |
| CO.13 | Worked example (Passive Investors → five funds) | **IN\*** | `§9.0A` — **rewritten**. See [4.9](#49-the-90a-worked-example-was-rewritten) |
| CO.14 | Prohibition: assume a collective label is a single legal entity | **IN** | `§9.0A` A) |
| CO.15 | Prohibition: create ownership links based solely on collective labels | **IN** | `§9.0A` A) |
| CO.16 | Prohibition: ignore ownership legends | **IN** | `§9.0A` B) |
| CO.17 | Prohibition: ignore clarification contained in client emails | **IN** | `§9.0A` sweep + LEGEND PRECEDENCE |
| CO.18 | Prohibition: infer underlying parties without documentary support | **IN** | `§9.0A` UNRESOLVED — *"do NOT infer, name, guess, or apportion"* |
| CO.19 | Unresolved → `ownershipReviewStatus = NEED_REVIEW` | **IN+** | `§9.0A` — plus the ruling that this **outranks** `§11.2`'s mandatory `COMPLETE` |
| CO.20 | Unresolved → `COLLECTIVE_OWNER_UNRESOLVED` or `LEGEND_INTERPRETATION_MISSING` | **IN** | Both added to rule 16's vocabulary **and** its binding selection map, and to critic **R6** |
| CO.21 | The 9 example QA-failure conditions (business lines 302-312) | **OUT** | See [4.10](#410-the-nine-example-qa-failure-conditions-were-not-reproduced) and [6.14](#614-the-nine-qa-failure-examples-were-not-reproduced) |
| CO.22 | Must not assume identity of investors / funds / partnerships, percentages, or distribution | **IN** | `§9.0A` UNRESOLVED + PERCENTAGES ARE READ AS PRINTED |
| CO.23 | Unresolved collective ownership is an Ownership Completeness Failure, disclosed in `governanceBasis`, QA Flags, `ownershipReviewStatus`, final summary | **IN** | `§9.0A` D) NO SILENT PASS — all four places named |
| CO.24 | Never silently ignore unresolved groups, legends, symbols or references | **IN** | `§9.0A` D) |

### Rulings the business text left open (all **IN+**)

| Gap | Ruling |
|---|---|
| **Are resolved percentages absolute or relative?** | **Absolute, as printed.** `KERNEL C` bars computing a percentage no document states. Where the resolved parties do not reconcile to the block, both facts stand: emit the parties at their stated figures, flag `OWNERSHIP_PERCENTAGE_CONFLICT` + `COLLECTIVE_OWNER_UNRESOLVED`, advise. Never rescale, never derive a residual holder. *Confirmed with you before implementation* |
| **Partial resolution** (three of five funds evidenced) | The business text is binary; the real case is not. Emit the named parties, retain the label for the remainder at `actualOwnershipPercentage = 0` + `MISSING_PERCENTAGE` (writing the difference would be the computation the rule forbids) |
| **Ladder rung** | Rung 2 fires on *"party identity is unresolved"* and **precedes** rung 3, so first-match-wins would have decided every collective label by which sentence was read first. Ruling: rung 2 is for an unresolved question **between candidates**; a collective label takes **rung 3**. Pinned in both rungs, in critic R2, and in the schema |
| **Does the retained label keep its percentage?** | **Yes.** Rule 6's attribution-control text bars deriving a percentage *from* a label — different from a figure printed on the chart for that link. Dropping it would break `§9.3`'s sum for the target *and* collapse `§15.1`'s headroom to zero, making the branch IMMATERIAL and suppressing the very advisory the rule exists to force |
| **`itemType` / `relationshipType`** | Both are required non-nullable enums, so the record asserts something whatever you do. Pinned: `itemType = "Non Natural Person"` is a container type carrying no assertion of legal personality; `relationshipType = "other"` |
| **Stop-rule interaction** | `§9.0A` does not reach a party beyond an evidenced, recorded, permitted stop |
| **`§15.1` headroom** | An unresolved label / legend is **MATERIAL by rule**, whatever the headroom |

---

## 4. What was NOT included, and why

### 4.1 "Streamline" selects a *candidate*, not the emitted value

7X STEP 2 reads as a mechanical two-line mapping straight to `ownershipApproach`. Implemented
instead as: the KYC Level selects the **candidate** pathway, and `§8.2`'s existing conditions
decide the pathway actually emitted.

**Why.** `§8.2` makes STREAMLINED conditional on four things — the input, *plus* KOS permitting
it, *plus* no local drill-down overlay, *plus* no document conflict — and rule 16's
`STREAMLINED_NOT_SUPPORTED` flag draws its **only** trigger from exactly that gate. A mechanical
mapping orphans the flag. It also makes 7X self-contradictory: STEP 1 says Local Addenda apply
before "stop-rule application" and "drill-down decisions" and that "the Local Addendum prevails"
— which is `§13.3`'s mandatory-drill-down list (Austria, Netherlands, Luxembourg, …) forcing
GLOBAL — while STEP 2 would send Streamline → STREAMLINED unconditionally. **Needs business
confirmation** ([6.3](#6-open-questions-back-to-the-business-team)).

### 4.2 `DominationBasis = D3 Control Basis` was not adopted

**Not implemented as written.** The rule behind it is implemented; the literal token is not.

**Why.** `§10.2`'s closed list of control bases has eleven entries and **"control by other means"
is not one of them**. `§10.2.0` D3 requires the basis to be *"named verbatim from the list"*, and
the critic scores a basis outside that list as IMPORTANT. Emitting `D3 Control Basis` would have
made every correct application of 7X a finding. The same applies to the title casing in
`D1 Majority Share Ownership` / `D2 Majority Voting Rights`: rule 18 and critic R3 pin the
lowercase forms `[D1 majority share ownership]` / `[D2 majority voting rights]`.

7X's three domination lines were therefore **cited rather than restated**. There is precedent:
CLAUDE.md records that `§12.4`'s restatement of the same closed list silently dropped
`trustee / protector control`.

### 4.3 `PARTY_IDENTITY_UNRESOLVED` was not used for 9.0A

**Not implemented.** `COLLECTIVE_OWNER_UNRESOLVED` and `LEGEND_INTERPRETATION_MISSING` are used
instead.

**Why.** The business text names **three different flags for one condition**: its MANDATORY
RESOLUTION section says `PARTY_IDENTITY_UNRESOLVED`, its MANDATORY QA FAILURE CONDITIONS section
says `COLLECTIVE_OWNER_UNRESOLVED` or `LEGEND_INTERPRETATION_MISSING`, and its EXAMPLE says
`PARTY_IDENTITY_UNRESOLVED` again. They cannot all be right.

`PARTY_IDENTITY_UNRESOLVED` already has a **binding, single-condition trigger** in rule 16 —
`§9.0` pass 1's *two near-identical party labels* — and it carries a consequence 9.0A
contradicts: ladder rung 2 with `confidenceStatus = NEED_REVIEW`, where 9.0A requires rung 3
"Information Only". Reusing it would give one flag two incompatible downstream meanings and
defeat rule 16's own premise that the mappings are binding and the most specific category wins.
The two new flags were added with their own triggers.

### 4.4 Two symbol glyphs were written as words

The legend example list `*, **, #, ^, †, ‡, [1], (A)` is implemented as *"a dagger †, a double
dagger ‡, a hash #, a bracketed numeral such as [1], a bracketed letter such as (A), an asterisk,
a double asterisk, or a caret"*.

**Why.** `tools/gen_insert_sql.py` reserves `^` as the sentinel for `&` and `*` as the sentinel
for `:` — the encoding that gets the prompts past the Java `PreparedStatement` and Oracle's
4000-byte literal cap. Generation **failed loudly** on the literal glyphs, exactly as designed.
The meaning is unchanged and the list is expressly open-ended. The `§9.0A` worked example's
marker was changed from `*` to `†` for the same reason.

### 4.5 7X's qualification list does not redefine `qualifyingInterest`

7X's *"Ownership qualification must consider: dilution ownership assessment, domination ownership
assessment, voting-rights assessment, control assessment, Local ownership overlays"* is
implemented as the **qualification routes**, with an express statement that it does not redefine
the tested quantity.

**Why.** `§12.4` defines `qualifyingInterest` as a **single** recomputable quantity — the
cumulative diluted product on GLOBAL — and deliberately places control, domination and voting
*outside* that comparison (*"they sit outside this threshold-comparison contract"*). Read as a
redefinition, `QualifyingInterest` becomes a blend with no arithmetic trace, `DilutionChain`
stops being recomputable, and the critic's Percentage Accuracy criterion — *"a trace whose
operands do not produce the asserted result is CRITICAL"* — starts firing on correct output.

### 4.6 Layer 1 suspends inheritance, not the assessment

7X's Layer-1 bullet *"do not apply domination inheritance"* is implemented with an added
sentence: `§10.2.0`'s ladder still runs, and `dominationIndicator` still carries YES or NO.

**Why.** `§10.2.0` is *"MANDATORY, EVERY RECORD, BOTH PATHWAYS"*. Read without the clarification,
"do not apply domination" at layer 1 licenses a null `dominationIndicator`, which the critic
scores as a skipped assessment (IMPORTANT). The business sentence is about what an owner
**inherits**, not about whether the determination was made.

### 4.7 ADV suppression is scoped to the ownership figure

ADV.5 says do not raise `MISSING_PERCENTAGE` / `SOURCE_DATA_NOT_AVAILABLE` / advisory. Implemented
**for the ownership figure only**.

**Why.** `OC.3` requires a `SOURCE_DATA_NOT_AVAILABLE` entry for a missing **voting** figure too,
and `§10.1.1` requires `DILUTED_VOTING_INCOMPLETE`. An ADV *ownership* code says nothing about
votes (`§9.2.2`), so an unscoped suppression would silently delete the voting gap on every ADV
case. The clause states the scope explicitly.

### 4.8 A resolved label needed an express inventory exception

CO.5's *"replace the collective label with the resolved ownership parties"* is implemented with an
added ruling: a resolved label is **not a party**, is not inventoried under `§9.0` pass 1, and is
emitted in no container.

**Why.** `§9.0` pass 1 must inventory every party *named as an owner*, and the chart does name the
label. `§9.0` pass 2 then routes every inventoried party through a **closed two-container rule**
that says in terms *"the two routes are NOT interchangeable… admitting a choice of container here
is itself a source of run-to-run variance"*. "Replace" is a third outcome that routing forbids, so
it needed to be written as an express exception — alongside the EVIDENCED STOP RULE, the only
other one.

This also drove the single largest critic change. Without a matching guard, the critic's
independent document sweep finds "Passive Investors" printed on the chart as an owner, the holder
roster has no line for it, the near-identical-name check does not reach it ("Passive Investors"
and "Fund A" share no stem), and the NAME-IT-AND-QUOTE-IT anti-fabrication requirement is
**satisfied** — the chart really does print it. A fully-evidenced, completely wrong CRITICAL,
answerable only by re-emitting the label the rule just removed. A **COLLECTIVE-LABEL GUARD** now
runs second in Entity Completeness, right after the SCOPE GUARD.

### 4.9 The 9.0A worked example was rewritten

The supplied example was not pasted in. Two defects would have propagated:

1. `ABC Holdings -> 100% Passive Investors` is an **inverted edge** under `FIELD-VOCABULARY A`'s
   positional direction contract, which reads it as "ABC Holdings owns Passive Investors" — the
   reverse of the intent. Every other example in `system.txt` is written `nameAsSource → linkedName`.
2. The five funds total **10+8+6+4+2 = 30%** against a block printed at **100%**. Either way the
   percentages are read, that puts the target's `§9.3` sum at 30 → below 95% → a **false
   `INCOMPLETE_SHAREHOLDER_SET` on the canonical worked example of the new rule**.

Rewritten in direction-contract form with a 30% block that the five funds reconcile to, plus two
further examples (partially resolved, unresolved) that the supplied text did not cover.
**Needs business confirmation** ([6.9](#69-the-worked-example-carries-an-inverted-edge-and-a-shareholder-sum-that-fails-93)).

### 4.10 The nine example QA-failure conditions were not reproduced

CO.21's list ("Passive Investors identified but underlying investors cannot be determined",
"Other Shareholders identified but…", and seven more) is **not** reproduced.

**Why.** All nine are the same condition — a collective label or marker that cannot be resolved —
restated against nine different labels, and that condition is already the operative rule in
`§9.0A`. The 17 labels themselves *are* reproduced. Nine near-identical restatements add prompt
length without adding a case the rule does not already reach, and rule 16's category list already
binds the flag names. If the business wants them as an explicit checklist, they are cheap to add.

### 4.11 `<rule id="7X">` as a standalone rule

**Not implemented** as a rule block; folded into rules 7 and 8 (see [§0](#0-where-the-three-rules-landed)).
`9.2.3` and `9.0A` kept their business numbers. *Confirmed with you before implementation.*

### 4.12 The `OwnershipCodeApplied` token keeps rule 18's brackets

The business specified the values `OwnershipCodeApplied=Code:E` / `Code:D` / `N/A`. Implemented as
`OwnershipCodeApplied=[Code:E]` … `OwnershipCodeApplied=[N/A]` — their vocabulary exactly, inside the
brackets every other token on that line uses.

**Why.** Rule 18's part [1] is a closed grammar in which all nineteen tokens are written `Token=[value]`,
and rule 18 says *"spell each label exactly as specified"*. One token without brackets on that line is
the kind of inconsistency a model silently "corrects" — in either direction, differently between runs.
The value vocabulary is untouched. `N/A` is also deliberately **not** normalised to the `n-a` its four
neighbouring trace tokens use, because the business named that form explicitly.

---

## 5. Changes made that the business did not ask for

These were required to make the three rules work inside the existing contract. None changes what
the business asked for; each closes a hole the new rules would otherwise open.

| Change | Why it was necessary |
|---|---|
| **Critic R9 / R10 / R11** reference blocks | The critic **never sees** `monolith/system.txt`. A rule it is asked to apply but cannot see is a check nobody can perform — and worse, it scores correct output as a defect. R9 carries the full ADV table *including the special allocations*: given only the base table, the critic reads `dominationIndicator = NO` on a 50.00 and scores a missed D1 against a correct extraction |
| **Critic: unauthorised STREAMLINED is CRITICAL**, and the stop-rule guard gated on the declaration | Entity Completeness' STOP-RULE GUARD limb (iii) certified every party above a 50%-or-less owner as correctly absent under STREAMLINED. It verified the **stop**, never the **declaration** — so the reported bug survived review as a blessed absence |
| **Critic: ADV / collective-label / Information Only carve-outs** | Without them, Percentage Accuracy scores every ADV figure as invented (CRITICAL), and criterion 6 scores the required `Information Only` record as a fabricated owner (CRITICAL) **by name** |
| `§9.2.1`'s closing clause amended | It read *"no other two-letter database code is interpreted by inference… any other code is NOT ownership evidence on its own"* — the exact opposite of `§9.2.3` for the same characters on the same page |
| Rule 9 / `OC.1` "exactly as stated" carve-outs | Three statements of the same contract, one of them in `schema.json`, contradicted every ADV record |
| `pageNumber = 1` for unpaginated sources | `§9.0A` makes email-, legend- and comment-sourced evidence routine; `pageNumber` is a required integer with no null and no "unknown" form |
| `thresholdApplied` null for ladder rungs 2 and 3 | Its closed grammar permitted null only for the client and for a stop-rule halt, forcing a threshold string onto records never threshold-tested. Pre-existing hole that `§9.0A` makes routine |
| Rule 6: `SEC Form ADV` → H1, `maker / checker comment` → H3 | `sourceClass` is a required enum and the critic scores a wrong class IMPORTANT; an unclassifiable source type is a guaranteed finding |
| `§15.1` "MATERIAL by rule" carve-out | Otherwise the headroom test suppresses the advisory `§9.0A` exists to force, and the critic is *positively forbidden* from demanding it |
| Three round-trip carve-outs | Found by writing the correct output by hand and reading every criterion against it: a short ADV target sum, `isIBO` on a 30% `Information Only` record, and `pageNumber = 1` reading as a fabricated citation |
| ~16 new tests; count pins 44→46 and 41→43 | The flag vocabulary and reference-block counts are pinned in `tests/test_monolith_prompts.py` |

---

## 6. The 15 questions, and how the business answered them

All 15 were answered on 2026-08-28. Line references are into `ownership_new_requierement.txt`.

| # | Question (business line ref) | Answer | Effect on the build |
|---|---|---|---|
| 6.1 | STEP 1 vs STEP 2 when an Addendum mandates drill-down (20–26, 29 vs 33–34) | **Proceed — "local addendum always overlap global"**, plus two sentences to add | Our ruling confirmed. The supplied text is now in `§8.0`, including *"must be reflected in the final `ownershipApproach`"* |
| 6.2 | What triggers `MIXED` / `NEED_REVIEW` (31–37) | **Proceed — for blank KYC, GLOBAL is added; NEED_REVIEW not required** | Confirms the narrowing already implemented. No change |
| 6.3 | Why single D = 74.99 against a base of 50.01 (119/120 vs 126/129) | **Replaced the table** with code + RANGE + percentage | `§9.2.3` and critic R9 rebuilt on the ranged table |
| 6.4 | "Two D holders = 50.00% each" switches domination off (128) | **Remove — Special Allocation Rules** | Moot. D is now always 50.01 → always D1 |
| 6.5 | Combinations not covered by the eight cases (123–131) | **Remove — Special Allocation Rules** | Moot. One code, one percentage, no combination cases |
| 6.6 | Does the suppression reach the VOTING gap? (141–145) | **left blank** | See [§6a](#6a-the-one-question-that-came-back-unanswered) |
| 6.7 | Where the code goes in `governanceBasis` (149) | **`OwnershipCodeApplied=Code:E` / `Code:D` / `N/A`** | Token adopted in their vocabulary; see [4.12](#412-the-ownershipcodeapplied-token-keeps-rule-18s-brackets) |
| 6.8 | Three flags for one condition (216, 283 vs 318–320) | **"Remove `PARTY_IDENTITY_UNRESOLVED` from the new collective-owner rule"** | Confirms what was implemented. No change |
| 6.9 | Worked example: inverted edge, funds ≠ block (257, 264–268) | **Corrected example supplied**: `ABC Holdings is owned 100% by Passive Investors`, funds 10/20/30/20/20 | `§9.0A`'s examples rebuilt on their figures, which total 100 and reconcile |
| 6.10 | Resolved percentages absolute or relative? (262–268) | **"Multiply only if Owners are same, if owners are different, do not multiply"** | The *do not multiply* limb implemented. The other limb is not — see [§6a](#6a-the-one-question-that-came-back-unanswered) |
| 6.11 | Does "replace" mean removed entirely? (209) | **"removal is intended"** | Confirms what was implemented. No change |
| 6.12 | Partial resolution (207–216) | **"confirm"** | Confirms what was implemented, with one correction we made ourselves — see [§8](#8-round-2--business-response-of-2026-08-28) |
| 6.13 | Maker / checker comments admissible? (203–204) | **"remove maker checker comment section"** | Withdrawn from rule 6's H3 list and from `§9.0A`'s sweep, critic R11, and the unpaginated-source rule |
| 6.14 | The nine QA-failure examples (302–312) | **"confirmed"** | They stay out |
| 6.15 | Does 9.0A fire above a stop rule? (not in the file) | **"confirm"** | Both rulings stand |

### 6a. The one question that came back unanswered, and one that came back half-answered

**6.6 — the voting gap.** The line reads `6.6 -` with nothing after it. The clause at business lines
141–145 says do not raise `MISSING_PERCENTAGE` / `SOURCE_DATA_NOT_AVAILABLE` / advisory for an
undisclosed exact figure; both of those artefacts are ALSO used for the **voting** figure, and an ADV
*ownership* code says nothing about votes. Implemented scoped to the **ownership figure only**, so
`MISSING_VOTING_RIGHTS`, its gap entry and `DILUTED_VOTING_INCOMPLETE` all still fire. The third bullet
of the business clause says "ownership percentage advisory requests", which supports that reading.
**Still worth a yes/no.**

**6.10 — "Multiply only if Owners are same."** The second limb is implemented: where the resolved
parties are different parties — which is what makes a label collective — each takes the figure its
source states, unmultiplied. The **same-owner** limb has no worked example and no reading I can turn
into a deterministic rule, so it was deliberately NOT written into the prompt: a rule that cannot be
applied identically twice is worse than no rule, and it would reintroduce exactly the run-to-run
variance this prompt keeps having to remove. It costs nothing today, because a collective label
resolves to multiple parties by definition. **Needs one worked example before it can be added.**

## 7. Files changed

| File | Change |
|---|---|
| `monolith/system.txt` | `§8.0`, `§8.1`, `§8.3.0`, `§9.0A`, `§9.2.3` new; rules 6, 7, 9, 12.0, 15, 15.1, 16, 18, `INPUT-VALIDATION`, `OUTPUT-CONTRACT` amended |
| `monolith/schema.json` | 9 field descriptions; **no new or removed fields** |
| `monolith_critic/system.txt` | R2 / R6 amended; R9 / R10 / R11 added; 9 criteria amended; `verdict_rules` amended |
| `monolith_critic/user.txt` | 5 instruction blocks mirrored |
| `tests/test_monolith_prompts.py` | 2 count pins, 4 list pins, ~27 new tests including the round-2 regressions **(note: `tests/` is gitignored — `git add -f`)** |
| `CLAUDE.md` | Design history + open questions |
| `monolith/insert_prompt.sql`, `monolith_critic/insert_prompt.sql` | Regenerated via `python3 tools/gen_insert_sql.py` — never hand-edit |

**Verification:** `venv/bin/pytest tests/ -v` → `254 passed, 1 xfailed` (the xfail is the
pre-existing stale `monolith/example.json`). Both SQL files: one `INSERT INTO`, no semicolons,
no comments, no `& ; : ?` surviving.

---

## 8. Round 2 — business response of 2026-08-28

Received as a screenshot (`IMG_4740.HEIC`). Six answers changed the build; the rest confirmed what was
already there. Everything below is implemented and covered by tests.

### 8.1 The Form ADV table was replaced, and the special allocations withdrawn

The base table is now a **ranged** table, and the eight special allocation rules are **gone**:

| Code | Range | Percentage emitted |
|---|---|---|
| NA | Less Than 5% | 4.99 |
| A | 5% But Less Than 10% | 6.00 |
| B | 10% But Less Than 25% | 11.00 |
| C | 25% But Less Than 50% | 25.01 |
| D | 50% But Less Than 75% | 50.01 |
| E | 75% or More | 75.01 |

This is a **simplification with real consequences**, all of them good. The allocations were the reason
a code's value depended on who else held one (the same `C` was 25.01, 25.00 or 49.99), which forced
three pieces of machinery that are now deleted:

- the **per-target SET operation** clause (allocation had to wait until the holder set was closed, and
  was otherwise reading-order dependent);
- the **fallback** for combinations the eight cases did not cover;
- the critic's **double-scoring exception** in `verdict_rules` (one missed holder used to change every
  other holder's percentage, so a single omission was simultaneously a missing entity and N wrong
  percentages).

It also removes the domination flip: a `D` is now 50.01 on every run, so §10.2.0 D1 always fires.

Two things had to be **added** to make the ranged table safe:

- **The range is not the figure.** D's range starts *at* 50%, so a model reading the range instead of
  the percentage could argue a D holder might hold exactly 50% and therefore not dominate. Both prompts
  now state that the percentage column is the only value produced, and that 50.01 is what the ladder tests.
- **A banded set does not total 100.** Two D holders make 100.02, four C holders 100.04, a single E
  holder 75.01. That is an artefact of deemed values standing for ranges, not double-counting — the
  target is exempt from §9.3 under `explicitly-partial-source`, `TargetSum` reads "exempt", and
  `OWNERSHIP_PERCENTAGE_CONFLICT` is never raised for it. Without this the new table would fire a false
  conflict flag on the commonest ADV shape, and the critic would demand one.

`test_form_adv_special_allocations_are_gone` pins all eight cases, plus `74.99` and `49.99`, as
**absent** from all three prompt files — so they cannot be reintroduced from the superseded instruction.

### 8.2 Maker / checker comments withdrawn

Removed from rule 6's H3 list, from §9.0A's resolution sweep, from critic R11, from the
COLLECTIVE-LABEL GUARD, and from the unpaginated-source example in OC.1.

One deliberate non-removal: `KERNEL F` says the output must be *"suitable for audit, maker, checker and
QA review"*. That is a sentence about **who reviews the output**, not about evidence, and it predates
this change — it stays, and a test pins it so a future sweep does not delete it by keyword match.

### 8.3 The §9.0A example now uses the business's figures

`ABC Holdings Ltd. is owned 100% by Passive Investors †`, resolving to Fund A 10%, Fund B 20%,
Fund C 30%, Fund D 20%, Fund E 20% — which total 100 and reconcile to the block. Both defects in the
original example are gone, and the direction reads correctly under the `nameAsSource → linkedName`
contract.

### 8.4 Resolved percentages are not multiplied

Per *"if owners are different, do not multiply"*: each resolved party takes the figure its source states
as its percentage **in the target**, unmultiplied by the block's percentage.

### 8.5 One correction we made ourselves while implementing 8.3/8.4

The non-reconciling case originally flagged `OWNERSHIP_PERCENTAGE_CONFLICT` in **both** directions. That
is wrong for a **shortfall**: where the resolved parties total *less* than the block (the partial
resolution case), parties are missing rather than contradictory, and §9.3 already owns that condition
with `INCOMPLETE_SHAREHOLDER_SET`. Using the conflict flag there would report double-counting where the
real defect is an incomplete set, and would put §9.0A at odds with §9.3 on the same target.

The flag now follows the **direction** of the discrepancy — resolved total above the block →
`OWNERSHIP_PERCENTAGE_CONFLICT`; below → `INCOMPLETE_SHAREHOLDER_SET` — with
`COLLECTIVE_OWNER_UNRESOLVED` and an advisory either way. Mirrored in critic R11.

### 8.6 What is still open

Two items, neither blocking — both in [§6a](#6a-the-one-question-that-came-back-unanswered):

1. **6.6** came back blank: does the ADV gap-suppression reach the **voting** gap? Implemented
   ownership-only, which is the safe reading and matches the clause's own third bullet.
2. **6.10's "multiply only if Owners are same"** limb has no worked example and was not written into
   the prompt. The operative limb ("if owners are different, do not multiply") is implemented.

One consequence worth a confirmation rather than a question: **a `D` code now always evidences
domination**, because 50.01 is always more than 50%, even though the code's own range admits a holding
of exactly 50%. That follows from withdrawing the allocations rather than from a stated decision.
