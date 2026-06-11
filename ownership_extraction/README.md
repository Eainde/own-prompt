# Wave 1 — Ownership Extraction

## What this prompt does

Takes a single KYC source document and extracts a **complete, flat directed ownership graph** of every entity and every ownership relationship visible in the document — in both directions, with no target entity and no direction filtering. Grounding to a client entity is a downstream agent's responsibility.

This is a per-document agent. If ten documents mention the same entities, Wave 1 runs ten times and produces ten independent graphs. Cross-document merging happens in Wave 3 (Merge+Comparison). Client grounding happens in Agent 5.

**Two sources fused into one graph:**
- **chart-sourced edges** — derived mechanically from `chart_structure` (the upstream Chart Reader artifact). The model never re-reads the chart visually.
- **text-sourced edges** — read directly from non-chart document text: shareholder registers, cap tables, certificates, prose. Each carries a page/section citation.

**Inputs:**
- `{{gcsDocumentPath}}` — GCS path or name of the source document
- `{{chartStructure}}` — chart_structure artifact from the upstream Chart Reader (may be empty)
- `{{criticFeedback}}` — feedback from a previous critic pass (empty string on first run)

**Output:** a single JSON object matching `schema.json`

---

## Key behaviours

- **No target, no layer, no ownership_chain**: the agent outputs every entity in the document as a flat node list. No layer numbers. No client anchor. No upward/downward filtering.
- **Both directions**: all ownership edges are extracted as stated in the document. Subsidiaries are included alongside parent owners.
- **Evidence-only**: never invents or infers ownership relationships. Every edge must be directly evidenced.
- **No threshold**: extracts all relationships including 0% owners.
- **Exact percentages**: records ownership % exactly as written. Uses `"Not Available"` if the document omits a percentage.
- **source_type on every edge**: `"chart_structure"` for edges derived from Chart Reader arrows; `"document_text"` for edges read from non-chart text.
- **Structural cycle detection (W1.R7)**: after building all edges, runs DFS over the flat directed edge graph. Back-edges are recorded in `cycles` with `detected_at: "W1"`.
- **No person-owns-person (W1.R9)**: no edge may have both `owner` and `owned` resolve to Natural Person nodes.
- **Branch isolation (W1.R10)**: when chart_structure is provided, each holding entity's shareholders are derived strictly by filtering `chart_structure.arrows` for entries where `arrow.to == holding`. No shareholder may be assigned to a holding unless an explicit arrow backs it. W1.R12 supersedes the visual branch-table step of W1.R10 when chart_structure is non-empty.
- **Chart-vs-text reconciliation (W1.R13)**: when both sources describe the same `(owner, owned)` relationship, union on existence; percentage delta ≤ 2% takes the text value (no conflict entry); delta > 2% → `"CONFLICT — see conflicts array"` on the edge and a conflict entry with `resolution_strategy: "deferred"`.
- **Conflicts are deferred**: W1 never resolves percentage disagreements. All conflicts carry `resolution_strategy: "deferred"` and `resolved_value: "deferred"`. The downstream Conflict Detection agent (Agent 4) performs resolution.

---

## Response schema

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `source_document` | string | GCS path or document name this flat ownership graph was extracted from. |
| `nodes` | array | All ownership entities found in this document. One node per entity — identity only, no relationship data. |
| `edges` | array | All ownership relationships found in this document. One edge per directional ownership link. |
| `cycles` | array | Structural cycles detected by DFS over the flat directed edge graph. Empty if no cycles found. |
| `conflicts` | array | Ownership percentage disagreements within this document. Empty if none found. |

There is no `client_entity` field and no top-level `entity_type_category` field.

---

### `nodes[]` — entity identity

Each node represents one entity mentioned in the ownership structure. Relationship data (ownership %, control, source) lives on edges, not nodes.

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Slug identifier: the entity's name in lowercase with spaces replaced by underscores. E.g. `"alpha_holdings_ltd"`. Used as the reference key in edges, cycles, and conflicts. |
| `name` | string | No | Legal name exactly as it appears in the document. Never normalised or cleaned — that happens in Wave 2. |
| `type` | string (enum) | No | Entity classification. See type values below. |
| `entity_type_category` | string (enum) | No | Per-node KYC risk category assigned from documentary evidence. See values below. |
| `listing_proof` | object or null | Yes | Present only for Listed Companies. Contains exchange, ticker, ISIN, and evidence source. Null for all other entity types. |
| `data_gaps` | string | Yes | Free-text note describing missing or incomplete information for this entity. Null if none. |
| `exceptions` | string | Yes | Free-text note for structural anomalies. Set to `"Cycle detected: see cycles array."` when this node is the repeated node in a detected cycle. Null otherwise. |
| `classification_reasoning` | string | No | Entity type reasoning plus the document page/section evidencing this entity. Does not include layer or owner/subsidiary claims — those are grounding-agent outputs. |

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

