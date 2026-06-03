# Wave 1 — Ownership Extraction

## What this prompt does

Takes a single KYC source document and a target client entity name, and extracts the complete ownership graph for that entity from the document. It traces ownership layer by layer from the client upward — who owns the client, who owns those owners, and so on — until all known owners are exhausted or a cycle is detected.

This is a per-document agent. If ten documents mention the same client, Wave 1 runs ten times and produces ten independent graphs. Cross-document merging happens in Wave 3.

**Inputs:**
- `{{clientEntityName}}` — legal name of the target entity (layer 0)
- `{{documentPath}}` — GCS path or name of the source document to extract from

**Output:** a single JSON object matching `schema.json`

---

## Key behaviours

- **Evidence-only**: never invents or infers ownership relationships. Every edge must be directly stated in the document.
- **No threshold**: extracts all layers, not just up to a fixed depth.
- **Exact percentages**: records ownership % exactly as written. Never rounds or estimates. Uses `"Not Available"` if the document omits a percentage.
- **Target not found (W1.R6)**: if the client entity is not mentioned at all in the document, outputs a single layer-0 node with null fields and a `data_gap` message. Returns empty `edges`, `cycles`, and `conflicts` arrays.
- **Cycle detection (W1.R7)**: maintains an ancestry path while traversing. If entity X's id appears in the current path before its owners are extracted, records a cycle entry and stops — does not loop infinitely.
- **Conflict handling**: if the same `(owner, owned)` relationship appears with two different percentages within this document, records both in `conflicts` with `resolution_strategy: "use_higher"` and sets `resolved_value` to the higher value.

---

## Response schema

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `client_entity` | string | Legal name of the target entity. Matches the `{{clientEntityName}}` input. |
| `entity_type_category` | string (enum) | KYC risk category assigned to the client entity based on documentary evidence. See values below. |
| `source_document` | string | GCS path or document name this graph was extracted from. |
| `nodes` | array | All ownership entities found in this document. One node per entity — identity only, no relationship data. |
| `edges` | array | All ownership relationships found in this document. One edge per directional ownership link. |
| `cycles` | array | Cyclic ownership loops detected during extraction. Empty if no cycles found. |
| `conflicts` | array | Ownership percentage disagreements within this document. Empty if none found. |

### `entity_type_category` values

| Value | Meaning |
|---|---|
| `Category 1 - Private/Regulated/Listed` | Standard company — private, regulated, or publicly listed |
| `Category 2 - Adverse Media` | Entity with adverse media or elevated risk indicators |
| `Category 3 - SPV/Trust/Charity/Foundation` | Special purpose or non-profit structure |
| `Entity Type not confirmed` | Insufficient evidence to classify |

---

### `nodes[]` — entity identity

Each node represents one entity mentioned in the ownership structure. Relationship data (ownership %, control, source) lives on edges, not nodes.

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Slug identifier: the entity's name in lowercase with spaces replaced by underscores. E.g. `"alpha_holdings_ltd"`. Used as the reference key in edges, cycles, and conflicts. |
| `name` | string | No | Legal name exactly as it appears in the document. Never normalised or cleaned — that happens in Wave 2. |
| `type` | string (enum) | No | Entity classification. See type values below. |
| `layer` | integer (≥0) | No | Distance from the client entity. `0` = the client itself, `1` = direct owners of the client, `2` = owners of layer-1 entities, and so on. |
| `listing_proof` | object or null | Yes | Present only for Listed Companies. Contains exchange, ticker, ISIN, and evidence source. Null for all other entity types. |
| `data_gaps` | string | Yes | Free-text note describing missing or incomplete information for this entity. Null if none. E.g. `"Ownership percentage not stated in document."` |
| `exceptions` | string | Yes | Free-text note for structural anomalies. Set to `"Cycle detected: see cycles array."` when this node is the repeated node in a detected cycle. Null otherwise. |

**`type` values:**

