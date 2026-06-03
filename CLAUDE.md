# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

All commands must be run from the project root with the venv active:

```bash
source .venv/bin/activate
```

**Run all tests:**
```bash
pytest tests/ -v
```

**Run a single test:**
```bash
pytest tests/test_wave1.py::test_prompt_runner_import -v
```

**Run schema validation tests only (no API key needed):**
```bash
pytest tests/test_schemas.py tests/test_wave1.py::test_prompt_runner_import tests/test_wave2.py::test_wave2_runner_import tests/test_wave3.py::test_wave3_runner_import -v
```

**Run integration tests (requires GEMINI_API_KEY in .env):**
```bash
pytest tests/ -v -k "not import"
```

**Install dependencies:**
```bash
pip install -r requirements.txt
```

## Architecture

This repo implements a 4-wave KYC ownership extraction pipeline using Gemini single-shot prompts. Each wave has three files in its directory: `system.txt` (role + rules), `user.txt` (task instruction with `{placeholder}` syntax), and `jsonSchema.txt` (Draft7 JSON Schema the model must conform to).

**Wave pipeline:**
- **Wave 0** (non-AI, external): downloads documents from internal system → GCS bucket
- **Wave 1** (`wave1/`): takes a GCS document path + client entity name → extracts per-document ownership graph
- **Wave 2** (`wave2/`): takes all Wave 1 JSON outputs → merges into one unified graph, deduplicates entities, flags conflicts
- **Wave 3** (`wave3/`): takes merged graph + target entity name → extracts only the upward ownership chain, tagging terminal nodes as UBO (natural person) or IBO (listed/regulated entity)

**`runner/prompt_runner.py`** — `PromptRunner(wave_dir)` loads the three prompt files and exposes `run(**placeholders) -> dict`. Calls Gemini `gemini-2.0-flash` with `response_mime_type="application/json"`. Requires `GEMINI_API_KEY` in `.env`.

**`runner/schema_validator.py`** — `validate(data, schema_path) -> list[str]` validates a dict against a `jsonSchema.txt` file using `jsonschema.Draft7Validator`. Returns empty list if valid.

**Tests** in `tests/test_wave*.py` are API-gated via `@_needs_api` decorator — they skip when `GEMINI_API_KEY` is absent. `tests/test_schemas.py` always runs (no API needed). Fixtures in `tests/fixtures/` contain synthetic ownership structures used as test inputs.

## Key Design Decisions

**Node schema:** Every node across all waves has `id` (slug), `parent_id` (slug or null), `name`, `type`, `layer`, `ownership_percentage_direct` (string — exact value, `"Not Available"`, or `"CONFLICT — see conflicts array"`), `control`, `source`, `listing_proof` (non-null only for Listed Company type), `data_gaps`, `exceptions`.

**Conflicts are never resolved** — Wave 2 surfaces them in a top-level `conflicts` array and sets the node's `%` to `"CONFLICT — see conflicts array"`. Wave 3 carries them forward unchanged.

**Wave 3 traversal** stops at Natural Person (→ UBO) or Listed/Regulated/Government entity (→ IBO). Nodes at or above 25% direct ownership get `significant_owner: true`.

**Prompt modifications:** When editing `wave*/user.txt`, preserve all `{placeholder}` tokens — they are filled at runtime by `PromptRunner.build_user_prompt()`. The Wave 1 user prompt has both `{gcs_document_path}` (production) and `{document_text}` (used in tests to pass inline text).

## Design Docs

- Spec: `docs/superpowers/specs/2026-06-03-ownership-prompts-design.md`
- Implementation plan: `docs/superpowers/plans/2026-06-03-ownership-prompts.md`
