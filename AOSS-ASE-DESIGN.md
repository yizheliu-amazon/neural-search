# ASE (Automatic Semantic Enrichment) in AOSS

> **Scope.** This document covers how ASE via the `semantic` field
> (`language` / `model_type` parameters) works in **AOSS (Amazon OpenSearch
> Serverless)**, and how it differs from the managed-cluster (AOS) integration.
>
> This design combines two decisions already taken:
>
> 1. **AOSS Semantic Field — Option B (MD-centric).** A lightweight
>    `systemIngestPipelineConfig` is pre-computed at write time and stored in the
>    **PhysicalIndex DynamoDB table** owned by the Metadata Service (MD); the
>    Indexing Coordinator (IC) reads it from its existing metadata fetch and
>    builds the system ingest pipeline from it.
> 2. **ASE — Option 3 (OSS `language`/`model_type` + managed patch, no separate
>    plugin/library).** The entire mechanism — the `SemanticModelResolver`
>    interface, the default `PretrainedSemanticModelResolver`, the
>    `language`/`model_type` parameters, validation, and transformer integration
>    — lives in **OSS neural-search**. The managed service
>    (`AWSOpenSearchNeuralSearchPlugin`) is a **source fork** that vendors OSS
>    neural-search via AutoSync. The managed patch is exactly **one new file**
>    (`ManagedSemanticModelResolver.java`, ~50 lines) plus a **one-line swap** in
>    `NeuralSearch.getMappingTransformers()`. There is **no separate library, no
>    separate plugin, and no separate package** — the managed resolver lives
>    inside the managed neural-search source as a patch file.

---

## 1. TL;DR

In AOSS, a customer creates an index with a `semantic` field parameterized by
`language` and `model_type` (instead of an explicit `model_id`). AOSS uses two
combined decisions to make this work:

- **Option B (MD-centric):** a lightweight `systemIngestPipelineConfig` is
  pre-computed and stored in the **PhysicalIndex DynamoDB table**. IC reads it
  from the metadata fetch it already performs on the write path — no new
  round-trip, no mapping parse, no model resolution on the hot path.
- **Option 3 (OSS resolver + managed patch):** all of the mechanism ships in OSS
  neural-search behind a `SemanticModelResolver` interface. The managed
  neural-search source fork adds a single patch file,
  `ManagedSemanticModelResolver.java`, and swaps it in via one line of
  `NeuralSearch.getMappingTransformers()`. For AOSS, that managed resolver
  **returns hardcoded global model IDs instantly** — no registration, no
  deployment, no ML-Commons round-trip. Because the resolved `model_id` is a
  constant, the whole `semantic_info` sub-field and the ingest transformation are
  a pure function of the mapping.

This is what lets CreateIndex + config extraction happen at metadata write time
from the mapping alone. There is **no separate library or plugin** — the managed
resolver is a patch file inside the managed neural-search source. Phase 1
delivers CreateIndex + ingest + search; Phase 2 extends to PutMapping /
PutTemplate + auto-create.

---

## 2. AOSS vs AOS

Both share the identical OSS mechanism: the `SemanticModelResolver` interface,
`language`/`model_type` params, validation, and transformer integration all live
in OSS neural-search, and both AOSS and AOS run a managed source fork that swaps
in `ManagedSemanticModelResolver`. They differ only in what that one resolver
does and where the transform runs.

| Concern | AOS (managed clusters) | AOSS (serverless) |
|---|---|---|
| Managed patch | Same one file (`ManagedSemanticModelResolver.java`) + one-line swap in the source fork | Same one file + one-line swap in the source fork |
| What `ManagedSemanticModelResolver.resolve()` does | **Registers** a model that points at the global OASis model via a connector, then returns that `model_id` | **Returns a hardcoded global model_id constant instantly** — no registration, no deployment |
| Where the transform runs | Neural-search `semantic` mapper + ingest transformer run **in-process on the cluster node** at mapping-parse / ingest time; the node has the full mapping | **No node with the full mapping on the write path.** IC uses `skipMappings=true`; the authoritative mapping lives in MD (DynamoDB). Transform runs on IC at CreateIndex, output baked into `systemIngestPipelineConfig` |
| Model deployment cost | One-time registration per `(language, model_type)` | Zero — global model IDs are pre-provisioned constants |
| Ingest pipeline | Standard cluster-state system ingest pipeline attaches on the node | Pre-computed `systemIngestPipelineConfig` stored in PhysicalIndex DDB; IC materializes it from constants |

**Net for AOSS:** because the managed resolver returns a *constant*, the whole
semantic expansion is static, so it fits the config-in-DDB (Option B) path: IC
resolves the constant once at CreateIndex, persists a `systemIngestPipelineConfig`,
and rebuilds the pipeline from that config on every write.

---

