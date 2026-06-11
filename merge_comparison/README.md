# Wave 3 — Merge + Comparison

## What this prompt does

Takes all Wave 2 per-document normalized ownership graphs and combines them into one composite annotated graph. It **never merges, deduplicates, or removes any node or edge**. Every node from every document is preserved exactly, with its id namespaced to be globally unique.

Instead of collapsing entities, Wave 3 annotates the composite graph with two hint arrays for the downstream Conflict Detection agent (Agent 4): `overlaps` (candidate same-entity groups) and `potential_conflicts` (cross-document percentage disagreements). No merging or conflict resolution happens here — that is Agent 4's job.

**Input:**
- `{{normalisedEntities}}` — the full Wave 2 output (all per-document normalized graphs)

**Output:** a single JSON object matching `schema.json` with top-level arrays `nodes`, `edges`, `overlaps`, `potential_conflicts`, `cycles`, `conflicts`

---

## Key behaviours

- **No elimination**: every node and edge from every input document appears in the output. A node that looks like a duplicate of another in a different document is still kept as its own node.
- **Namespaced ids**: every node gets a globally unique id of the form `<source_doc_id>::<original_slug>` (e.g. `doc_1::alpha_holdings`). Every edge's `owner` and `owned` are remapped to the namespaced ids of the same document the edge came from. Nothing is collapsed across documents.
- **Source attribution**: every node carries a `source_document` field (GCS URI); every edge carries `source_document` and `source_type`.
- **Overlap tagging (W3.R3)**: candidate same-entity groups are identified by exact `dedupKey`, then `asciiDedupKey`, then unambiguous `normalizedName` similarity within the same type. Each group is emitted in the `overlaps` array with `dedup_key`, `member_ids`, `match_basis`, and `confidence_note`. These are **hints for Agent 4 — not merges**.
- **Contradiction flagging (W3.R4)**: for relationships that map to the same owner/owned overlap group across documents, percentage values are compared. A `potential_conflicts` entry is emitted with `status: "agree"` (all values within 2%) or `status: "contradict"`. No `resolution_strategy` or `resolved_value` is set — resolution is Agent 4's job.
- **Carry-forward (W3.R5)**: upstream `cycles` and `conflicts` arrays are passed through **unchanged and unresolved**. Conflict `resolution_strategy` may be `"deferred"` as set upstream. Wave 3 does **not** add cross-document cycles (Agent 4 does that on the merged identity).
- **Agent 2 blind**: Wave 3 does not receive `{{clientEntityName}}`. It processes the graph structure uniformly regardless of which node is the client entity.

---

## Rules

| Rule | Priority | Summary |
|---|---|---|
| W3.R1 NAMESPACE_IDS | CRITICAL | Assign every node a `<source_doc_id>::<slug>` id; remap edges to matching namespaced ids; set `source_document` on every node and edge. |
| W3.R2 NO_ELIMINATION | CRITICAL | Include every node and every edge from every input document. Never merge, drop, deduplicate, or rewrite any entity or relationship. |
| W3.R3 OVERLAP_TAGGING | CRITICAL | Group candidate same-entity node ids into the `overlaps` array. Annotate only — do not merge the members. |
| W3.R4 CONTRADICTION_FLAG | CRITICAL | For each overlapping owner/owned relationship pair across documents, compare percentages and emit a `potential_conflicts` entry. Do not resolve. |
| W3.R5 CARRY_FORWARD | CRITICAL | Pass upstream `cycles` and `conflicts` forward unchanged. Do not resolve conflicts or add cross-document cycles. |

---

## Response schema

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `nodes` | array | All ownership nodes from all input documents, preserved with namespaced ids. Nothing is merged or eliminated. |
| `edges` | array | All ownership edges from all input documents, with namespaced `owner`/`owned` ids. Nothing is merged or eliminated. |
| `overlaps` | array | Candidate same-entity groups across documents. Hints for Agent 4 — not merges. |
| `potential_conflicts` | array | Cross-document percentage disagreements on overlapping relationships. Detection only — Agent 4 resolves. |
| `cycles` | array | All cycles detected upstream (W1), carried forward unchanged. |
| `conflicts` | array | Ownership percentage conflicts carried forward from upstream waves, unresolved. |

---

### `nodes[]` — all composite nodes