**`entity_type_category` values (per node):**

| Value | Meaning |
|---|---|
| `Category 1 - Private/Regulated/Listed` | Standard company — private, regulated, or publicly listed |
| `Category 2 - Adverse Media` | Entity with adverse media or elevated risk indicators |
| `Category 3 - SPV/Trust/Charity/Foundation` | Special purpose or non-profit structure |
| `Entity Type not confirmed` | Insufficient evidence to classify |

**`listing_proof` sub-fields** (present only when not null):

| Field | Type | Description |
|---|---|---|
| `exchange` | string | Name or code of the stock exchange (e.g. `"LSE"`, `"NYSE"`) |
| `ticker` | string | Exchange ticker symbol (e.g. `"HSBA"`) |
| `isin` | string | International Securities Identification Number |
| `proof_source` | string | Exact page/section of the document where listing evidence was found |

---

### `edges[]` — ownership relationships

Each edge represents a single directional ownership link: one entity holds a stake in another. Edges are extracted in both directions — neither subsidiaries nor parent entities are filtered out.

| Field | Type | Nullable | Description |
|---|---|---|---|
| `owner` | string | No | The `id` of the entity that holds the ownership stake. |
| `owned` | string | No | The `id` of the entity being owned. |
| `ownership_percentage_direct` | string | No | The direct ownership percentage as stated verbatim in the document (e.g. `"60%"`). `"Not Available"` if the document does not state a percentage. `"CONFLICT — see conflicts array"` if two different values appear for this edge. |
| `control` | string | Yes | Any evidence of control beyond ownership percentage — e.g. voting rights, board appointment rights, veto rights. Null if not mentioned. |
| `source` | string | No | Exact document name and page or section where this ownership relationship is stated (e.g. `"doc1.pdf p.3"`). |
| `source_type` | string (enum) | No | Provenance: `"chart_structure"` for edges derived from Chart Reader arrows; `"document_text"` for edges read from non-chart text. |
| `direction_proof` | string | No | For chart_structure edges: `"From chart_structure arrow: '<evidence>'"`. For document_text edges: the page/section text proving direction. |

---

### `cycles[]` — detected ownership loops

A cycle occurs when an entity appears in its own ownership chain (e.g. A owns B, B owns C, C owns A). Wave 1 detects cycles by running DFS over the flat directed edge graph after all edges are built.

| Field | Type | Description |
|---|---|---|
| `cycle_path` | array of strings | Ordered list of entity `id`s forming the loop, starting and ending at the repeated node. E.g. `["company_a", "company_b", "company_c", "company_a"]`. |
| `detected_at` | string (enum) | Always `"W1"` for cycles detected during extraction. (`"W3"` is reserved for cycles discovered during cross-document merge.) |
| `source` | string | The source document in which this cycle was detected. |

---

### `conflicts[]` — intra-document percentage disagreements

A conflict is recorded when the same ownership relationship (same `owner` + `owned` pair) appears with two different percentage values within this single document — including chart-vs-text disagreements greater than 2%. Wave 1 records but never resolves conflicts.

| Field | Type | Description |
|---|---|---|
| `owner` | string | The `id` of the owning entity in the conflicting relationship. |
| `owned` | string | The `id` of the owned entity in the conflicting relationship. |
| `conflict_description` | string | Human-readable description of the conflict (e.g. `"Ownership % conflict: 60% vs 55% for [owner] → [owned]"`). |
| `value_a` | string | First conflicting percentage value (e.g. `"60%"`). |
| `source_a` | string | Document location where `value_a` was found (e.g. `"doc1.pdf p.2"`). |
| `value_b` | string | Second conflicting percentage value (e.g. `"55%"`). |
| `source_b` | string | Document location where `value_b` was found (e.g. `"doc1.pdf p.4"`). |
| `resolution_strategy` | string (enum) | Always `"deferred"` at Wave 1 — resolution is performed by the downstream Conflict Detection agent (Agent 4). |
| `resolved_value` | string | Always `"deferred"` at Wave 1. |

**`resolution_strategy` enum values** (Agent 4 uses these; Wave 1 always emits `"deferred"`):

| Value | Meaning |
|---|---|
| `use_higher` | Use the larger of the two percentage values |
| `use_lower` | Use the smaller of the two percentage values |
| `use_most_recent` | Use the value from the more recently dated source |
| `manual` | Cannot be resolved automatically — requires human review |
| `deferred` | Not yet resolved — Wave 1 always sets this |