## 3. CreateIndex Flow (Phase 1)

CreateIndex flows `SGW → IC → (transform) → MD (store)`. IC runs the managed
neural-search source fork, so its `SemanticMappingTransformer` invokes the
patched `ManagedSemanticModelResolver`.

```
Customer ──(SigV4)──► SGW ──► IC
                                │  1. finalize mapping (semantic field with language/model_type)
                                │  2. SemanticMappingTransformer runs (OSS code)
                                │  3. ManagedSemanticModelResolver.resolve(language, model_type)
                                │       └─► returns HARDCODED global model_id  [constant, instant]
                                │  4. build semantic_info sub-field from constants
                                │       (no getModel(), no registration, no OASis call)
                                ▼
                               MD  ── store expanded mapping (SearchIndex metadata)
                                   ── extract + store systemIngestPipelineConfig (PhysicalIndex DDB)
```

Step by step:

1. **SGW** routes the CreateIndex request to IC (routing unchanged; only the
   request body must be allowlisted to carry `language`/`model_type`).
2. **IC** finalizes the mapping and runs the OSS `SemanticMappingTransformer`
   from the neural-search source fork.
3. The transformer calls `ManagedSemanticModelResolver.resolve(language,
   model_type)`, which **returns a hardcoded global model_id** — a constant map
   lookup. No model registration, no deployment, no ML-Commons round-trip.
4. Using that constant, the transformer builds the `semantic_info` sub-field
   (processor type, field map, chunking, dimension, etc.) entirely from
   constants.
5. IC hands the expanded mapping to **MD**, which stores it as `SearchIndex`
   metadata and **extracts `systemIngestPipelineConfig`** into the `PhysicalIndex`
   DynamoDB record.

The whole path is **instant** — there is no model registration or deployment
anywhere in the request flow.

---

## 4. `systemIngestPipelineConfig`

A **lightweight, self-contained descriptor** (not a full OpenSearch pipeline
object) that carries everything IC needs to reconstruct the inference processor
on the write path without parsing mappings or resolving models. It is extracted
from the expanded mapping at CreateIndex time and stored in the **PhysicalIndex
DynamoDB table**.

Per semantic field, it carries:

```jsonc
{
  "version": 1,
  "semantic_fields": [
    {
      "field": "product_description",                    // semantic field path
      "semantic_info_field_name": "product_description_semantic_info",
      "processor_type": "text_embedding",                // or sparse_encoding, per model_type
      "model_id": "<HARDCODED_GLOBAL_MODEL_ID>",         // constant from ManagedSemanticModelResolver
      "model_type": "dense",                             // echoed from mapping
      "language": "en",                                  // echoed from mapping
      "raw_field_type": "text",
      "chunking": { ... },                               // if configured
      "skip_existing_embedding": false,
      "field_map": { "product_description": "product_description_semantic_info.embedding" }
    }
  ]
}
```

Key points:

- **The model_id is a baked-in constant.** Because `ManagedSemanticModelResolver`
  returns a hardcoded global model ID, the `model_id` is known at CreateIndex
  time and written into the config verbatim. IC never resolves `(language,
  model_type)` and never calls a resolver on the hot path.
- **Storage location — PhysicalIndex DDB.** MD extracts the config during the
  metadata store and writes it onto the `PhysicalIndex` record. IC picks it up
  through the **existing** `GetBatchPhysicalIndex` fetch it already makes to
  resolve physical indices on the write path — no new fetch is introduced.
- **Why PhysicalIndex rather than the user pipeline table:** the system ingest
  pipeline is derived per-index from the mapping, is not user-managed, and must
  be co-resolved with the physical index IC is already fetching — so it rides on
  the PhysicalIndex metadata IC already reads.
- **`language`/`model_type` are echoed** into the config for observability and to
  make it self-describing; they are also stored in the mapping and returned
  verbatim by GetIndex (see §7).

---

## 5. Ingest Flow

On the write path (`_bulk` / `_doc`), IC does **not** parse mappings and does
**not** resolve models — it consumes the pre-computed config.

```
Customer ──► SGW ──► IC
                      │ 1. resolve pIndex (cluster-state cache / GetBatchPhysicalIndex)
                      │ 2. read systemIngestPipelineConfig from cached PhysicalIndex metadata
                      │ 3. SemanticFieldProcessorFactory builds SemanticFieldProcessor
                      │      from the config (constant model_id, field_map)
                      │ 4. SemanticFieldProcessor runs inference ───► OASis (global managed model)
                      │ 5. prepend system pipeline before any user pipeline
                      ▼
                     Indexing Worker  (writes enriched doc)
```

1. IC resolves the target collection / sIndex / physical index via its
   cluster-state cache, falling back to `GetBatchPhysicalIndex` on MD (existing
   behavior).
