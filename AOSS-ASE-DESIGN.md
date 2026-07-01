# ASE (Automatic Semantic Enrichment) in AOSS

> **Scope.** This section covers *only* how ASE via the `semantic` field
> (`language` / `model_type` parameters) works differently in **AOSS (Amazon
> OpenSearch Serverless)**. The general ASE data-plane design (the
> `SemanticModelResolver` abstraction, the `opensearch-model-provider` library,
> the managed neural-search fork) is defined in the parent doc and is
> summarized here only where AOSS diverges from it. The domains/managed-cluster
> (AOS) integration is also covered in the parent doc.
>
> This design combines two decisions already taken:
> 1. **AOSS Semantic Field — Option B (MD-centric).** A lightweight
>    `systemIngestPipelineConfig` is pre-computed on the Metadata Service (MD)
>    at write time and stored as index metadata; the Indexing Coordinator (IC)
>    reads it from its existing metadata fetch and builds the system ingest
>    pipeline from it.
> 2. **ASE DP — Option 3 (Model Provider Library).** A pluggable
>    `SemanticModelResolver` in OSS neural-search, with a
>    `ManagedSemanticModelResolver` (in `opensearch-model-provider`) that returns
>    hard-coded managed model IDs for a `(language, model_type)` pair and
>    reports `providesStaticExpansion() == true` so `semantic_info` can be built
>    from constants with no `getModel()` / ML-Commons round-trip.

---

## 1. TL;DR

In AOSS, a customer creates an index with a `semantic` field parameterized by
`language` and `model_type` (instead of an explicit `model_id`). The
`ManagedSemanticModelResolver` maps that pair to a **managed, pre-deployed model
id** — a constant, resolved without any registration or deployment and without
calling ML-Commons. Because the managed resolver reports
`providesStaticExpansion() == true`, the entire `semantic_info` sub-field and
the ingest transformation (which processor, which field, which model id) are
**pure functions of the mapping** — no live model lookup is needed.

This static property is what makes semantic field fit the MD-centric (Option B)
model cleanly: MD can compute a `systemIngestPipelineConfig` at CreateIndex time
from the mapping alone (no OASis/ML-Commons call), persist it as index metadata,
and IC reconstructs the pipeline from that config on the write path. The managed
model id is embedded in the config as a constant, so IC never needs to resolve a
model itself.

Phase 1 delivers this for **CreateIndex** only (matching the MD semantic-field
validator, which already accepts semantic fields on CreateIndex only). Phase 2
extends it to PutMapping/PutTemplate once the MD→OASis network path and a slim
transform module on MD exist.

---

## 2. Why AOSS is different from AOS

| Concern | AOS (managed clusters) | AOSS (serverless) |
|---|---|---|
| Where the transform runs | The neural-search `semantic` mapper + the ingest transformer run **in-process on the cluster node** at mapping-parse and ingest time. The node has the full mapping and can call ML-Commons directly. | There is **no node with the full mapping on the write path**. IC uses `skipMappings=true` and keeps only a slim cluster-state cache; the authoritative mapping lives in MD (DynamoDB). |
| Model resolution | `ManagedSemanticModelResolver` runs on the cluster and returns the managed model id inline; `providesStaticExpansion()` lets it skip `getModel()`. | The managed model id must be resolved **once, at write time, on the component that owns the mapping (MD / IC)** and then *baked into* index metadata (`systemIngestPipelineConfig`) so the hot write path never resolves a model. |
| Ingest pipeline plumbing | Standard cluster ingest pipeline resolved from cluster state; auto-generated system pipeline attaches on the node. | No cluster state, no node-local pipeline registry. Pipelines are stored in MD's `PipelineConfig` DDB table and cached on the coordinators; the *system* ingest pipeline for a semantic field must be pre-computed and distributed the same way. |
| OASis reachability | ML-Commons is co-located (in-cluster). | ML-Commons runs in **OASis**, reachable from OASis-connected components only. In today's topology **MD has no OASis network path** (Phase 2 opens it). |

**Net:** AOS uses the transformer directly at parse/ingest time; AOSS must take
the *config-in-DDB* path — resolve the managed model to a constant once, persist
a `systemIngestPipelineConfig`, and rebuild the pipeline from that config on IC.
The `providesStaticExpansion()` property of the managed resolver is precisely
what makes the AOSS path viable without opening MD→OASis in Phase 1.

