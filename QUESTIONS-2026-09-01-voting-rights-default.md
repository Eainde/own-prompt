# Voting Rights — proposed rule change and questions for the business owner

**Date raised:** 2026-09-01
**Raised by:** engineering (KYC ownership extraction prompt)
**Needed from:** KOS text owner / business rule owner
**How to answer:** write under each `**Answer:**` line. Anything left blank stays unimplemented.

---

## 1. The issue reported

Every record in the extractor output is coming back with

```
actualVotingRightsPercentage = 0
```

The reason is that the prompt currently treats a **share percentage** and a **voting percentage**
as two different facts. It records a voting figure **only** where a document states voting
explicitly. A chart or share register that prints `"52.27% Shares"` and nothing about votes
produces `voting = 0` plus a "missing voting rights" gap flag.

The proposed change is:

> **Where voting rights are not stated explicitly, the ownership percentage IS the voting
> percentage.**

So `"52.27% Shares"` would produce `ownership = 52.27` **and** `voting = 52.27`.

---

## 2. What the prompt does today, and why — please read before answering

This is **not** a bug in the sense of something the prompt does by accident. The current
behaviour was written deliberately, and there is history on it:

- **Settled 2026-08-22.** The same case was run twice and the same chart label `"100% Shares"`
  came back as `voting = 100` on one run and `voting = 0` on the next. Nothing in the prompt
  answered the question, so both readings survived. The rule (`§9.2.2`) was added to stop the
  flip, and it was settled in the **"shares are not votes"** direction, on the reasoning that
  shares and votes genuinely diverge: dual-class shares, preference shares, non-voting shares,
  golden shares, and Dutch **STAK / Stichting Administratiekantoor** depositary receipts (where
  the foundation holds the votes and the receipt-holders hold the economics).

- **2026-08-28.** During the Form ADV work the business team answered a related question with:

  > *"Voting rights not needed, the existing prompt understands ownership and voting right logic
  > so not adding any additional value for voting rights."*

  That was read as confirming the current behaviour and no change was made.

So the change now proposed is a **reversal of a settled rule**, not the filling of a gap. That is
entirely the business's call — we just need it recorded in writing, because it changes roughly
25 places across the extractor prompt, the output schema and the reviewer ("critic") prompt, and
because the previous position is documented as settled.

**Q0. Please confirm the reversal.** From now on, where no document states voting rights, the
extractor should copy the ownership percentage into the voting field, instead of recording 0 plus
a "missing voting rights" gap.

**Answer:**

---

## 3. Questions that must be answered before we can implement

Each of these changes what the output looks like. If any is left blank we cannot implement that
part, because an undecided question is what produced the original run-to-run flip.

### Q1. Should the deemed voting figure count towards **domination**?

Domination (`dominationIndicator`) is decided by a fixed ladder, first match wins:

| Rung | Test |
|------|------|
| D1 | more than 50% **share ownership** on this link |
| D2 | more than 50% **voting rights** on this link |
| D3 | an evidenced control right (board appointment, veto, shareholder agreement, etc.) |
| D4 | none of the above |

If voting is deemed equal to ownership, then **every majority shareholder now satisfies both D1
and D2**. D1 still matches first, so the recorded *basis* would not change — but the field
`dominationVotingRights` would flip from `0` to the ownership figure on almost every majority
link, and a reader would see an assertion of majority voting that no document makes.

- **Option A (recommended).** A deemed voting figure is a **D1 basis only** and is **never**
  treated as evidence of majority voting for D2. `dominationVotingRights` stays 0 unless a
  document states votes. Domination outcomes are completely unchanged by this whole change.
- **Option B.** A deemed voting figure counts for D2 as well. `dominationVotingRights` is
  populated wherever ownership exceeds 50%.

**Answer (A or B):**

---

### Q2. When must the default **not** apply?

There are documented structures where shares and votes deliberately differ. If we deem voting =
ownership in these cases, the output would state the opposite of what the document says.

Proposed exception list — the default does **not** apply, and voting is taken from the document,
where the documents show any of:

1. non-voting shares, preference shares, or a dual-class / multiple-class share structure;
2. a golden share or a special/enhanced voting share;
3. a STAK / Stichting Administratiekantoor or comparable trust office issuing **depositary
   receipts** (foundation votes, receipt-holders hold economics);
4. a shareholders' agreement, voting agreement, voting cap or pass-through voting clause that
   sets voting entitlement differently from the shareholding;
5. a registry/database column that states voting separately from ownership.

In each of those, the **stated** voting position governs and nothing is deemed.

**Q2a. Is this exception list correct and complete? Anything to add or remove?**

**Answer:**

**Q2b. Where the document shows one of the above but does not give a voting number** (e.g. it
says "Class B shares are non-voting" but gives no percentages) — should the record show voting =
0, or should it stay a gap for a caseworker to resolve?

**Answer:**

---

### Q3. What happens to the "missing voting rights" flag and the advisory?

Today, when voting is not stated the extractor produces three things:

| Artefact | Meaning today |
|---|---|
| `MISSING_VOTING_RIGHTS` (per record) | this record's own voting % is not stated |
| `DILUTED_VOTING_INCOMPLETE` (per record) | the indirect/diluted voting figure could not be calculated because some link in the chain has no voting % |
| an **advisory request** for the case | asking the client/relationship team for the voting-rights position |