2. IC reads `systemIngestPipelineConfig` from the **already-cached** PhysicalIndex
   metadata (rides the existing fetch; no extra round-trip).
3. `SemanticFieldProcessorFactory` materializes a `SemanticFieldProcessor` from
   the config: for each `semantic_fields[]` entry it constructs the inference
   processor (`text_embedding` for dense / `sparse_encoding` for sparse) wired
   with the **constant `model_id`** and `field_map`. This is direct construction
   from constants — no cluster-state pipeline resolution, no mapper re-parse.
4. `SemanticFieldProcessor` calls **OASis** (ML-Commons) with the hardcoded
   global `model_id` to generate embeddings. Only *inference* touches OASis;
   *resolution* was done at CreateIndex and baked into the config.
5. The system pipeline is prepended to any user-specified ingest pipeline (system
   enrichment first, as in OSS `systemIngestPipeline` semantics), then the
   enriched docs go to the Indexing Worker.

> **`skip_existing_embedding`.** If set in the mapping, IC honors it the same way
> the OSS system pipeline does (skip inference when the target `semantic_info`
> sub-field is already populated on an update). The flag is carried in the config.

---

## 6. Search Flow

**No AOSS-specific search change is required.**

SC stores mappings (`storeMappings=true`), so it already holds the full expanded
mapping — including the semantic field and its hardcoded global `model_id`. At
query time:

```
Customer ──► SGW ──► SC
                      │ 1. NeuralQueryBuilder reads model_id straight from the stored mapping
                      │ 2. encode query text ───► OASis (global managed model)
                      │ 3. run knn / rank_features query against semantic_info sub-field
                      ▼
                     results
```

- `NeuralQueryBuilder` reads the `model_id` directly from the stored mapping and
  calls **OASis** to encode the query text into a vector, then runs the
  knn/rank_features query against the `semantic_info` sub-field — **identical to
  any semantic field with an explicit `model_id`**.
- Because the resolved model is a constant already present in the stored mapping,
  SC resolves nothing on the hot path. SC runs the managed neural-search source
  fork only so its query rewrite reads the managed `model_id`.

---

## 7. GetIndex

GetIndex returns the mapping **stored** by MD (`SearchIndex` metadata) — **no
reconstruction is needed**. The transform persisted `language`, `model_type`, and
the resolved `model_id` as `SemanticFieldMapper` parameters (MD's lightweight
delegating mapper serializes semantic params in `toXContent`), so the GET
response round-trips the semantic field's declared `language`/`model_type` plus
its expanded `semantic_info` sub-field verbatim. This matches OSS/AOS behavior:
the client sees exactly what was stored.

---

## 8. PutMapping (Phase 2 only)

PutMapping and PutTemplate flow `SGW → MD directly`, bypassing IC entirely — so
the IC-side transform in Phase 1 never sees them.

- **Phase 1:** semantic fields on PutMapping/PutTemplate are **not supported** —
  the existing MD semantic-field validator accepts semantic fields only on direct
  CreateIndex. This is a deliberate Phase-1 boundary, not a regression.
- **Phase 2:** MD embeds a **slim transform module** (a cut-down neural-search
  source fork carrying only the semantic mapper + `ManagedSemanticModelResolver`).
  Because `ManagedSemanticModelResolver.resolve()` returns a hardcoded global
  `model_id`, the same static expansion runs on MD with **no OASis call** — MD
  produces `semantic_info` + `systemIngestPipelineConfig` locally and persists
  both.

```
Customer ──► SGW ──► MD  (bypasses IC)          [Phase 2]
                      │ slim transform module:
                      │   ManagedSemanticModelResolver.resolve() → hardcoded model_id [constant]
                      │   static expansion → semantic_info + systemIngestPipelineConfig
                      ▼
                     store expanded mapping + config
```

---

## 9. Component Changes

| Component | Phase 1 | Phase 2 |
|---|---|---|
| **SGW** | Allowlist the semantic-field CreateIndex body (`language`/`model_type`); routing unchanged | None |
| **IC** | Run managed neural-search source fork; on CreateIndex, `SemanticMappingTransformer` + `ManagedSemanticModelResolver` resolve the hardcoded `model_id`, build `semantic_info` + `systemIngestPipelineConfig` (no `getModel()`/OASis); on write path, read config from cached PhysicalIndex metadata, build `SemanticFieldProcessor`, prepend system pipeline, run inference against OASis | None (reads same metadata shape) |
| **MD** | Store `language`/`model_type` in mapping; extract + persist `systemIngestPipelineConfig` on `PhysicalIndex` DDB and return it via `GetBatchPhysicalIndex`; extend semantic-field validator to accept `language`/`model_type` | Embed slim transform module (semantic mapper + `ManagedSemanticModelResolver`) + Template Resolve API to handle PutMapping/PutTemplate/auto-create locally |
| **SC** | Run managed neural-search source fork so query rewrite reads the hardcoded managed `model_id` from the stored mapping; no functional change beyond that | None |
| **OASis** | Pre-provision the global managed models at the constant IDs `ManagedSemanticModelResolver` returns; serve inference only | Serve inference for template/auto-created indices; no registration path exercised |

