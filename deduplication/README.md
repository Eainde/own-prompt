# Wave 3 — Deduplication

## What this prompt does

Takes all Wave 2 per-document normalized ownership graphs and merges them into a single unified flat ownership graph. Entities that refer to the same real-world company or person across different documents are collapsed into one node. Ownership edges are merged or flagged as conflicting. After all merges are complete, a depth-first search detects any new cyclic ownership loops that only become visible once cross-document data is combined.

This is the only wave that merges across documents. The output is one flat graph representing everything known about the client entity's ownership structure.

**Input:**
- `{{wave2OutputJson}}` — the full Wave 2 output object (containing `normalized_documents` array)

**Output:** a single flat JSON object matching `schema.json`

---

## Key behaviours

- **Key-based matching only**: entities are matched using `dedupKey` (exact match first), then `asciiDedupKey` (diacritic-insensitive), then `normalizedName` similarity within the same entity type. Similarity-based matching is conservative — a false separate (same entity kept as two nodes) is recoverable downstream; a false merge (two different entities collapsed) corrupts the graph and is not recoverable.
- **Canonical id**: every merged node gets a canonical `dedupKey` as its `id`. All edges, cycles, and conflicts referencing merged nodes have their `owner`/`owned` fields updated to use this canonical key.
- **Edge merging**: if the same `(owner, owned)` pair appears with the same percentage across documents, the edges are merged into one (with a comma-separated source list). If they appear with different percentages, both values are preserved in `conflicts` and the edge's `ownership_percentage_direct` is set to `"CONFLICT — see conflicts array"`.
- **Conflict carry-forward**: all conflicts from Wave 1/2 are preserved and updated to use canonical dedupKeys.
- **Structural cycle detection (W3.R7)**: after all merges, a DFS traversal detects loops that span multiple documents — e.g. Document A shows A owns B and Document B shows B owns A. These are added to `cycles` with `detected_at: "W3"`.
- **No data loss**: every node from every input document is included in the output, even nodes that appear in only one document.
- **Client-blind**: Wave 3 does not receive `{{clientEntityName}}`. The client entity is identifiable as the node with `layer = 0`.

---

## Response schema

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `client_entity` | string | Legal name of the target entity. Carried forward from Wave 2. |
| `entity_type_category` | string (enum) | KYC risk category. Carried forward from Wave 2. |
| `nodes` | array | All deduplicated ownership entities. One node per unique real-world entity across all documents. |
| `edges` | array | All deduplicated ownership relationships. Owner and owned reference canonical dedupKey ids. |
| `cycles` | array | All ownership cycles — those detected in Wave 1 plus any new ones found post-dedup. |
| `conflicts` | array | All ownership percentage conflicts — intra-document ones from Wave 1/2 plus new cross-document ones found here. |
| `deduplication_log` | array | Audit trail of every merge decision made during deduplication. |

---

### `nodes[]` — deduplicated entities

One node per unique real-world entity. The `id` is the canonical `dedupKey` chosen during merging.

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | The canonical `dedupKey` selected during merging. Used as the stable identifier for this entity in all edges, cycles, and conflicts. E.g. `"alpha_holdings"`. |
| `name` | string | No | The most complete legal name found across all input documents for this entity. Prefer the longer, more complete form — e.g. `"Deutsche Bank Aktiengesellschaft"` over `"Deutsche Bank"`. |
| `normalizedName` | string | No | Canonical normalized name from Wave 2. |
| `dedupKey` | string | No | The canonical deduplication key for this entity — the one selected as canonical when multiple keys were merged. |
| `asciiDedupKey` | string | No | ASCII-transliterated version of `dedupKey`. |
| `type` | string (enum) | No | Entity type classification. See Wave 1 README for all values. |
| `layer` | integer (≥0) | No | Distance from the client entity. Never changed from Wave 2 values. |
| `listing_proof` | object or null | Yes | Listing evidence for listed companies. Null otherwise. |
| `data_gaps` | string | Yes | Missing-information notes. Any `data_gaps` values from across all input documents for this entity are carried forward. |
| `exceptions` | string | Yes | Structural anomaly notes. Set to `"Cycle detected post-dedup: see cycles array."` if this node is involved in a W3-detected cycle. |

