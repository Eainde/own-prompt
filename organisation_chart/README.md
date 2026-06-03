# Wave 4 — Organisation Chart

## What this prompt does

Takes the Wave 3 unified flat ownership graph and converts it into a nested ownership tree structure. Starting from the client entity at the root, it recursively embeds each entity's owners as children, building an upward-tracing tree that shows the full chain of ownership up to the ultimate beneficial owners.

This is a pure structural transformation — no new data is inferred or invented. All relationship data (ownership percentages, control evidence, source references) is lifted directly from the Wave 3 edges.

**Inputs:**
- `{{clientEntityName}}` — legal name of the target entity (used to set the `client_entity` output field)
- `{{wave3OutputJson}}` — the Wave 3 flat graph JSON

**Output:** a single JSON object matching `schema.json`

---

## Key behaviours

- **Tree grows upward**: in this tree, children are owners — not owned entities. The root is the client entity (layer 0), its children are the entities that directly own the client, their children are the entities that own those, and so on. The tree traces ownership up toward ultimate beneficial owners.
- **Edge-derived ownership data**: each tree node carries `ownership_percentage_direct`, `control`, and `source` from the specific Wave 3 edge linking it to its parent. A node that appears in multiple subtrees (multi-parent ownership) carries different values in each subtree — whichever edge connects it to that particular parent.
- **Cycle handling (W4.R3)**: before recursing into a child, the ancestry path for the current branch is checked. If a node's id already appears in the path, recursion stops, `children` is set to `[]`, and `exceptions` is set to a cycle-detected message. The upstream `cycles` array from Wave 3 is the authoritative cycle record and is passed through unchanged.
- **Orphaned nodes (W4.R4)**: nodes with no incoming edges (no one owns them) that are not the root are attached directly to the root with a `data_gaps` note.
- **Conflicts and cycles passed through unchanged**: Wave 4 does not modify or resolve any conflict or cycle entries. Wave 5 consumes `resolved_value` from conflicts.
- **Schema version v0**: the schema is subject to change pending the internal Wave 5 system specification.

---

## Response schema

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `client_entity` | string | Legal name of the target entity (from `{{clientEntityName}}`). The root of the ownership tree. |
| `entity_type_category` | string (enum) | KYC risk category carried forward from Wave 3. See values below. |
| `ownership_tree` | object | The root tree node (the client entity). All owners are nested recursively as children. See `tree_node` definition below. |
| `cycles` | array | All ownership cycles carried forward from Wave 3 unchanged. See `cycles[]` below. |
| `conflicts` | array | All ownership percentage conflicts carried forward from Wave 3 unchanged. See `conflicts[]` below. |
| `metadata` | object | Schema versioning information. |

### `entity_type_category` values

| Value | Meaning |
|---|---|
| `Category 1 - Private/Regulated/Listed` | Standard company — private, regulated, or publicly listed |
| `Category 2 - Adverse Media` | Entity with adverse media or elevated risk indicators |
| `Category 3 - SPV/Trust/Charity/Foundation` | Special purpose or non-profit structure |
| `Entity Type not confirmed` | Insufficient evidence to classify |

---

### `ownership_tree` — the nested tree node (recursive)

The root node represents the client entity. Its `children` array contains all entities that directly own the client, each of which has its own `children` array of their owners, and so on recursively. Every node in the tree has the same structure (`tree_node`).

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Canonical `dedupKey` from Wave 3, identifying this entity. |
| `name` | string | No | Most complete legal name from Wave 3. |
| `type` | string (enum) | No | Entity classification. See Wave 1 README for all values. |
| `layer` | integer (≥0) | No | Distance from the client entity. `0` = client (root), `1` = direct owners, `2` = owners of layer 1, etc. Never renumbered from Wave 3. |
| `ownership_percentage_direct` | string | Yes | The ownership percentage from the Wave 3 edge linking **this specific node instance** to its parent in **this subtree**. `null` for the root node (it has no parent). If the underlying edge had a conflict, this will be `"CONFLICT — see conflicts array"`. |
| `control` | string | Yes | Control evidence (voting rights, board rights, etc.) from the same Wave 3 edge. `null` for the root node or when not stated. |
| `source` | string | Yes | Document reference from the same Wave 3 edge — where this specific ownership relationship was evidenced. `null` for the root node. |
| `listing_proof` | object or null | Yes | Listing evidence for listed companies, carried from Wave 3. Null for all other types. |
| `data_gaps` | string | Yes | Missing-information notes from Wave 3. Also set to `"Orphaned node: no incoming edges. Attached to root."` if this node had no incoming edges in Wave 3. |
| `exceptions` | string | Yes | Structural anomaly notes. Set to `"Cycle detected: [full path from root to this node]"` when recursion was stopped because this node already appeared in the current branch's ancestry path. |
| `children` | array | No | Direct owners of this node — all nodes Y where a Wave 3 edge `{ owner: Y, owned: this_node }` exists. Empty array `[]` for terminal nodes (ultimate beneficial owners with no further known owners) and for cycle-broken instances. |

> **Important:** a node that is owned by multiple parents will appear multiple times in the tree — once in each parent's subtree. Each appearance is an independent instance with its own `ownership_percentage_direct`, `control`, and `source` values drawn from the specific edge linking it to that particular parent.

**`listing_proof` sub-fields** (when not null):

| Field | Type | Description |
|---|---|---|
| `exchange` | string | Name or code of the stock exchange |
| `ticker` | string | Exchange ticker symbol |
| `isin` | string | International Securities Identification Number |
| `proof_source` | string | Page/section of the document where listing evidence was found |

---

### `cycles[]` — ownership loops (passed through from Wave 3)

Carried forward unchanged from Wave 3. Not modified during tree construction.

| Field | Type | Description |
|---|---|---|
| `cycle_path` | array of strings | Ordered list of canonical `dedupKey` ids forming the loop, starting and ending at the repeated node. |
| `detected_at` | string (enum) | `"W1"` = detected during per-document extraction. `"W3"` = detected after cross-document deduplication. |
| `source` | string | Source document(s) where the cycle was detected. |

---

### `conflicts[]` — ownership percentage conflicts (passed through from Wave 3)

Carried forward unchanged from Wave 3. Wave 5 consumes `resolved_value` to get the authoritative percentage for IBO/UBO calculations; the raw `value_a`/`value_b` are preserved for audit.

| Field | Type | Description |
|---|---|---|
| `owner` | string | Canonical `dedupKey` of the owning entity. |
| `owned` | string | Canonical `dedupKey` of the owned entity. |
| `conflict_description` | string | Human-readable description of the conflict. |
| `value_a` | string | First (or highest, for 3+ values) conflicting percentage. |
| `source_a` | string | Document where `value_a` was found. |
| `value_b` | string | Second (or lowest, for 3+ values) conflicting percentage. |
| `source_b` | string | Document where `value_b` was found. |
| `resolution_strategy` | string (enum) | Rule used to derive `resolved_value`. `use_higher` / `use_lower` / `use_most_recent` / `manual`. |
| `resolved_value` | string | The single percentage value Wave 5 should use. Never empty. |

---

### `metadata`

| Field | Type | Description |
|---|---|---|
| `schema_version` | string | Always `"v0"`. This schema is subject to change once the internal Wave 5 system specification is finalised. |
| `note` | string | Always `"Schema subject to change pending internal Wave 5 system spec."` |