| Value | Description |
|---|---|
| `Listed Company` | Publicly traded on a recognised exchange |
| `Private Company` | Privately held company |
| `Regulated Entity` | Bank, insurer, fund, or other regulated financial entity |
| `Natural Person` | Individual human being |
| `SPV` | Special purpose vehicle |
| `Trust` | Trust structure |
| `Charity` | Charitable organisation |
| `Foundation` | Foundation |
| `Partnership` | General or limited partnership |
| `Government Entity` | State-owned or government body |
| `Branch` | Branch office of a foreign entity |
| `Unknown` | Type cannot be determined from the document |

**`listing_proof` sub-fields** (present only when not null):

| Field | Type | Description |
|---|---|---|
| `exchange` | string | Name or code of the stock exchange (e.g. `"LSE"`, `"NYSE"`) |
| `ticker` | string | Exchange ticker symbol (e.g. `"HSBA"`) |
| `isin` | string | International Securities Identification Number |
| `proof_source` | string | Exact page/section of the document where listing evidence was found |

---

### `edges[]` — ownership relationships

Each edge represents a single directional ownership link: one entity owns a stake in another. A node with two owners will have two edges pointing to it.

| Field | Type | Nullable | Description |
|---|---|---|---|
| `owner` | string | No | The `id` of the entity that holds the ownership stake. |
| `owned` | string | No | The `id` of the entity being owned. |
| `ownership_percentage_direct` | string | No | The direct ownership percentage as stated verbatim in the document (e.g. `"60%"`). `"Not Available"` if the document does not state a percentage. `"CONFLICT — see conflicts array"` if two different values appear for this edge (see conflicts below). |
| `control` | string | Yes | Any evidence of control beyond ownership percentage — e.g. voting rights, board appointment rights, veto rights. Null if not mentioned. |
| `source` | string | No | Exact document name and page or section where this ownership relationship is stated (e.g. `"doc1.pdf p.3"`). |

---

### `cycles[]` — detected ownership loops

A cycle occurs when an entity appears in its own ownership chain (e.g. A owns B, B owns C, C owns A). Wave 1 detects cycles during extraction by tracking the ancestry path.

| Field | Type | Description |
|---|---|---|
| `cycle_path` | array of strings | Ordered list of entity `id`s forming the loop, starting and ending at the repeated node. E.g. `["company_a", "company_b", "company_c", "company_a"]`. |
| `detected_at` | string (enum) | Always `"W1"` for cycles detected during extraction. (Value `"W3"` is reserved for cycles discovered after cross-document deduplication in Wave 3.) |
| `source` | string | The source document in which this cycle was detected. |

---

### `conflicts[]` — intra-document percentage disagreements

A conflict is recorded when the same ownership relationship (same `owner` + `owned` pair) appears with two different percentage values within this single document. Both values are preserved for audit; a resolution strategy determines which value Wave 5 should use.

| Field | Type | Description |
|---|---|---|
| `owner` | string | The `id` of the owning entity in the conflicting relationship. |
| `owned` | string | The `id` of the owned entity in the conflicting relationship. |
| `conflict_description` | string | Human-readable description of the conflict (e.g. `"Ownership % differs: 60% on p.2 vs 55% on p.4"`). |
| `value_a` | string | First conflicting percentage value (e.g. `"60%"`). |
| `source_a` | string | Document location where `value_a` was found (e.g. `"doc1.pdf p.2"`). |
| `value_b` | string | Second conflicting percentage value (e.g. `"55%"`). |
| `source_b` | string | Document location where `value_b` was found (e.g. `"doc1.pdf p.4"`). |
| `resolution_strategy` | string (enum) | Rule applied to derive `resolved_value`. Always `"use_higher"` for intra-document conflicts at Wave 1. |
| `resolved_value` | string | The value that Wave 5 (IBO/UBO identification) should use. Derived by applying `resolution_strategy` to `value_a` and `value_b`. The raw values are never modified. |

**`resolution_strategy` values:**

| Value | Meaning |
|---|---|
| `use_higher` | Use the larger of the two percentage values |
| `use_lower` | Use the smaller of the two percentage values |
| `use_most_recent` | Use the value from the more recently dated source |
| `manual` | Cannot be resolved automatically — requires human review |
