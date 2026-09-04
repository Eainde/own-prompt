# "Contractual partnership" in rule 9.0A — questions for the business owner

**Date raised:** 2026-09-04
**Raised by:** engineering (KYC ownership extraction prompt)
**Needed from:** KOS text owner / business rule owner
**How to answer:** write under each `**Answer:**` line. Anything left blank stays unimplemented.

---

## 1. What was asked

To add **`Contractual partnership`** to the list of examples in rule **9.0A** (collective
ownership labels), between `Other Funds` and `Limited Partners`:

> Examples - illustrative, not a closed list: Passive Investors; Other Shareholders; Other Owners;
> Investor Group; Group Of Investors; Institutional Investors; External Investors; Co-Investors;
> Various Funds; Group Of Funds; Other Funds; **Contractual partnership**; Limited Partners;
> Other LPs; Remaining Shareholders; Minority Investors; Passive Investor Group;
> Investor Consortium.

The intent, as we understand it: a contractual partnership is **not a legal structure in its own
right — it is a pool of entities**, so it must not be recorded as a single owner.

**Q0. Is that the intent?** (If the intent is something narrower — e.g. only "flag it for a
caseworker" — say so here and most of the questions below fall away.)

**Answer:**

---

## 2. What rule 9.0A does today — please read before answering

9.0A is **not** a labelling rule. It runs *before any ownership link is built* and decides
**whether the box on the chart is a party at all**. Adding a word to its example list therefore
changes the output, not just the wording. There are exactly two outcomes:

**RESOLVED** — a legend, footnote, ownership note, table or client email says who is behind the
label:

- the underlying parties are extracted as the owners, one record each;
- **the label itself is emitted nowhere at all** — it is deleted from the structure, and the
  parties behind it attach directly to the entity below;
- percentages are taken **exactly as printed** for each party, never multiplied, never rescaled.

**UNRESOLVED** — no supplied document says who is behind it:

- the label is kept as one record with the printed percentage, but marked
  `roleCapacity = "Information Only"`, `isIBO = false`, `isUBO = false`, flagged
  `COLLECTIVE_OWNER_UNRESOLVED`, the case set to `NEED_REVIEW`, and an advisory raised.

Today that treatment applies to things like "Passive Investors" and "Other Shareholders" —
labels that name **nobody**. A contractual partnership is different in kind: it is often a
**named** thing with a GP, LPs, a partnership agreement and stated internal percentages. That is
why the questions below exist.

---

## 3. What else in the prompt this touches

Six existing rules give a different answer for the same box once "Contractual partnership" is on
the 9.0A list. We cannot implement until we know which one wins.

| # | Existing rule | What it says today | Conflict |
|---|---|---|---|
| 1 | **11.7 Partnership / LP / KG** | extract the general partner, the limited partners, partnership percentages, GP control rights, and look through a corporate GP where local rules require | 9.0A says emit **nothing** for the partnership. 11.7 says treat it as an entity with owners beneath it |
| 2 | **Reviewer check "Entity-Type Specific Logic"** | scores whether the GP look-through was performed | If the partnership is correctly deleted, the reviewer reads it as a **skipped look-through** and raises a finding on every such case |
| 3 | **12.1 IBO definition** | a partnership is a Non-Natural Person and **is an IBO** if it is over the threshold | Under 9.0A it is never an IBO — deleted if resolved, forced to "Information Only" if not |
| 4 | **Ladder rungs "General Partner" / "Limited Partner"** | the roles a partner is labelled with | Unreachable once the partnership node is gone; the partners attach to the entity below with no partner relationship to it |
| 5 | **9.0A "percentages are read as printed, never multiplied"** | correct for passive investors, where the email gives each fund's share **of the target** | A partnership normally states the **internal** split. See Q2 — this is the one that produces wrong numbers |
| 6 | **9.3 per-target sum check** | every owner of one entity should total ≈100% | Follows from Q2. A wrong reading here makes routine cases fail the sum check |

---

## 4. Questions

### Q1. Which boxes does this apply to?

- **Option A (recommended).** Only where the chart shows a **generic descriptor** — a box
  literally labelled "Contractual partnership", "Partnership", "JV partners", "Consortium" —
  i.e. no named legal vehicle. Named vehicles (`ABC Fund LP`, `X GmbH & Co. KG`, `Y LLP`) keep
  today's treatment under rule 11.7.
- **Option B.** Any partnership **without separate legal personality** in its jurisdiction —
  including named ones (German GbR, unincorporated joint ventures, consortium agreements,
  associations in participation). This is closer to "not a legal structure but a pool of
  entities", but the prompt would have to decide legal personality from the document, which it
  often cannot.
- **Option C.** **All** partnerships, named or not, including LPs, LLPs and KGs. This effectively
  withdraws rule 11.7.

**Answer (A, B or C):** A

---

### Q2. Percentages — the most important question

A contractual partnership holds **60%** of the client. The partnership agreement says the
partners are **Partner X 50% / Partner Y 50%** — that is 50% *of the partnership*, not of the
client.

- **Option A (recommended).** Multiply through: Partner X **30%**, Partner Y **30%** of the
  client. The numbers add up and the 9.3 sum check still works.
- **Option B.** Read as printed, as 9.0A does today: Partner X **50%**, Partner Y **50%** of the
  client. This is what the rule currently says, and it is right for passive investors because
  there the email states each fund's share *of the target*. Applied here it would overstate both
  partners and push the entity's shareholders over 100%, which raises a percentage-conflict flag
  and sends routine cases to manual review.
- **Option C.** Only emit partner percentages where the document states them **against the
  client**; otherwise record the partners with no percentage and raise a gap.

Note: on 2026-08-28 the answer for collective owners was *"do not multiply"* — that was for
figures already stated against the target. This case is different, so please answer it
separately.

**Answer (A, B or C):** A

---

### Q3. Does the partnership stay in the chart or disappear?

- **Option A (recommended).** The partnership **stays as a node** and the partners sit above it,
  as today under 11.7 — 9.0A only stops us treating the partnership as *the* owner where the
  partners are known, and forces the partners to be identified.
- **Option B.** The partnership is **deleted** and the partners attach directly to the entity
  below, exactly as "Passive Investors" is deleted today. Consequence: no GP look-through, no IBO
  recorded for the partnership itself, and the ownership chain gets one layer shorter.

**Answer (A or B):** A

---

### Q4. What happens to the general partner's control?

A GP frequently has **control with little or no economic share**. If the partnership node is
deleted (Q3 option B), the GP's control right has nothing to attach to.

- **Option A (recommended).** The GP is still recorded on the **control** basis (control-based
  IBO/UBO), even at 0% economic ownership.
- **Option B.** Only economic holders are recorded; GP control is noted in the reasoning text
  only.

**Answer (A or B):** A

---

### Q5. Where the partners are NOT identified in the documents

9.0A's unresolved treatment would record the partnership as **"Information Only", not an IBO**,
with a flag and a case-level `NEED_REVIEW`.

- **Option A (recommended).** Yes — that is the intended output. Nothing is classified as an IBO
  until the partners are known.
- **Option B.** No — a named contractual partnership over the threshold should still be recorded
  as an **IBO**, with the flag and advisory alongside it.

**Answer (A or B):** A

---

### Q6. Does this also cover the case where the CLIENT is a contractual partnership?

Rule 9.0A only reaches labels printed as **owners** in the chart. It does nothing when the
**client entity itself** is a contractual partnership (`entity_type`). If that case needs
handling — e.g. every partner treated as a client-side party — it is a separate new rule, not
this one.

- **Option A.** Owner-side only for now. Client-side raised separately if needed.
- **Option B.** Client-side is also in scope — please describe the expected output.

**Answer (A or B):** A

---

## 5. What we will implement

Once Q1–Q3 are answered we will make three changes, not one:

1. add `Contractual partnership` to rule 9.0A's example list, as asked;
2. add **one sentence** saying which boxes it catches and which stay with rule 11.7 (Q1) — without
   it, the list is explicitly "illustrative, not a closed list", so the model may extend it to
   every LP and KG on its own;
3. add **one sentence** to rule 11.7 saying which rule wins, plus the matching change in the
   reviewer prompt — without it the reviewer raises a finding against the correct output on every
   case, and the extract/review loop cannot settle.

If Q1–Q3 are left blank we will not implement, because an undecided question is what produces
run-to-run flips: the same case comes back with different numbers depending on which sentence the
model reads first.

---

## 6. For engineering (not needed to answer)

Change sites once agreed — the example list is reproduced in five places and all must move
together, or the reviewer scores the correct output as a failure:

- `monolith/system.txt` §9.0A (the list), §11.7 (routing sentence), rule 16 flag mapping
- `monolith_critic/system.txt` R11 and the COLLECTIVE-LABEL GUARD
- `monolith_critic/user.txt` criterion 1 (guard) and criterion 12 (GP look-through)
- `monolith/schema.json` — `itemType` and `roleCapacity` descriptions
- `CLAUDE.md`, `tests/test_monolith_prompts.py` (label list + R11 tests)
- regenerate both `insert_prompt.sql` via `tools/gen_insert_sql.py`