---

## 3. The three blocking issues (recap) and how ASE interacts with each

From the Option B design, three issues block semantic field in AOSS. ASE with
`language`/`model_type` interacts with each as follows:

1. **No mapping on IC at ingest time (IC uses `skipMappings=true`).**
   IC cannot look at the mapping to discover that a field is `semantic`, let
   alone which processor/model to run. → Solved by having MD publish the
   pre-computed `systemIngestPipelineConfig` as index metadata, which IC already
   fetches (see §5). ASE adds nothing here beyond ensuring the config carries the
   managed model id constant.

2. **No transform on PutMapping/PutTemplate (goes directly to MD, which has no
   OASis).** For AOS, the neural-search mapper transform (build `semantic_info`,
   resolve model) runs on the node. In AOSS, PutMapping/PutTemplate hit MD
   directly and MD cannot call OASis. → With `providesStaticExpansion()`, the
   `semantic_info` expansion and model-id resolution are **static** (constants),
   so MD can perform the expansion locally **without** an OASis call. This is why
   ASE-via-managed-models is deliverable on MD earlier than the general
   arbitrary-`model_id` case.

3. **Pipeline resolved before auto-create, no templates on IC.** For auto-created
   indices the ingest pipeline is resolved before the index (and its mapping)
   exists. → Phase 2 concern; addressed by a Template Resolve API on MD that runs
   the same static expansion against the resolved template so the
   `systemIngestPipelineConfig` is available before the first write.

---

## 4. Where `ManagedSemanticModelResolver` runs in AOSS