One entry per node per source document. Nothing is collapsed.

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Namespaced id: `<source_doc_id>::<original_slug>`. Globally unique; never collapsed across documents. E.g. `"doc_1::alpha_holdings"`. |
| `source_document` | string | No | GCS URI of the source document this node came from. |
| `name` | string | No | Legal name carried forward from Wave 2 unchanged. |
| `normalizedName` | string | No | Canonical normalized name from Wave 2, carried forward unchanged. |
| `dedupKey` | string | No | Deduplication key from Wave 2, carried forward unchanged. Used for overlap grouping. |
| `asciiDedupKey` | string | No | ASCII-transliterated `dedupKey` from Wave 2, carried forward unchanged. |
| `type` | string (enum) | No | Entity type from Wave 2. Unchanged. |
| `entity_type_category` | string (enum) | No | KYC risk category per node (e.g. `"Category 1 - Private/Regulated/Listed"`). Carried forward from Wave 2. |
| `listing_proof` | object or null | Yes | Listing evidence for listed companies. Null otherwise. Carried forward unchanged. |
| `data_gaps` | string | Yes | Missing-information notes. Carried forward unchanged. |
| `exceptions` | string | Yes | Structural anomaly notes. Carried forward unchanged. |
| `classification_reasoning` | string | Yes | Classification justification from Wave 2. Carried forward unchanged. |

---

### `edges[]` — all composite edges

One entry per edge per source document. Nothing is merged.

| Field | Type | Nullable | Description |
|---|---|---|---|
| `owner` | string | No | Namespaced id of the owning entity (same document as this edge). |
| `owned` | string | No | Namespaced id of the owned entity (same document as this edge). |
| `ownership_percentage_direct` | string | No | Carried forward from Wave 2 unchanged. |
| `control` | string | Yes | Carried forward from Wave 2 unchanged. |
| `source` | string | No | Source document identifier. Carried forward from Wave 2 unchanged. |
| `source_document` | string | No | GCS URI of the source document this edge came from. |
| `source_type` | string (enum) | No | `"chart_structure"` or `"document_text"`. Carried forward from Wave 2 unchanged. |
| `direction_proof` | string | No | Direction evidence string. Carried forward from Wave 2 unchanged. |

---

### `overlaps[]` — candidate same-entity groups

One entry per group of nodes that may represent the same real-world entity across documents. These are annotation hints only — the members are not merged.

| Field | Type | Description |
|---|---|---|
| `dedup_key` | string | The shared key used to group these nodes (e.g. the common `dedupKey` value). |
| `member_ids` | array of strings | Namespaced node ids in this candidate group. |
| `match_basis` | string (enum) | How the match was identified: `"dedupKey"`, `"asciiDedupKey"`, or `"similarity"`. |
| `confidence_note` | string | Brief explanation of match confidence, including any ambiguity or caveats. |

Singleton groups (only one document mentions the entity) are omitted — no cross-document candidate means no overlap entry.

---

### `potential_conflicts[]` — cross-document percentage disagreements

One entry per overlapping owner/owned relationship pair where percentages can be compared across documents.

| Field | Type | Description |
|---|---|---|
| `owner_overlap` | string | The `dedup_key` of the owner overlap group. |
| `owned_overlap` | string | The `dedup_key` of the owned overlap group. |
| `values` | array of strings | All observed `ownership_percentage_direct` values across documents. |
| `sources` | array of strings | Corresponding source document identifiers, parallel to `values`. |
| `status` | string (enum) | `"agree"` if all values are within 2% of each other; `"contradict"` otherwise. |

No `resolution_strategy` or `resolved_value` is set. Agent 4 resolves contradictions.

---

### `cycles[]` — upstream cycles, carried forward

All cycles detected in Wave 1, passed through unchanged.

| Field | Type | Description |
|---|---|---|
| `cycle_path` | array of strings | Ordered node ids forming the loop, starting and ending at the repeated node. |
| `detected_at` | string (enum) | `"W1"` (per-document cycle detection). Wave 3 does NOT add `"W3"` entries. |
| `source` | string | Source document where the cycle was detected. |

---

### `conflicts[]` — upstream conflicts, carried forward unresolved

All ownership percentage conflicts from Wave 1, passed through unchanged. Agent 4 resolves.

| Field | Type | Description |
|---|---|---|
| `owner` | string | Owner entity identifier (as set by the upstream wave). |
| `owned` | string | Owned entity identifier (as set by the upstream wave). |
| `conflict_description` | string | Human-readable description of the conflict. |
| `value_a` | string | First conflicting value. |
| `source_a` | string | Source of `value_a`. |
| `value_b` | string | Second conflicting value. |
| `source_b` | string | Source of `value_b`. |
| `resolution_strategy` | string (enum) | As set upstream. May be `"deferred"` — Wave 3 does NOT override this. |
| `resolved_value` | string | As set upstream. Wave 3 does NOT modify this. |