---

## 10. Phasing

### Phase 1 — CreateIndex + ingest + search
Deliverable end-to-end **with no MD→OASis network path**, because the managed
resolver returns constants:
- IC runs the managed neural-search source fork; static expansion at CreateIndex.
- MD stores `language`/`model_type` in the mapping and extracts
  `systemIngestPipelineConfig` onto PhysicalIndex; returns it via
  `GetBatchPhysicalIndex`.
- IC builds + runs the `SemanticFieldProcessor` from the config on the write path.
- SC serves search from the stored mapping's constant `model_id`.
- OASis pre-provisions the global managed models for supported `(language,
  model_type)` pairs.
- **Boundaries:** semantic fields only via CreateIndex (not PutMapping/template);
  only `(language, model_type)` pairs with pre-provisioned global models; up to 10
  semantic fields per index.

### Phase 2 — PutMapping / PutTemplate + auto-create
- Deploy the slim transform module on MD so PutMapping/PutTemplate (which bypass
  IC) run the same static expansion and persist the config locally.
- Template Resolve API on MD: run the static expansion against the resolved
  template so `systemIngestPipelineConfig` exists **before** the first write into
  an auto-created index.

---

## 11. Why hardcoded model IDs enable Phase 1 without MD→OASis

This is the key insight of the whole design.

Because `ManagedSemanticModelResolver.resolve()` **returns a hardcoded global
model ID constant** (a map lookup, not a call to OASis), the semantic expansion
is a **pure function of the mapping**:

- **The transform never calls OASis.** Resolving `(language, model_type)` is a
  constant lookup, so IC's `SemanticMappingTransformer` completes CreateIndex
  with no ML-Commons round-trip.
- **`systemIngestPipelineConfig` is fully computable at write time.** The
  `model_id` is a constant, so the config is complete the moment CreateIndex runs
  — nothing is left to resolve later.
- **The entire CreateIndex + config-extraction path works even though MD has no
  OASis connectivity.** Neither IC (during transform) nor MD (during extraction)
  needs to reach OASis, because there is nothing to resolve — only a constant to
  copy. The single runtime OASis dependency is *inference* at ingest/query time,
  which AOSS already operates for existing neural / K-NN offerings.

That is exactly what makes Phase 1 deliverable immediately: the hard,
cross-network MD→OASis work is not on the critical path. If AOSS ever supported
non-static semantic fields (a customer-supplied `model_id` requiring
`getModel()`), *that* path would need MD→OASis — but the managed
`(language, model_type)` case stays constant-only and OASis-free for resolution.

---

## 12. Open Questions

1. **Global model catalog & versioning.** The `(language, model_type) → model_id`
   mapping is a constant table inside `ManagedSemanticModelResolver.java`. How do
   we roll a new managed model version without breaking indices whose
   `systemIngestPipelineConfig` baked in the old ID? (Likely: the baked config is
   the source of truth for existing indices; new indices pick up the new
   constant — but ingest and query for a given index must stay on the same ID.
   Confirm.)
2. **Config staleness on model rotation.** If a global model is re-provisioned at
   a new ID, existing indices keep the old ID in their persisted config. Confirm
   OASis retains old global model IDs for the lifetime of indices using them, or
   define a re-materialization story.
3. **Query/ingest model symmetry.** SC's query-side and IC's ingest-side
   `model_id` for the same `(language, model_type)` must always agree (embeddings
   must be produced and searched with the same model). Both derive from the same
   `ManagedSemanticModelResolver` constant table in the shared managed source
   fork — that shared table is the guarantee, but confirm both components vendor
   the same fork revision.
4. **PhysicalIndex rollover.** On rollover (time-series / size), confirm the new
   PhysicalIndex inherits `systemIngestPipelineConfig` (the extraction layer must
   copy it forward, analogous to how shard config is carried forward on warm
   transition).
5. **GET mapping fidelity.** Confirm `language`/`model_type` round-trip unchanged
   in the GET mapping response (MD's delegating mapper must serialize the new
   params in `toXContent`).
6. **AutoSync patch stability.** The managed fork carries exactly one patch file
   (`ManagedSemanticModelResolver.java`) + a one-line swap in
   `NeuralSearch.getMappingTransformers()`. Confirm AutoSync conflict surface
   stays minimal as OSS neural-search evolves the `SemanticModelResolver`
   interface — the one-line swap is the only OSS-file touch and must track any
   `getMappingTransformers()` signature change.