`ManagedSemanticModelResolver` (from `opensearch-model-provider`, swapped in via
the managed neural-search fork's one-line patch) is deployed wherever the
`semantic` mapper's transform logic executes. In AOSS that is **not** on the hot
write path — it runs at *metadata write time*:

| Path | Component running the resolver | When |
|---|---|---|
| **CreateIndex** (Phase 1) | **Indexing Coordinator (IC)** — during the CreateIndex flow, before the final mapping is handed to MD for storage. IC runs the managed neural-search fork; the resolver maps `(language, model_type) → managed model_id` (constant) and, via `providesStaticExpansion()`, builds `semantic_info` and the `systemIngestPipelineConfig` without any `getModel()` / OASis call. | Once, at CreateIndex. |
| **PutMapping / PutTemplate** (Phase 2) | **Metadata Service (MD)** — a slim transform module (a cut-down neural-search fork carrying only the semantic mapper + `ManagedSemanticModelResolver`) runs the same static expansion on the mapping/template MD receives directly. | Once, at PutMapping/PutTemplate. |

Because the managed resolver is *static*, both call sites run **identical,
side-effect-free** logic. They only ever produce constants; neither registers,
deploys, nor invokes a model. This is the crucial property that lets the same
resolver live in two different components without either needing OASis
connectivity.

> **Alternative considered:** running the resolver on MD in Phase 1 too (so
> there is a single home for the transform). Rejected for Phase 1 because MD
> does not yet embed a neural-search fork and the CreateIndex path already flows
> **SGW → IC → (transform) → MD (store)** — IC is the natural transform point
> and already runs the neural-search plugin fork. PutMapping/PutTemplate go
> **SGW → MD directly**, bypassing IC entirely, which is exactly why they must
> wait for the MD-side transform module in Phase 2.

---

## 5. `systemIngestPipelineConfig` — contents and model id

The `systemIngestPipelineConfig` is a **lightweight, self-contained descriptor**
(not a full OpenSearch pipeline object) pre-computed by the transform and stored
as index metadata. For ASE it **must include the resolved managed model id as a
constant**, plus everything else IC needs to reconstruct the processor without
any further resolution.

Per semantic field, the config carries:

```jsonc
{
  "version": 1,
  "semantic_fields": [
    {
      "field": "product_description",              // the semantic field path
      "semantic_info_field_name": "product_description_semantic_info",
      "processor_type": "text_embedding",          // or sparse_encoding, per model_type
      "model_id": "<MANAGED_MODEL_ID_CONSTANT>",   // resolved by ManagedSemanticModelResolver
      "model_type": "dense",                        // echoed from mapping
      "language": "en",                             // echoed from mapping
      "raw_field_type": "text",
      "chunking": { ... },                          // if configured
      "field_map": { "product_description": "product_description_semantic_info.embedding" }
    }
  ]
}
```

Key points:

- **The model id is a baked-in constant.** Because `providesStaticExpansion()`
  is true for the managed resolver, the model id is known at transform time and
  written into the config. IC never resolves `(language, model_type)` itself and
  never calls a resolver on the hot path.
- **Storage location.** In Phase 1 the config is stored on the
  **`PhysicalIndex`** metadata (the extraction layer described in Option B writes
  it into the PhysicalIndex record after CreateIndex), so IC picks it up through
  its existing `GetBatchPhysicalIndex` fetch — the same call it already makes to
  resolve physical indices on the write path. No new fetch is introduced.
  - *Rationale for PhysicalIndex over the `PipelineConfig` DDB table:* the
    `PipelineConfig` table is keyed `account_id:collection_id` / `pipeline_type`
    and is designed for *user-defined* pipelines that coordinators cache and
    refresh via the notification cron. The **system** ingest pipeline is derived
    per-index from the mapping, is not user-managed, and must be co-resolved with
    the physical index IC is already fetching — so it rides on the PhysicalIndex
    metadata rather than the user pipeline table. (If a future need arises to let
    the config be edited/observed independently, it can be promoted to a
    dedicated `pipeline_type` in `PipelineConfig`; not needed for ASE.)
- **`language`/`model_type` are stored in the mapping** (MD already persists
  arbitrary semantic params via its lightweight delegating `SemanticFieldMapper`)
  and are **echoed into the config** for observability and to make the config
  self-describing. They are also returned verbatim in the GET mapping response
  (see §6).
- **No embeddings config leakage.** The config carries only what IC needs to
  attach the inference processor; the raw-field behavior (`raw_field_type`) is
  already handled by MD's delegating mapper.

---

## 6. How IC builds the system ingest pipeline from the config

On the write path (`_bulk` / `_doc`), IC does **not** parse mappings and does
**not** resolve models. It:

1. Resolves the target collection/sIndex/physical index via its cluster-state
   cache, falling back to `GetBatchPhysicalIndex` on MD (existing behavior).
2. Reads `systemIngestPipelineConfig` from the fetched PhysicalIndex metadata
   (new — but rides the existing fetch; no extra round-trip).
3. If present, **materializes an in-memory system ingest pipeline** from the
   config: for each `semantic_fields[]` entry it constructs the corresponding
   inference processor (`text_embedding` for dense / `sparse_encoding` for
   sparse `model_type`) wired with the **constant `model_id`** and the
   `field_map` from the config. This is a direct construction from constants —
   no `IngestService` cluster-state pipeline resolution, no neural-search mapper
   re-parse.
4. Prepends the system pipeline to any user-specified ingest pipeline (system
   enrichment runs first, exactly as in OSS `systemIngestPipeline` semantics),
   then forwards the enriched documents to the Indexing Worker.
5. The inference call itself (embedding generation) targets the **managed model
   in OASis** through the existing ML-Commons inference path used for K-NN /
   neural search in serverless. Only *inference* touches OASis — *resolution* was
   already done at write time and baked into the config.

Because the model id is a constant in the config, IC's pipeline construction is
**deterministic and OASis-independent for resolution**; the only OASis
dependency at runtime is the embedding inference call, which AOSS already
supports for existing neural offerings.

> **Idempotency / `skip_existing_embedding`.** If the mapping sets
> `skip_existing_embedding`, IC honors it the same way the OSS system pipeline
> does (skip inference when the target `semantic_info` sub-field is already
> populated on an update). This flag is carried in the config.

---

## 7. Component-by-component changes

### Indexing Coordinator (IC) — *Phase 1, primary*
- Runs the managed neural-search fork with `ManagedSemanticModelResolver`
  swapped in (one-line patch, per Option 3).
- **CreateIndex transform:** after the mapping is finalized, run the static
  semantic expansion — resolve `(language, model_type) → managed model_id`, build
  `semantic_info`, emit `systemIngestPipelineConfig`. Hand both the expanded
  mapping and the config to MD to store.
- **Write path:** read `systemIngestPipelineConfig` from PhysicalIndex metadata;
  materialize + prepend the system ingest pipeline from constants; run inference
  against the managed model in OASis.
- No mapping storage change (IC stays `skipMappings=true`); it only *consumes*
  the config.

### Metadata Service (MD)
- **Phase 1:** extend the extraction layer to persist `systemIngestPipelineConfig`
  onto the `PhysicalIndex` record after CreateIndex, and return it through
  `GetBatchPhysicalIndex`. MD already registers the `semantic` field type via its
  lightweight delegating mapper and already stores semantic params (`model_id`,
  `raw_field_type`, `semantic_info_field_name`, `chunking`, …) — extend the
  stored param set to include `language` and `model_type`, and extend the
  semantic-field validator (`validateSemanticFieldTypeEnabled`) to accept
  `language`/`model_type` (and to require that either an explicit `model_id`
  *or* a resolvable `(language, model_type)` pair is present).
- **Phase 2:** embed the **slim transform module** (semantic mapper +
  `ManagedSemanticModelResolver`) so MD can run the static expansion itself for
  **PutMapping / PutTemplate** (which bypass IC). Requires the MD→OASis network
  path *only if* non-static (arbitrary `model_id`) semantic fields are ever
  supported on these paths; for managed `(language, model_type)` the expansion
  stays static and no OASis call is needed even on MD.
- Continue enforcing `MAX_SEMANTIC_FIELDS_PER_INDEX = 10` and the
  version/flag/collection-type gates already in place.

### Search Coordinator (SC)
- SC already stores mappings and services search. For semantic-field **query**
  time, the `neural`/`semantic` query clause resolves the query-side model the
  same way: with `providesStaticExpansion()`, the `search_model_id` (or the
  `(language, model_type)`-derived managed query model) is a constant available
  from the mapping SC already holds. SC needs the managed neural-search fork so
  its query rewrite picks the managed model id.
- No `systemIngestPipelineConfig` on SC (that is ingest-only); SC's concern is
  that the stored mapping round-trips `language`/`model_type` and the derived
  query model id.

### OASis (ML-Commons)
- **Must pre-deploy the managed models** referenced by every supported
  `(language, model_type)` pair, at the constant model ids the
  `ManagedSemanticModelResolver` returns. This is the single hard dependency: the
  resolver hands out ids that OASis must already be serving.
- No registration/deploy API is exercised at CreateIndex/PutMapping — only
  inference at write/query time. The managed-model deployment is an
  operational/provisioning task, not part of the request flow.

### Service Gateway (SGW)
- No functional change to routing. Must ensure the semantic-field CreateIndex
  request body (with `language`/`model_type`) is on the allowlist for the create
  path (same allowlisting mechanism used for neural/pipeline features).

---

## 8. Request flows

### CreateIndex with a semantic field (Phase 1)

```
Customer ──(SigV4)──► SGW ──► IC
                                │  1. finalize mapping
                                │  2. ManagedSemanticModelResolver:
                                │       (language, model_type) → managed model_id  [constant]
                                │  3. providesStaticExpansion(): build semantic_info
                                │       + systemIngestPipelineConfig  [no getModel(), no OASis]
                                ▼
                               MD  ── store expanded mapping (SearchIndex)
                                   ── store systemIngestPipelineConfig on PhysicalIndex
```

### Ingest (`_bulk`) into a semantic-field index

```
Customer ──► SGW ──► IC
                      │ 1. resolve pIndex (cluster-state cache / GetBatchPhysicalIndex)
                      │ 2. read systemIngestPipelineConfig from PhysicalIndex metadata
                      │ 3. materialize system ingest pipeline from constants
                      │ 4. run inference processor  ─────────────► OASis (ML-Commons managed model)
                      │ 5. prepend system pipeline before user pipeline
                      ▼
                     Indexing Worker  (writes enriched doc)
```

### PutMapping adding a semantic field (Phase 2 only)

```
Customer ──► SGW ──► MD  (bypasses IC)
                      │ slim transform module:
                      │   ManagedSemanticModelResolver → managed model_id [constant]
                      │   static expansion → semantic_info + systemIngestPipelineConfig
                      ▼
                     store expanded mapping + config
```

*(In Phase 1, PutMapping adding a semantic field remains **rejected** by the
existing MD validator — semantic fields are accepted only on direct CreateIndex,
matching current behavior. This is a deliberate Phase-1 boundary, not a
regression.)*

---

## 9. Phasing

### Phase 1 — CreateIndex, managed models only
Deliverable end-to-end with **no MD→OASis network path**, because managed model
resolution is static:
- IC runs the managed neural-search fork; static expansion at CreateIndex.
- MD stores `language`/`model_type` in the mapping and
  `systemIngestPipelineConfig` on PhysicalIndex; returns it via
  `GetBatchPhysicalIndex`.
- IC builds + runs the system ingest pipeline from the config on the write path.
- OASis pre-deploys the managed models for supported `(language, model_type)`
  pairs.
- Query-side: SC resolves the managed query model from the stored mapping.
- **Boundaries:** semantic fields only via CreateIndex (not PutMapping/template);
  only managed `(language, model_type)` pairs OASis has pre-deployed; up to 10
  semantic fields per index.

### Phase 2 — PutMapping / PutTemplate + auto-create
- Deploy the slim transform module on MD so PutMapping/PutTemplate (which bypass
  IC) can run the same static expansion and persist the config.
- Open the MD→OASis network path — required only if/when **non-static** semantic
  fields (arbitrary customer `model_id`, needing `getModel()`) are supported;
  managed `(language, model_type)` remains static and OASis-free for resolution
  even here.
- Template Resolve API on MD: run the static expansion against the resolved
  template so `systemIngestPipelineConfig` exists **before** the first write into
  an auto-created index (solves blocking issue #3).

---

## 10. Why `providesStaticExpansion()` is the linchpin

Everything AOSS-specific above hinges on the managed resolver being *static*:

- **No OASis on the metadata write path (Phase 1)** — expansion is constants, so
  IC (and later MD) never call ML-Commons at CreateIndex/PutMapping.
- **`systemIngestPipelineConfig` is fully computable at write time** — the model
  id is a constant, so the config is complete and IC never resolves anything on
  the hot path.
- **Same resolver in two homes (IC and MD)** — because it is side-effect-free and
  deterministic, running it on IC (Phase 1) and MD (Phase 2) yields identical
  output with no coordination.
- **Cheap and reliable** — no dependency on ML-Commons availability for
  create/put; the only runtime OASis dependency is inference, which AOSS already
  operates for existing neural/K-NN offerings.

If we ever support non-managed semantic fields in AOSS (customer-supplied
`model_id` that requires `getModel()`), `providesStaticExpansion()` returns
false and that path **does** require the MD→OASis connection — which is exactly
the Phase 2 network work, kept out of the critical path for the managed
launch.

---

## 11. Open questions / risks

1. **Managed model catalog & versioning.** The `(language, model_type) →
   model_id` map is a constant table in `opensearch-model-provider`. How do we
   roll a new managed model version without breaking indices whose
   `systemIngestPipelineConfig` baked in the old id? (Likely: the baked config is
   the source of truth for existing indices; new indices pick up the new
   mapping — but ingest and query must stay on the *same* model id for a given
   index. Confirm.)
2. **Config staleness on model rotation.** If a managed model is redeployed at a
   new id, existing indices keep the old id in their persisted config. Confirm
   that OASis retains old managed model ids for the lifetime of indices using
   them, or define a re-materialization story.
3. **Query/ingest model symmetry.** Ensure SC's query-side managed model id and
   IC's ingest-side managed model id for the same `(language, model_type)` always
   agree (dense embeddings must be produced and searched with the same model).
   Both derive from the same resolver, but they run in different components — a
   shared constant table in `opensearch-model-provider` is the guarantee.
4. **PhysicalIndex rollover.** On rollover (time-series / size), confirm the new
   PhysicalIndex inherits `systemIngestPipelineConfig` (the extraction layer must
   copy it forward, analogous to how shard config is carried forward on warm
   transition).
5. **GET mapping fidelity.** Confirm `language`/`model_type` round-trip
   unchanged in the GET mapping response (MD's delegating mapper must serialize
   the new params in `toXContent`).
