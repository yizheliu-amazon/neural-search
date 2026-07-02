# ASE Data Plane Support — Full High-Level Design

**Author:** yizheliu
**Date:** 2026-07-01
**Status:** Draft (for team review)
**Scope:** AOSS (Serverless) + AOS (Managed Provisioned) data plane, with open-source neural-search alignment
**Related docs:** [ASE DP Decision Document](https://quip-amazon.com/LtuGAXbMUU6Y) · [ASE DP Tech Design (semantic_enrichment)](https://quip-amazon.com/Bc25Anm0Vbqj) · [Semantic Field in AOSS Design](https://chorus.aws.dev/doc/AxrMMnQmVykO) · [Approach comparison](https://chorus.aws.dev/doc/3pkPc89HLJRl)

> **What this document is.** This is the consolidated HLD for enabling Automatic Semantic Enrichment (ASE) through the OpenSearch data plane API. It supersedes the base HLD by carrying over everything from it (Sections 1–5) and adding two new capabilities that were previously out of scope:
>
> 1. **`text ↔ semantic` field type update** — enabling/disabling ASE on *existing* fields of a live index (Section 6).
> 2. **Automatic `match` query rewrite** — a system-generated search request processor that transparently converts lexical `match` queries against semantic fields into `neural` / `neural_sparse` (optionally hybrid) queries (Section 7).
>
> This document is self-contained; a reader does not need the original HLD.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Decision: `semantic` field type (not `semantic_enrichment`)](#2-decision-semantic-field-type-not-semantic_enrichment)
3. [Current State: How the Semantic Field Works in Open Source](#3-current-state-how-the-semantic-field-works-in-open-source)
4. [Proposed ASE Solution — `language` / `model_type` (Option 3: `SemanticModelResolver` + managed patch)](#4-proposed-ase-solution--languagemodel_type-option-3-semanticmodelresolver--managed-patch)
5. [Model Resolution: Open-Source vs Managed Paths](#5-model-resolution-open-source-vs-managed-paths)
6. [**NEW:** `text ↔ semantic` Field Type Update (Enable / Disable ASE)](#6-new-text--semantic-field-type-update-enable--disable-ase)
7. [**NEW:** Automatic `match` Query Rewrite (System-Generated Search Request Processor)](#7-new-automatic-match-query-rewrite-system-generated-search-request-processor)
8. [Component Changes Summary (all features combined)](#8-component-changes-summary-all-features-combined)
9. [Phasing / Timeline](#9-phasing--timeline)
10. [Diff from the Existing HLD](#10-diff-from-the-existing-hld)
11. [Open Questions](#11-open-questions)

---

## 1. Problem Statement

Automatic Semantic Enrichment (ASE) currently requires customers to use the AWS SDK control plane API (`create-index`, `update-index`, `get-index`) to configure semantic enrichment on index fields. Customers cannot use the OpenSearch data plane API (`PUT /index`) via `opensearch-py`, `opensearch-js`, or any OpenSearch-compatible SDK.

This forces customers to maintain two SDK integrations — the OpenSearch SDK for data operations, and the AWS SDK for index creation — solely because ASE configuration is only available through the control plane. This is a significant adoption barrier for teams whose existing tooling, IaC, and workflows are built around the OpenSearch SDK.

**Goal:** Give customers a 1-click experience for enabling semantic enrichment through the OpenSearch data plane API only — for both **creating** semantically enriched fields and, as newly scoped here, **toggling** enrichment on existing fields, and **querying** them with a plain `match` query.

---

## 2. Decision: `semantic` field type (not `semantic_enrichment`)

The prior tech design proposed using `semantic_enrichment` as a parameter on `type: text` fields. We have decided to use the **open-source `semantic` field type** instead, extended with `language` and `model_type` parameters.

**Why `semantic` field over `semantic_enrichment`:**

1. **Open-source alignment** — the `semantic` field is already released in OpenSearch 3.x. Honoring it avoids fragmenting the API surface between self-managed and managed service.
2. **Typed-client support** — `SemanticProperty` already exists in the OpenSearch client specs. `semantic_enrichment` would require raw-JSON workarounds until a spec change is upstreamed.
3. **No pipeline artifacts** — the `semantic` field uses a `MapperPlugin` pattern (system ingest processor + query rewrite). No explicit ingest/search pipelines are created or exposed to customers.
4. **Enable / disable is expressed by the type itself** — `type: "semantic"` = ASE enabled; `type: "text"` = ASE disabled. The `status: ENABLED/DISABLED` toggle from `semantic_enrichment` is redundant. (If a status parameter is ever still required, a `semantic` field with `status: "DISABLED"` is treated exactly as `type: "text"`.) **This is precisely what makes the newly-scoped `text ↔ semantic` update in Section 6 the natural mechanism for enabling/disabling ASE on an existing field.**
5. **Only 12 active `semantic_enrichment` customers** — it is easier to deprecate `semantic_enrichment` (managed-service-only, small blast radius) than to fork `semantic`, which is used across open source.
6. **Same underlying machinery** — both approaches ultimately call `MLCommonsClientAccessor.inferenceSentences()` for embeddings and `NeuralQueryBuilder` / `NeuralSparseQueryBuilder` for search. The `semantic` field already wires this up.

---

## 3. Current State: How the Semantic Field Works in Open Source

### 3.1 CreateIndex

Customer provides `model_id` explicitly:

```json
PUT /my-index
{
  "mappings": {
    "properties": {
      "body": {
        "type": "semantic",
        "model_id": "customer-registered-model-abc"
      }
    }
  }
}
```

**What happens:**

1. `TransportCreateIndexAction` calls `MappingTransformerRegistry.applyTransformers()`.
2. `SemanticMappingTransformer` (neural-search) runs:
   - Validates `model_id` is present (throws `IllegalArgumentException` if null).
   - Calls `mlClientAccessor.getModels({modelId})` to fetch `MLModel` metadata (dimension, space_type, algorithm).
   - `SemanticInfoConfigBuilder` builds the companion field config (`knn_vector` with dimension/method for dense, or `rank_features` for sparse).
   - Injects a `body_semantic_info` field into the mapping (as a sibling of `body`).
3. `MapperService.merge()` parses the expanded mapping — `SemanticFieldMapper` handles `body`, `KNNVectorFieldMapper` handles `body_semantic_info.embedding`.
4. Mapping is stored in cluster state.

### 3.2 GetIndex

```
GET /my-index
```

Returns the stored mapping directly — includes `body` (`type: semantic`, `model_id`) and `body_semantic_info` (the expanded companion field).

### 3.3 UpdateMapping (PutMapping)

```json
PUT /my-index/_mapping
{
  "properties": {
    "new_field": {
      "type": "semantic",
      "model_id": "some-model"
    }
  }
}
```

Same flow as CreateIndex — `MappingTransformerRegistry` runs and the transformer expands the new field. **Note:** open-source today only supports *adding new* semantic fields via PutMapping; changing an *existing* field's type between `text` and `semantic` is rejected by `SemanticFieldMapper.merge()`. Section 6 addresses exactly this gap.

### 3.4 Ingest (document indexing)

`SemanticFieldProcessor` (a system ingest processor, auto-created from the mapping):

- Reads `model_id` from the field config.
- Chunks text if chunking is enabled.
- Calls `MLCommonsClientAccessor.inferenceSentences(modelId, texts)` to generate embeddings.
- Writes embeddings into `body_semantic_info.embedding`.

### 3.5 Search (query)

`NeuralQueryBuilder` / `NeuralSparseQueryBuilder`:

- Detects `SemanticFieldType` on the target field.
- Reads `model_id` (or `search_model_id`) from the field's `SemanticParameters`.
- Encodes the query text into a vector via ML Commons.
- Rewrites to a KNN query (dense) or sparse-token query (sparse) against the `semantic_info.embedding` field.

**Key limitation:** the customer must register the model themselves and provide `model_id`. There is no managed model selection, and today the customer must also *manually* issue a `neural` / `neural_sparse` query (Section 7 removes that requirement).

---

## 4. Proposed ASE Solution — `language`/`model_type` (Option 3: `SemanticModelResolver` + managed patch)

### 4.1 Customer API

```json
PUT /my-index
{
  "mappings": {
    "properties": {
      "body": {
        "type": "semantic",
        "language": "ENGLISH",
        "model_type": "SPARSE"
      }
    }
  }
}
```

No `model_id` needed. The system resolves a managed model automatically.

### 4.2 Parameters

- `language` — optional, defaults to `ENGLISH`. Values: `ENGLISH`, `MULTI-LINGUAL`.
- `model_type` — optional, defaults to `SPARSE`. Values: `SPARSE`, `DENSE`.
- If neither `model_id` nor `language`/`model_type` is specified → default to English sparse.
- If `model_id` / `dense_embedding_config` is specified alongside `language` or `model_type` → **400** conflict error.
- If `DENSE` + `MULTI-LINGUAL` → **400** error (not supported).

### 4.3 GetIndex response includes `language`/`model_type`

```json
{
  "body": {
    "type": "semantic",
    "model_id": "<auto-resolved>",
    "raw_field_type": "text",
    "language": "ENGLISH",   // null when customer supplied model_id manually (same as current OSS experience)
    "model_type": "SPARSE"
  }
}
```

### 4.4 Core insight — mechanism vs policy

The `language`/`model_type` capability is **not** entirely AWS-proprietary. Separate it into two parts:

- **The *mechanism*** — the `language`/`model_type` parameters, their validation, conflict rules, defaulting (English/sparse), the "skip validation when no `model_id`" transformer behavior, and the `SemanticInfoConfigBuilder` expansion. None of this needs to be AWS-specific. Any OpenSearch user benefits from "describe what you want, not which model."
- **The *policy*** — *which concrete model* a given `language`/`model_type` resolves to. This is the only part that differs between open source and the managed service:
  - **Open source:** resolve to a **pretrained model** from the model hub (e.g. `opensearch-neural-sparse-encoding-v2-distill`), register it, deploy it on demand.
  - **Managed service:** resolve to a **pre-deployed managed model** (a constant, already loaded fleet-wide), skipping deployment entirely.

So we move *all* the mechanism into neural-search (open source) behind an interface, ship the open-source default resolver, and keep a **tiny** managed patch that contributes only the managed resolution policy.

This is **Option 3** — chosen over Option 1 (a separate ~14 KB closed-source ASE plugin) and Option 2 (all ASE logic embedded in the managed neural-search fork) because it is the only option that upstreams the "no `model_id` needed" experience to open source, keeps the closed-source footprint to one file plus a one-line swap, and is AutoSync-friendly (purely additive). *(Option 1/2 remain viable interim fallbacks if the OSS interface stalls community review — see Section 9.)*

### 4.5 Architecture

**neural-search (open source) — owns all the logic**

- `SemanticFieldMapper` / `SemanticParameters`: add `language` and `model_type` (stored in mapping, returned in GET). Upstreamed.
- `SemanticMappingUtils`: when `model_id` is absent but `language`/`model_type` present, do **not** throw; route to the resolver. Validation and conflict rules live here. Upstreamed.
- `SemanticMappingTransformer`: when there is no `model_id`, call the injected `SemanticModelResolver` to obtain a `model_id`, then run the existing `SemanticInfoConfigBuilder` expansion. Downstream (ingest/search/GET) unchanged.
- **New extension point (in open-source neural-search):**

  ```java
  public interface SemanticModelResolver {
      /**
       * Resolve a language/model_type request into a concrete, deployed model_id.
       * Implementations may register+deploy on demand (open source) or return a
       * model id pointing to a pre-deployed global model (managed service).
       */
      void resolve(String language, String modelType, ActionListener<String> listener);

      /** Build the companion (_semantic_info) field config for the resolved model. */
      Map<String, Object> buildSemanticInfoConfig(String language, String modelType, String modelId);

      /** Shared validation/conflict rules (English/sparse defaults, DENSE+MULTI-LINGUAL rejection). */
      void validate(String language, String modelType);
  }
  ```

- **Default open-source implementation — `PretrainedSemanticModelResolver`:** maps `language`/`model_type` → pretrained hub model name, registers it via ML Commons (`deploy=true`), polls until DEPLOYED, calls `getModel()` for dimension/space_type, returns the resolved `model_id`. This is what self-managed OpenSearch uses out of the box — the feature works with zero AWS dependency.

**Managed neural-search patch (`AWSOpenSearchNeuralSearchPlugin`)**

1. `ManagedSemanticModelResolver.java` (~50 lines, one new file):
   - **AOSS:** returns hard-coded managed global model IDs instantly — no registration, no network call.
   - **AOS:** registers a model that points at the global model on OASIS via a connector.
   - Builds `_semantic_info` from known constants (AOSS) or via `getModel()` (AOS).
2. A one-line swap in `NeuralSearch.getMappingTransformers()`:

   ```java
   // Open source:
   semanticMappingTransformer.setModelResolver(new PretrainedSemanticModelResolver(mlClient));
   // Managed (the only change):
   semanticMappingTransformer.setModelResolver(new ManagedSemanticModelResolver(mlClient));
   ```

### 4.6 What lives where

| Component | Location | Open/Closed |
| --- | --- | --- |
| `SemanticModelResolver` interface | neural-search OSS | Open source |
| `PretrainedSemanticModelResolver` | neural-search OSS | Open source |
| `language`/`model_type` on `SemanticFieldMapper` | neural-search OSS | Open source |
| Resolver integration in `SemanticMappingTransformer` | neural-search OSS | Open source |
| Skip-validation change (`validateModelId`) | neural-search OSS | Open source |
| `ManagedSemanticModelResolver` | Managed patch (1 new file) | Closed source |
| Resolver swap (1 line) | Managed patch | Closed source |

---

## 5. Model Resolution: Open-Source vs Managed Paths

### 5.1 Open-source path (self-managed OpenSearch)

1. Customer: `PUT /index {"type":"semantic","language":"ENGLISH","model_type":"SPARSE"}`.
2. `SemanticMappingTransformer`: no `model_id` → calls `PretrainedSemanticModelResolver.resolve()`.
3. Resolver registers the pretrained model via ML Commons.
4. Polls the task until DEPLOYED → gets `model_id`.
5. Calls `getModel()` → reads dimension/space_type → `SemanticInfoConfigBuilder` expands `_semantic_info`.
6. Sets `model_id` + `language` + `model_type` on the field config.
7. Index created; ingest + search work.

### 5.2 Managed path (AOS / AOSS)

1. Same customer request.
2. `SemanticMappingTransformer`: no `model_id` → calls `ManagedSemanticModelResolver.resolve()`.
3. Resolver returns:
   - **AOSS:** a global model ID (a hard-coded constant), instantly.
   - **AOS:** a registered model that points to a global model on OASIS via a connector.
4. No model deployment.
5. Index created instantly.

```java
public class ManagedSemanticModelResolver implements SemanticModelResolver {

    public static final String MANAGED_SPARSE_ENGLISH_MODEL_ID     = "managed-inference-sparse-model-id";
    public static final String MANAGED_SPARSE_MULTILINGUAL_MODEL_ID = "managed-inference-multilingual-model-id";
    public static final String MANAGED_DENSE_MODEL_ID              = "managed-inference-dense-model-id";

    @Override
    public void resolve(String language, String modelType, ActionListener<String> listener) {
        validate(language, modelType);
        // AOSS: instant resolution — no registration, no network call
        if ("DENSE".equals(modelType))               listener.onResponse(MANAGED_DENSE_MODEL_ID);
        else if ("MULTI-LINGUAL".equals(language))   listener.onResponse(MANAGED_SPARSE_MULTILINGUAL_MODEL_ID);
        else                                         listener.onResponse(MANAGED_SPARSE_ENGLISH_MODEL_ID);
        // AOS: based on model_type/language, register a model pointing to the global model via a connector
    }

    @Override
    public Map<String, Object> buildSemanticInfoConfig(String language, String modelType, String modelId) {
        // AOSS: build companion field config from known constants (no getModel call)
        if ("DENSE".equals(modelType)) return knnVectorConfig(768, "cosinesimil");
        return rankFeaturesConfig();  // sparse: no dimension needed
        // AOS: call ML Commons getModel() for the given model id
    }
}
```

### 5.3 Validation rules (in open source, shared by both resolvers)

| Input | Result |
| --- | --- |
| Bare `{"type":"semantic"}` | Default: ENGLISH + SPARSE |
| `language` not specified | Default: ENGLISH |
| `model_type` not specified | Default: SPARSE |
| `model_id` + `language`/`model_type` | 400 — mutually exclusive |
| DENSE + MULTI-LINGUAL | 400 — not supported |
| `model_id` alone | Works as before (resolver not invoked) |

### 5.4 Model mapping tables

**Open source (`PretrainedSemanticModelResolver`):**

| language | model_type | Pretrained model | Version |
| --- | --- | --- | --- |
| ENGLISH | SPARSE | opensearch-neural-sparse-encoding-v2-distill | 1.0.0 |
| MULTI-LINGUAL | SPARSE | opensearch-neural-sparse-encoding-multilingual-v1 | 1.0.1 |
| ENGLISH | DENSE | all-MiniLM-L6-v2 | 1.0.1 |

**Managed (`ManagedSemanticModelResolver`):**

| language | model_type | Managed global model ID |
| --- | --- | --- |
| ENGLISH | SPARSE | managed-inference-sparse-model-id |
| MULTI-LINGUAL | SPARSE | managed-inference-multilingual-model-id |
| ENGLISH | DENSE | managed-inference-dense-model-id |

---

## 6. NEW: `text ↔ semantic` Field Type Update (Enable / Disable ASE)

### 6.1 Goal and the gap it fills

Sections 3–5 let a customer *create* a semantic field. But real customers already have live indices full of `text` fields. They need to **enable** ASE on an existing `title` field, and later be able to **disable** it, without reindexing into a brand-new index or dropping data. Today both directions are blocked:

- **Open-source / AOS core:** `SemanticFieldMapper.merge()` (and, more generally, `FieldMapper.merge()` in OpenSearch core) rejects any change to a field's `type` with `"mapper [<name>] cannot be changed from type [text] to [semantic]"` (and vice-versa). This is core's standard "mappings are append-only" guarantee.
- **AOSS SC:** `PutMappingHandler` calls `MappingsUtil.validateSemanticFieldTypeEnabled(..., boolean allowSemanticField)` with `allowSemanticField=false`, which throws for *any* semantic field introduced via PutMapping (whether new or a type change). Semantic fields are only accepted on a direct `CreateIndex`.

**The cleanest solution is to change OpenSearch Core (and, for AOSS, relax the SC validator) to allow the specific `text ↔ semantic` transitions — not a blanket type-change allowance.** Everything downstream (the transformer, companion field, ingest processor, query rewrite) already exists from Sections 3–5 and is reused unchanged.

### 6.2 Customer UX

On the console, the customer selects an existing `text` field → clicks **"Enable ASE"** → picks `language` / `model_type` (or supplies a `model_id`) → the console issues a `PutMapping` that changes the field's `type` to `semantic`:

```json
PUT /my-index/_mapping
{
  "properties": {
    "title": {
      "type": "semantic",
      "language": "ENGLISH",
      "model_type": "SPARSE"
    }
  }
}
```

To **disable**, the console sends the reverse — the same field back to `type: "text"`:

```json
PUT /my-index/_mapping
{
  "properties": {
    "title": { "type": "text" }
  }
}
```

Because `type: "semantic"` *is* the "enabled" state and `type: "text"` *is* the "disabled" state (Decision point #4), no separate status flag is needed; enable/disable is a natural consequence of the update path.

### 6.3 AOS path — core mapper change

**Where:** OpenSearch Core mapper merge logic — `FieldMapper.merge()` / `ParametrizedFieldMapper.merge()`, and the neural-search `SemanticFieldMapper.merge()` override.

**What changes:** Introduce a narrowly-scoped, allow-listed transition rather than lifting the general "type cannot change" rule. Concretely, `merge()` short-circuits the incompatibility error **only** when the transition is exactly `text → semantic` or `semantic → text`; every other cross-type transition keeps throwing as before.

```java
// SemanticFieldMapper.merge() (and the symmetric check when merging a text mapper against a semantic one)
@Override
public Mapper merge(Mapper mergeWith, MapperMergeContext ctx) {
    if (isAllowedSemanticTransition(this, mergeWith)) {
        // Return the "target" mapper (the incoming one) rather than throwing.
        // For text -> semantic the semantic mapper wins; for semantic -> text the text mapper wins.
        return resolveTransitionTarget(this, mergeWith);
    }
    // Unchanged: any other type change still fails hard.
    return super.merge(mergeWith, ctx);
}

private static boolean isAllowedSemanticTransition(Mapper existing, Mapper incoming) {
    String from = typeName(existing), to = typeName(incoming);
    return ("text".equals(from) && "semantic".equals(to))
        || ("semantic".equals(from) && "text".equals(to));
}
```

The transition is gated behind a feature flag/cluster setting so it can be dark-launched and rolled back. This mirrors the metrics-instrumented, flag-gated approach already used on the AOSS side (Section 6.4).

### 6.4 AOSS path — reuse the conflict-merge machinery, remove the block

AOSS already has the harder half of this built. JunoMetadata's `DocumentMapper.mergeMappersAllowingFieldTypeConflicts(existingMapper, newMapper, indexSettings)` performs exactly a field-type-conflicting merge (behind a feature flag, with `CONFLICT_MAPPING_MERGE_ATTEMPT` / `CONFLICT_MAPPING_MERGE` metrics) and returns a candidate mapper encoding how the system will resolve the type change. Combined with rollover, the physical index gets the new mapping cleanly. Two changes are needed:

1. **Remove the `allowSemanticField=false` block in `PutMappingHandler`.** Today `PutMappingHandler`, `InternalPutMappingHandler`, and `JunoMetadataIndexTemplateService.checkNewMappingFeatureEnabledForVersionUpgrade` all pass `allowSemanticField=false` to `MappingsUtil.validateSemanticFieldTypeEnabled(...)`, so semantic fields can only appear on a direct `CreateIndex` (the two `CreateIndexHandler` call-sites pass `true`). To support enable-via-PutMapping, `PutMappingHandler` must pass `allowSemanticField=true` **when the change is a `text → semantic` transition on an existing field** (not a blanket flip — the template and version-upgrade call-sites stay `false`), and route the merge through `mergeMappersAllowingFieldTypeConflicts` rather than the strict merge that `SemanticFieldMapper.merge()` would otherwise reject.
2. **Extend beyond TIMESERIES collections.** The conflict-merge + rollover path is currently exercised for TIMESERIES; the `text ↔ semantic` update must be enabled for the collection types ASE targets (SEARCH / VECTORSEARCH), so the validator and rollover trigger apply there too.

The existing per-index cap (`MAX_SEMANTIC_FIELDS_PER_INDEX = 10`) and the OS-version (≥ 3.3.0) / `SEMANTIC_FIELD_TYPE_ENABLED` feature-flag gates in `validateSemanticFieldTypeEnabled` continue to apply to the transition.

### 6.5 What happens on `text → semantic` (enable)

1. **Field type changes** from `text` to `semantic` (via the allow-listed merge in 6.3 / the conflict-merge in 6.4).
2. **Companion field is created.** The same `MappingTransformer` from Sections 3–5 runs (`SemanticMappingTransformer` on AOS; the equivalent MD-side transformer on AOSS) and injects the `title_semantic_info` companion field (`rank_features` for sparse, `knn_vector` for dense).
3. **`ManagedSemanticModelResolver` resolves the managed `model_id`** from `language`/`model_type` (Section 5) and stamps it onto the field config; for dense, the `index.knn=true` setting is injected as usual.
4. **The system ingest processor starts generating embeddings for new docs.** `SemanticFieldProcessor` is (re)derived from the updated mapping and, from this point forward, every newly indexed/updated document has its `title` embedded into `title_semantic_info.embedding`.
5. **Old docs are not retroactively embedded** — documents indexed *before* the transition have no embeddings in the companion field. Backfill is a **separate concern** (out of scope for this document): options include a customer-triggered reindex/update-by-query, or a managed backfill job. Until backfilled, those old docs are reachable via BM25 on the raw text but not via neural scoring.

### 6.6 What happens on `semantic → text` (disable)

1. **Field type changes back to `text`.**
2. **The `_semantic_info` companion field becomes orphaned.** Existing embeddings are left in place (no destructive mapping change), but no new embeddings are generated. It occupies storage until the customer drops/reindexes; we do not auto-delete it (dropping a field's data is a destructive operation and stays customer-initiated).
3. **The system ingest processor stops** embedding that field — newly indexed docs simply store the raw `text`.
4. **Neural query rewrite stops** — the automatic `match → neural` rewrite (Section 7) no longer fires for this field because it is no longer a semantic field.
5. **BM25 still works on the raw text** — the field behaves as an ordinary analyzed `text` field again (its `raw_field_type` was `text` all along), so lexical `match` continues to function with no interruption.

### 6.7 Validation (same conflict rules as create)

The transition reuses the exact validation from Section 5.3 — `model_id` vs `language`/`model_type` are mutually exclusive (400), `DENSE` + `MULTI-LINGUAL` is rejected (400), and defaults are ENGLISH/SPARSE. In addition:

- Only the two allow-listed transitions (`text → semantic`, `semantic → text`) are permitted; any other type change still fails.
- The `raw_field_type` of the semantic field must be compatible with the pre-existing text field (default `text`); a mismatch (e.g. trying to become a `keyword`-backed semantic field over an analyzed text field) is rejected so the lexical behavior on disable is well-defined.
- On AOSS, the per-index semantic-field cap and version/flag gates from `validateSemanticFieldTypeEnabled` still apply.

---

## 7. NEW: Automatic `match` Query Rewrite (System-Generated Search Request Processor)

### 7.1 Goal

After Section 6, a field can be semantically enriched transparently. But search is still not transparent: to get neural results the customer must *manually* write a `neural` / `neural_sparse` query. The remaining barrier to a true "1-click" experience is the query side — customers want to keep issuing ordinary `match` queries and get semantic results automatically.

**The cleanest solution is a System-Generated Search Request Processor contributed by neural-search** that inspects each incoming search request, detects `match` queries targeting semantic fields, and rewrites them to the corresponding `neural` / `neural_sparse` (optionally `hybrid`) query — before any customer-defined search pipeline runs.

This reuses the exact same SPI OpenSearch already ships for `semantic-highlighter`, `mmr_over_sample_factory`, and `mmr_rerank_factory`, so there is strong precedent and no new framework work.

### 7.2 Mechanism — the system-generated processor SPI

OpenSearch's `SearchPipelinePlugin` exposes:

```java
default Map<String, SystemGeneratedProcessor.SystemGeneratedFactory<SearchRequestProcessor>>
    getSystemGeneratedRequestProcessors(Parameters parameters) { return Collections.emptyMap(); }
```

`SearchPipelineService` collects these factories at boot. For each search request it evaluates every *enabled* factory's `shouldGenerate(ProcessorGenerationContext)`; for those that opt in, it creates a processor and inserts it into the effective pipeline. The execution order relative to a customer's own pipeline is `[PRE_USER_DEFINED, user-defined, POST_USER_DEFINED]`, chosen per-processor via `SystemGeneratedProcessor.getExecutionStage()` (default is post).

`NeuralSearch` will implement `getSystemGeneratedRequestProcessors()` to contribute one factory.

### 7.3 Factory — `SemanticMatchRewriteFactory`

```java
public class SemanticMatchRewriteFactory
        implements SystemGeneratedProcessor.SystemGeneratedFactory<SearchRequestProcessor> {

    public static final String TYPE = "semantic_match_rewrite_factory";

    @Override
    public boolean shouldGenerate(ProcessorGenerationContext context) {
        // Only generate the processor when the target index/indices actually
        // have at least one semantic field. Otherwise, skip entirely — zero cost
        // for the overwhelming majority of indices that have no semantic fields.
        return hasSemanticField(context.indexMetadata());
    }

    @Override
    public SearchRequestProcessor create(
            Map<String, Processor.Factory<SearchRequestProcessor>> processorFactories,
            String tag, String description, boolean ignoreFailure,
            Map<String, Object> config, Processor.PipelineContext pipelineContext) {
        return new SemanticMatchRewriteProcessor(tag, description, ignoreFailure, /* semantic field metadata */);
    }
}
```

`shouldGenerate()` reads the resolved index mapping to check for semantic fields (the same metadata the mapper stores). Indices with no semantic fields never pay for the processor.

### 7.4 Processor — `SemanticMatchRewriteProcessor`

The processor walks the request's query tree and rewrites `match` clauses that target semantic fields:

```java
public class SemanticMatchRewriteProcessor extends AbstractProcessor implements SearchRequestProcessor {

    @Override
    public SearchRequest processRequest(SearchRequest request) {
        QueryBuilder root = request.source().query();
        QueryBuilder rewritten = rewrite(root, /* topLevel = */ true);
        request.source().query(rewritten);
        return request;
    }

    // Returns ExecutionStage.PRE_USER_DEFINED so the rewrite happens before
    // any customer pipeline sees the query.
    @Override
    public SystemGeneratedProcessor.ExecutionStage getExecutionStage() {
        return SystemGeneratedProcessor.ExecutionStage.PRE_USER_DEFINED;
    }

    private QueryBuilder rewrite(QueryBuilder q, boolean lexicalContext) {
        if (q instanceof MatchQueryBuilder m && isSemanticField(m.fieldName()) && !lexicalContext) {
            return toNeural(m);              // match -> neural / neural_sparse (optionally hybrid)
        }
        if (q instanceof BoolQueryBuilder b) {
            BoolQueryBuilder out = QueryBuilders.boolQuery();
            b.must().forEach(c   -> out.must(rewrite(c, false)));    // scoring clauses -> rewrite
            b.should().forEach(c -> out.should(rewrite(c, false)));  // scoring clauses -> rewrite
            b.filter().forEach(out::filter);                         // filter: LEFT UNCHANGED (stay lexical)
            b.mustNot().forEach(out::mustNot);                       // must_not: LEFT UNCHANGED (stay lexical)
            return out;
        }
        return q;  // everything else untouched
    }
}
```

`toNeural()` maps to the field's `model_type`: **sparse → `neural_sparse`**, **dense → `neural`**, reading the resolved `model_id` / `search_model_id` straight from the field's `SemanticParameters` (no customer input needed).

### 7.5 Hybrid option

If a semantic field retains its raw text alongside embeddings (the default — `raw_field_type: text`), the processor can optionally wrap the rewrite in a `HybridQueryBuilder` combining a BM25 `match` on the raw text with the `neural`/`neural_sparse` sub-query, so lexical and semantic signals are scored together:

```java
QueryBuilders.hybridQuery()
    .add(QueryBuilders.matchQuery(field, text))          // BM25 on the raw text
    .add(toNeural(field, text));                          // neural / neural_sparse on embeddings
```

Whether to emit hybrid vs pure-neural is a per-index/per-field policy (default proposal: pure neural for the first launch, hybrid behind a flag), since hybrid requires the normalization/combination search phase results processor to be present.

### 7.6 Execution stage

The processor declares `ExecutionStage.PRE_USER_DEFINED`. This guarantees the `match → neural` rewrite happens **before** any customer-defined search pipeline, so a customer's own request/response processors operate on the already-semantic query — the customer's pipeline sees exactly what it would have seen had they written a `neural` query by hand.

### 7.7 Activation

- **AOS:** automatic. The factory is registered by the neural-search plugin, and its name is added to the default value of `cluster.search.enabled_system_generated_factories` (which on the AOS build already ships defaults such as `mmr_over_sample_factory`, `mmr_rerank_factory`, `semantic-highlighter`). No customer action is required.
- **AOSS:** the AOSS search-cluster build defaults `cluster.search.enabled_system_generated_factories` to an **empty list**, so a factory is inert until explicitly enabled. Activation = adding `semantic_match_rewrite_factory` to `cluster.search.enabled_system_generated_factories` in the search cluster's `opensearch.yml` (this is exactly how `semantic-highlighter` is enabled today). The setting is `Dynamic` + `NodeScope` and also supports the `"*"` wildcard.

### 7.8 What gets rewritten

- `match` on a semantic field → `neural_sparse` (sparse model) or `neural` (dense model).
- `match` inside `bool.must` / `bool.should` → each such clause is rewritten individually (these contribute to score).

### 7.9 What does *not* get rewritten

- `match` inside `bool.filter` / `bool.must_not` → **left unchanged.** Filtering and exclusion should stay lexical/exact; rewriting them to neural would change filter semantics into fuzzy semantic matching, which is almost never what a filter intends.
- `neural` / `neural_sparse` queries → already neural; skipped (this is also the primary opt-out — see 7.10).
- `hybrid` queries → already composed; skipped.
- `term` / `range` / `ids` / etc. → not text-based; skipped.
- `match` on a **non-semantic** field → skipped (only semantic fields are targeted).

### 7.10 Opt-out

Customers have two clean opt-outs:

1. **Cluster-level:** exclude `semantic_match_rewrite_factory` from `cluster.search.enabled_system_generated_factories` (AOSS: simply don't add it; AOS: remove it from the enabled list). The processor then never generates.
2. **Per-query:** issue a `neural` / `neural_sparse` (or `hybrid`) query directly. The processor detects an already-neural query and skips it (7.9), so an explicit query type is always honored verbatim.

---

## 8. Component Changes Summary (all features combined)

| Component | Feature(s) | Change | Open/Closed |
| --- | --- | --- | --- |
| `SemanticFieldMapper` / `SemanticParameters` (neural-search) | ASE params | Add `language` / `model_type` (stored, returned in GET) | Open source |
| `SemanticMappingUtils` (neural-search) | ASE params | Skip `model_id` validation when `language`/`model_type` present; hold shared conflict rules | Open source |
| `SemanticMappingTransformer` (neural-search) | ASE params | Route no-`model_id` fields to the injected `SemanticModelResolver` | Open source |
| `SemanticModelResolver` (interface) + `PretrainedSemanticModelResolver` (neural-search) | ASE params | New extension point + OSS default resolver | Open source |
| `ManagedSemanticModelResolver` + 1-line resolver swap (`AWSOpenSearchNeuralSearchPlugin`) | ASE params | Managed model IDs / OASIS connector | Closed source |
| `FieldMapper.merge()` / `ParametrizedFieldMapper.merge()` + `SemanticFieldMapper.merge()` (OpenSearch Core) | text ↔ semantic | Allow-list the two transitions `text→semantic` / `semantic→text`, flag-gated; every other type change still throws | Open source (core) |
| `PutMappingHandler` + `MappingsUtil.validateSemanticFieldTypeEnabled` (JunoMetadata) | text ↔ semantic | Pass `allowSemanticField=true` for a `text→semantic` transition; route through `mergeMappersAllowingFieldTypeConflicts`; extend beyond TIMESERIES to SEARCH/VECTORSEARCH | Closed source (AOSS) |
| Rollover trigger (JunoMetadata) | text ↔ semantic | Fire on conflict-merge for the ASE collection types | Closed source (AOSS) |
| `NeuralSearch.getSystemGeneratedRequestProcessors()` (neural-search) | match rewrite | Contribute `SemanticMatchRewriteFactory` | Open source |
| `SemanticMatchRewriteFactory` + `SemanticMatchRewriteProcessor` (neural-search) | match rewrite | `shouldGenerate()` gate + query-tree rewrite at `PRE_USER_DEFINED` | Open source |
| `cluster.search.enabled_system_generated_factories` default (AOS build) | match rewrite | Add `semantic_match_rewrite_factory` to the AOS default list | Closed source (AOS build) |
| Search cluster `opensearch.yml` (AOSS SC) | match rewrite | Add `semantic_match_rewrite_factory` to the enabled factories list | Closed source (AOSS) |
| Console | text ↔ semantic | "Enable/Disable ASE" on existing fields → emits `PutMapping` | Closed source |

**Reused unchanged across all three features:** `SemanticInfoConfigBuilder`, `SemanticFieldProcessor` (system ingest), `NeuralQueryBuilder` / `NeuralSparseQueryBuilder`, `MLCommonsClientAccessor`, and the `MappingTransformerRegistry` invocation path.

---

## 9. Phasing / Timeline

### Phase 1 — ASE via CreateIndex (`language`/`model_type`) — Target: mid-August 2026

Foundation for everything else.

| Task | Effort | Notes |
| --- | --- | --- |
| SemanticModelResolver interface + PretrainedSemanticModelResolver (OSS) | 3 days | Interface + pretrained resolver + caching + polling |
| language/model_type params on SemanticFieldMapper (OSS) | 1 day | 2 params + SemanticParameters + GET response |
| Skip-validation patch in SemanticMappingUtils (OSS) | 0.5 day | 2 lines + regression tests |
| Resolver integration in SemanticMappingTransformer (OSS) | 3 days | Split logic, async resolution, expansion |
| KNN setting injection ActionFilter (OSS) | 0.5 day | For dense fields |
| OSS e2e testing | 2 days | Sparse + dense + validation + Python script |
| OSS PR submission + review | 1-2 weeks | Community review (long pole) |
| ManagedSemanticModelResolver (managed patch) | 1 day | 1 file, ~50 lines |
| 1-line resolver swap (managed patch) | 0.5 day | + integration testing |
| AOS DP wiring | 1 day | Verify on AOS domain |
| AOSS wiring (systemIngestPipelineConfig) | 3-5 days | MD extraction + IC reads config |

**Total effort: ~3-4 weeks (1 SDE), ~2 weeks (2 SDEs)**
**Critical path: OSS PR review (1-2 weeks calendar time)**
**Risk hedge:** If OSS PR stalls, ship as Option 2 (all in managed fork), revert to Option 3 when PR merges.

**Assessment: Mid-August is achievable** with 1 SDE if OSS PR review is fast, or with 2 SDEs regardless. Most POC work is already done on `dp-option-common-lib` branch.

---

### Phase 2 — `text ↔ semantic` field type update (enable/disable) — Target: mid-October 2026

Depends on Phase 1 (transformer + resolver must exist).

| Task | Effort | Notes |
| --- | --- | --- |
| AOS: flag-gated allow-list in FieldMapper.merge() (OSS core) | 2-3 days | Narrow: only text↔semantic. Behind feature flag. |
| AOS: trigger SemanticMappingTransformer on type change | 1-2 days | Expand companion field on text→semantic |
| AOS: handle semantic→text (stop processor, orphan companion) | 1 day | Disable path |
| OSS core PR + review | 1-2 weeks | Community review |
| AOSS: remove allowSemanticField=false in PutMappingHandler | 0.5 day | Gate conditionally for text→semantic |
| AOSS: extend collection type beyond TIMESERIES | 0.5 day | Allow SEARCH/VECTORSEARCH |
| AOSS: ensure slim transform module on MD (Phase 2 of Option B) | 2-3 weeks | MD → OASis network path + transform logic |
| AOSS: rollover integration testing | 2-3 days | Verify pIndex rollover with semantic type change |
| Console "Enable/Disable ASE" UI | 1-2 weeks | Frontend + API integration |
| Integration testing (AOS + AOSS) | 3-5 days | Both directions, both platforms |

**Total effort: ~6-8 weeks (1 SDE), ~4-5 weeks (2 SDEs)**
**Critical path: AOSS MD slim transform module (requires MD → OASis infra) + OSS core PR review**
**Dependencies:** Phase 1 merged. AOSS semantic field Option B Phase 2 infra (MD → OASis network path).

**Assessment: Mid-October is achievable** with 2 SDEs. The AOSS MD slim transform is the long pole — if it's already in progress from the AOSS semantic field design, this phase benefits directly.

---

### Phase 3 — Automatic `match` rewrite — Target: mid-October 2026 (parallel with Phase 2)

Independent of Phase 2; only depends on Phase 1 (semantic fields exist to rewrite against).

| Task | Effort | Notes |
| --- | --- | --- |
| SemanticMatchRewriteFactory + Processor implementation | 3-5 days | Query tree walk, match→neural rewrite, bool handling |
| Hybrid mode (BM25 + neural wrapped in HybridQueryBuilder) | 2-3 days | Optional, behind flag |
| AOS activation (add to default factory list) | 0.5 day | opensearch.yml / settings |
| AOSS activation (add to SC opensearch.yml) | 0.5 day | + per-account AppConfig flag |
| Unit tests + integration tests | 2-3 days | Compound queries, opt-out, edge cases |
| OSS PR (if upstreaming) or managed-only | 1-2 weeks | Community review if OSS |
| Performance testing (rewrite latency overhead) | 1-2 days | Ensure negligible coordinator-side cost |

**Total effort: ~3-4 weeks (1 SDE), ~2 weeks (2 SDEs)**
**Critical path: Implementation + testing (no external dependency beyond Phase 1)**

**Assessment: Mid-October is achievable.** Can proceed in parallel with Phase 2 since it's independent. Launch pure-neural first; hybrid behind flag as fast-follow.

---

### Timeline Summary

| Phase | Target | Effort (1 SDE) | Effort (2 SDEs) | Dependency |
| --- | --- | --- | --- | --- |
| Phase 1: CreateIndex with language/model_type | **Mid-August 2026** | 3-4 weeks | 2 weeks | OSS PR review |
| Phase 2: text ↔ semantic update | **Mid-October 2026** | 6-8 weeks | 4-5 weeks | Phase 1 + AOSS MD infra |
| Phase 3: Automatic match rewrite | **Mid-October 2026** | 3-4 weeks | 2 weeks | Phase 1 only |

**With 2 SDEs:**
- SDE-A: Phase 1 (Aug) → Phase 2 (Oct)
- SDE-B: Phase 1 support → Phase 3 (Oct, parallel with Phase 2)

**With 3 SDEs:**
- SDE-A: Phase 1 OSS + managed
- SDE-B: Phase 2 (start early, core PR in parallel)
- SDE-C: Phase 3 (start after Phase 1 lands)
- All three phases could complete by early October.

**Conclusion:**
- **Mid-August for Phase 1: YES** — achievable with 1-2 SDEs. POC already done.
- **Mid-October for Phase 2 + 3: YES** — achievable with 2 SDEs working in parallel. Phase 3 is lower risk; Phase 2 depends on AOSS infra (MD → OASis path).

---

## 10. Diff from the Existing HLD

**Carried over (unchanged in substance) from the base HLD:**
- Problem statement (Section 1).
- The decision to use the `semantic` field type over `semantic_enrichment` (Section 2).
- Current-state description of how the semantic field works in OSS — CreateIndex, GetIndex, PutMapping (add-only), ingest, search (Section 3).
- The `language`/`model_type` proposal and the Option 3 design — `SemanticModelResolver` interface, `PretrainedSemanticModelResolver`, `ManagedSemanticModelResolver`, the one-line swap, validation rules, and model-mapping tables (Sections 4–5). Options 1 and 2 are retained as interim fallbacks (Section 4.4 / Phase 1 risk hedge) rather than re-argued in full.

**New in this document:**
- **Section 6 — `text ↔ semantic` field type update.** Enable/disable ASE on existing fields. AOS path via a flag-gated allow-list in core `merge()`; AOSS path via reusing `mergeMappersAllowingFieldTypeConflicts` + rollover and removing the `allowSemanticField=false` block in `PutMappingHandler` (extended beyond TIMESERIES). Full enable/disable behavior, including the orphaned companion field, ingest-processor start/stop, query-rewrite start/stop, and the explicit deferral of old-doc backfill.
- **Section 7 — Automatic `match` query rewrite.** A system-generated search request processor (`SemanticMatchRewriteFactory` / `SemanticMatchRewriteProcessor`) contributed via `NeuralSearch.getSystemGeneratedRequestProcessors()`, running at `PRE_USER_DEFINED`, rewriting scoring `match` clauses on semantic fields to `neural`/`neural_sparse` (optionally hybrid), leaving `filter`/`must_not` lexical, with AOS-automatic / AOSS-opt-in activation via `cluster.search.enabled_system_generated_factories`, plus opt-out paths.
- **Section 8 — combined component-change summary** across all three features.
- **Section 9 — phasing** that sequences the three capabilities by dependency and risk.

**Net effect:** the base HLD delivered "create a semantic field via the data plane." This document extends the story end-to-end — *toggle* enrichment on existing fields, and *query* them with a plain `match` — so the entire ASE lifecycle is available through the OpenSearch data plane API alone.

---

## 11. Open Questions

1. **First-time latency (OSS path).** ~60 s for pretrained-model download in the open-source `PretrainedSemanticModelResolver`. The managed resolver's pre-deployed models eliminate this — acceptable for OSS?
2. **Upstreaming `language`/`model_type`.** Gating decision for the Option 3 form; upstreaming eliminates the per-version managed patch. Pursue in parallel with the Option 2 interim.
3. **Old-doc backfill on enable (Section 6.5).** Customer-triggered reindex/update-by-query vs a managed backfill job? This is deferred to a separate design — which owner/timeline?
4. **Orphaned `_semantic_info` on disable (Section 6.6).** Leave it (current proposal, non-destructive) vs offer an explicit customer-initiated cleanup? Do we surface storage-cost visibility for the orphaned companion field?
5. **Core `merge()` allow-list acceptance (Section 6.3).** Will the OpenSearch community accept a narrowly-scoped `text ↔ semantic` transition in core `FieldMapper.merge()`, or must the transition live entirely in the managed fork (raising AutoSync cost)?
6. **Hybrid default for match rewrite (Section 7.5).** Launch pure-neural and add hybrid behind a flag, or ship hybrid-by-default where the normalization phase processor is present?
7. **Interaction with existing customer search pipelines (Section 7.6).** Any customer pipeline that already assumes it receives a raw `match` (e.g., re-parses the query text) could behave differently once the query arrives pre-rewritten at `PRE_USER_DEFINED`. Do we need a per-index escape hatch beyond the two opt-outs in 7.10?
8. **Multi-field / cross-index `match`.** How should a `match` that (via `copy_to` or multi-index search) spans both semantic and non-semantic fields be rewritten — split into a hybrid, or rewrite only the semantic targets?