---

### `edges[]` — deduplicated ownership relationships

Each edge represents a unique ownership relationship in the merged graph. `owner` and `owned` reference canonical `dedupKey` ids.

| Field | Type | Nullable | Description |
|---|---|---|---|
| `owner` | string | No | Canonical `dedupKey` of the owning entity. |
| `owned` | string | No | Canonical `dedupKey` of the owned entity. |
| `ownership_percentage_direct` | string | No | Ownership percentage. One of: the exact percentage if consistent across all documents (e.g. `"60%"`); `"Not Available"` if no document states a percentage; `"CONFLICT — see conflicts array"` if different percentages appear across documents. |
| `control` | string | Yes | Evidence of control (voting rights, board rights, etc.). If documents disagree on the control value, the earlier document's value is used. Null if not mentioned. |
| `source` | string | No | Comma-separated list of all source documents where this relationship appears (e.g. `"doc1.pdf, doc2.pdf"`). |

---

### `cycles[]` — all detected ownership loops

Carries forward all cycles from Wave 1 and adds any new ones discovered post-dedup.

| Field | Type | Description |
|---|---|---|
| `cycle_path` | array of strings | Ordered list of canonical `dedupKey` ids forming the loop, starting and ending at the repeated node. E.g. `["alpha", "beta", "gamma", "alpha"]`. |
| `detected_at` | string (enum) | `"W1"` if detected during Wave 1 per-document extraction. `"W3"` if detected after cross-document merging. |
| `source` | string | Source document(s) where the cycle was detected. |

---

### `conflicts[]` — all ownership percentage conflicts

Carries forward all conflicts from Wave 1/2 (intra-document) and adds new cross-document conflicts discovered during edge merging. Raw values are always preserved.

| Field | Type | Description |
|---|---|---|
| `owner` | string | Canonical `dedupKey` of the owning entity in the conflicting relationship. |
| `owned` | string | Canonical `dedupKey` of the owned entity in the conflicting relationship. |
| `conflict_description` | string | Human-readable description of the conflict. |
| `value_a` | string | First conflicting percentage value. For 3+ conflicting values, this is the highest value found. |
| `source_a` | string | Document where `value_a` was found. For 3+ conflicting values, this is the source of the highest value. |
| `value_b` | string | Second conflicting percentage value. For 3+ conflicting values, this is the lowest value found. |
| `source_b` | string | Document where `value_b` was found. For 3+ conflicting values, this is the source of the lowest value. |
| `resolution_strategy` | string (enum) | Rule used to derive `resolved_value`. See strategy values below. |
| `resolved_value` | string | The value Wave 5 should use. Never empty. Derived by applying `resolution_strategy` to the conflicting values. Raw values are never modified. |

**`resolution_strategy` values:**

| Value | Meaning |
|---|---|
| `use_higher` | Use the larger percentage value. Default strategy. |
| `use_lower` | Use the smaller percentage value. |
| `use_most_recent` | Use the value from the more recently dated document. |
| `manual` | Cannot be resolved automatically — requires human review. |

---

### `deduplication_log[]` — merge audit trail

One entry per merge decision. Every time two or more nodes are collapsed into one canonical entity, a log entry is created. This is an audit trail — it is not carried forward to Wave 4.

| Field | Type | Description |
|---|---|---|
| `canonical_id` | string | The `dedupKey` chosen as the canonical id for the merged entity. |
| `merged_keys` | array of strings | All `dedupKey` values that were merged into this canonical entity (including the canonical one itself). |
| `merged_names` | array of strings | All `name` values from across the merged nodes. |
| `reason` | string | Explanation of why these nodes were merged — which matching rule was used (exact `dedupKey` match, `asciiDedupKey` match, or similarity) and any relevant detail. |