Once voting defaults to the ownership figure, none of these has a trigger any more — nothing is
"missing". Three options:

- **Option A.** Drop all three. The output no longer signals anything about voting; a reader
  cannot tell a deemed figure from a documented one **in the flags** (it will still be visible in
  the record's reasoning text).
- **Option B (recommended).** Drop `MISSING_VOTING_RIGHTS` and `DILUTED_VOTING_INCOMPLETE`, but
  keep **one advisory per case** asking for the voting-rights position where any figure in the
  case was deemed rather than documented. Cost: nil. Benefit: the file still shows a reviewer
  that the voting numbers are assumed, not evidenced.
- **Option C.** Add a **new flag** (e.g. `VOTING_RIGHTS_DEEMED`) on every record carrying a deemed
  figure. This is the most transparent, but it adds a new name to a closed vocabulary that
  downstream systems match literally — please confirm explicitly if you want it, and confirm the
  spelling.

**Answer (A, B or C — and if C, the exact flag name):**

---

### Q4. Does the default apply to percentages that are themselves derived?

Three wordings/codes already supply an ownership percentage without printing a number:

| Source | Ownership figure produced |
|---|---|
| "wholly owned subsidiary of", "sole shareholder", "entirely owned by" | 100 |
| ORBIS `WO` code (Wholly Owned) | 100 |
| SEC Form ADV ownership codes (`NA` 4.99 / `A` 6.00 / `B` 11.00 / `C` 25.01 / `D` 50.01 / `E` 75.01) | the banded figure |

Should the voting default apply to these too?

- **Option A (recommended for consistency).** Yes — a wholly-owned subsidiary gets voting = 100,
  a Form ADV `D` holder gets voting = 50.01.
- **Option B.** No — the default applies only where an ownership **percentage is printed**;
  wordings and codes remain ownership-only. Note this would leave `voting = 0` on a large share
  of real cases, which is most of what you are seeing now.

Note: today the prompt says in terms that a Form ADV **ownership** code says nothing about votes.
If you choose A we remove that sentence.

**Answer (A or B):**

---

### Q5. Indirect / diluted voting

For indirect holders the prompt also calculates a **diluted** voting figure — the voting
percentages multiplied down the chain to the client (e.g. 80% × 60% × 50% = 24%). Today that
figure is 0 whenever any link in the chain has no stated voting %.

Once voting defaults to ownership, the deemed chain will produce a diluted **voting** figure equal
to the diluted **ownership** figure on most cases.

- **Option A (recommended).** Yes, that is intended — diluted voting simply equals diluted
  ownership wherever every link is deemed.
- **Option B.** No — the deemed figure is used for the direct link only, and the diluted voting
  figure stays 0/unknown unless the whole chain is documented.

**Answer (A or B):**

---

## 4. Worked example — what the change looks like in the output

Chart shows: `Fund X ——52.27% Shares——> Mollie Holding B.V.` and nothing about votes.

**Today**

```
actualOwnershipPercentage    = 52.27
actualVotingRightsPercentage = 0
dominationOwnership          = 52.27      (majority ownership → domination YES)
dominationVotingRights       = 0
ownershipVotingMismatch      = false      (unknown is not a mismatch)
qaFlags                      = [MISSING_VOTING_RIGHTS, DILUTED_VOTING_INCOMPLETE]
advisory                     = "voting-rights position requested"
```

**After the change, with the recommended answers (Q1=A, Q3=B, Q4=A, Q5=A)**

```
actualOwnershipPercentage    = 52.27
actualVotingRightsPercentage = 52.27      <-- deemed from ownership
dominationOwnership          = 52.27
dominationVotingRights       = 0          <-- unchanged: deemed voting is not a D2 basis (Q1=A)
ownershipVotingMismatch      = false      <-- deemed voting always equals ownership
qaFlags                      = []
advisory                     = one per case, noting voting figures are deemed
```

---

## 5. What we will change if this is confirmed

For visibility only — no action needed from the business on this section.

- **Extractor prompt:** rule `§9.2.2` (the core rule, reversed), plus `§9.2.3` (Form ADV),
  `§9.2.4` (where a figure is allowed to come from), `§10.1.1` (diluted voting), `§10.2.0` /
  `§10.2.1` (domination ladder and its two percentage fields), `§12.2.1` (UBO qualification via
  voting), rule 15 (advisories), rule 16 (flag list), rule 18 (reasoning trace), the output
  contract and three worked examples.
- **Output schema:** the descriptions of `actualVotingRightsPercentage`,
  `dominationVotingRights`, `dilutionVotingRightsPercentage`, `ownershipVotingMismatch`.
- **Reviewer ("critic") prompt:** it currently scores a voting figure copied from the share figure
  as an **invented percentage — CRITICAL**. Until it is changed with the extractor, every case
  would be sent back for rework. This is the single most important dependency.
- **Test suite and documentation** pinning the current rule.

Estimated effort once the answers above are in: about half a day, plus a regression run on a
known case to confirm nothing else moved.

---

## 6. Sign-off

| | |
|---|---|
| Answered by | |
| Role | |
| Date | |

Once answered, this file becomes the clause-by-clause change trace for the work, in the same form
as `CHANGES-2026-08-27-business-rules.md`.
