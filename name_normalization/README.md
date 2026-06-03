# Wave 2 — Name Normalization

## What this prompt does

Takes all Wave 1 per-document ownership graphs and standardises the entity and person names within each document independently. It adds four normalization fields to every node — a canonical form of the name, a deduplication key, and an ASCII variant of that key — without touching any other field and without merging nodes across documents.

Wave 2 is strictly one-in-one-out: one Wave 1 document goes in, one normalized document comes out. Cross-document merging happens in Wave 3 using the `dedupKey` and `asciiDedupKey` fields produced here.

**Inputs:**
- `{{wave1OutputsJson}}` — array of all Wave 1 JSON outputs (one per source document)

**Output:** a single JSON object containing a `normalized_documents` array — one entry per input document, in the same order

---

## Key behaviours

- **No merging**: processes each document in isolation. A node that appears in three documents remains three separate nodes in the output.
- **No modification of Wave 1 fields**: `id`, `name`, `type`, `layer`, `listing_proof`, `data_gaps`, and `exceptions` are carried forward byte-for-byte. The `name` field in particular is never altered — it is the raw audit trail from Wave 1.
- **Edges, cycles, and conflicts passed through unchanged**: Wave 2 only enriches nodes. All other arrays are carried forward exactly as received.
- **Client-blind**: Wave 2 does not receive `{{clientEntityName}}`. It processes the graph structure uniformly — the client entity is identifiable as the node with `layer = 0`.
- **Two normalization pipelines**: natural persons (type `Natural Person`) go through a personal name pipeline that splits the name into title, first, middle, and last name components. All other entity types go through a legal entity pipeline that strips suffixes and generates a slug-style key.

---

## Response schema

### Top-level

| Field | Type | Description |
|---|---|---|
| `normalized_documents` | array | One entry per Wave 1 input document. Same order as the input. Each entry is a complete normalized document. |

---

### `normalized_documents[]` — per-document wrapper

Each entry mirrors the Wave 1 document structure, with nodes enriched by normalization fields.

| Field | Type | Description |
|---|---|---|
| `client_entity` | string | Carried forward unchanged from Wave 1. Legal name of the target entity. |
| `entity_type_category` | string (enum) | Carried forward unchanged from Wave 1. KYC risk category. |
| `source_document` | string | Carried forward unchanged from Wave 1. Source document identifier. |
| `nodes` | array | All nodes from this document, each enriched with normalization fields. See `nodes[]` below. |
| `edges` | array | Ownership edges carried forward **unchanged** from Wave 1. No field is modified. |
| `cycles` | array | Cycle entries carried forward **unchanged** from Wave 1. |
| `conflicts` | array | Conflict entries carried forward **unchanged** from Wave 1. |

For field-level documentation of `edges`, `cycles`, and `conflicts`, see the [Wave 1 README](../ownership_extraction/README.md) — these arrays are identical in structure and not modified here.

---

### `nodes[]` — normalized entity nodes

Every Wave 1 node field is preserved. Four normalization fields are added to all nodes. Natural persons get four additional name-component fields.

**Fields carried forward from Wave 1 (unchanged):**

| Field | Type | Description |
|---|---|---|
| `id` | string | Original Wave 1 slug id. Never modified by Wave 2. |
| `name` | string | Legal name exactly as extracted in Wave 1. **Never modified** — this is the raw audit trail. |
| `type` | string (enum) | Entity classification from Wave 1. Unchanged. |
| `layer` | integer | Distance from client entity. Unchanged. |
| `listing_proof` | object or null | Listing evidence for listed companies. Unchanged. |
| `data_gaps` | string or null | Missing-information notes from Wave 1. Unchanged. |
| `exceptions` | string or null | Structural anomaly notes from Wave 1. Unchanged. |

**Normalization fields added by Wave 2 (all nodes):**

| Field | Type | Nullable | Description |
|---|---|---|---|
| `normalizedName` | string | No | The canonical form of the entity name after normalization. For legal entities: legal suffixes stripped, formatting standardised. For persons: formatted as `"LASTNAME, Firstname Middlename"` (surname-first). This is the human-readable normalised version. |
| `dedupKey` | string | No | A lowercase slug derived from `normalizedName`, used as the primary matching key in Wave 3. Spaces become underscores, special characters stripped. Two entities with the same `dedupKey` are treated as the same entity. E.g. `"alpha_holdings"`. |
| `asciiDedupKey` | string | No | ASCII-transliterated version of `dedupKey` — accented characters replaced with their unaccented equivalents (e.g. `"müller"` → `"muller"`). Used as the fallback matching key in Wave 3 when `dedupKey` contains non-ASCII characters. |
| `normalizationNote` | string | Yes | Explanation of any non-obvious normalization decision made for this node — e.g. why a particular alias or abbreviation was chosen. Null if no special decision was made. |

**Name-component fields added for Natural Person nodes only:**

| Field | Type | Nullable | Description |
|---|---|---|---|
| `firstName` | string | Yes | Given name(s). Null for non-persons. |
| `middleName` | string | Yes | Middle name(s) if present. Null for non-persons or persons without a middle name. |
| `lastName` | string | Yes | Family/surname. Null for non-persons. |
| `personalTitle` | string | Yes | Title or honorific (e.g. `"Dr"`, `"Sir"`). Null for non-persons or persons without a documented title. |

> For all non-person entity nodes, `firstName`, `middleName`, `lastName`, and `personalTitle` are always `null`.
