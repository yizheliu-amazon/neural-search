# Task Breakdown: AOS Option 3 — OSS `language`/`model_type` + Managed Patch

## Scope

Deliver ASE Data Plane support for AOS using Option 3:
- **OSS neural-search**: `SemanticModelResolver` interface, `PretrainedSemanticModelResolver`, `language`/`model_type` params, skip-validation, resolver integration in transformer
- **Managed neural-search** (AWSOpenSearchNeuralSearchPlugin): Add `ManagedSemanticModelResolver.java` + 1-line resolver swap

---

## Stream A: OSS Neural-Search Changes (open-source PR)

### Task A1: `SemanticModelResolver` Interface

**File:** `src/main/java/org/opensearch/neuralsearch/ml/resolver/SemanticModelResolver.java`
**Effort:** 0.5 day

- [ ] Define interface with:
  - `void resolve(String language, String modelType, ActionListener<String> listener)`
  - `default void validate(String language, String modelType)` — rejects DENSE+MULTI-LINGUAL
- [ ] Unit test for `validate()` default method

---

### Task A2: `PretrainedSemanticModelResolver` Implementation

**File:** `src/main/java/org/opensearch/neuralsearch/ml/resolver/PretrainedSemanticModelResolver.java`
**Effort:** 2 days

- [ ] Resolve language/model_type → pretrained model name + version:
  - ENGLISH+SPARSE → `opensearch-neural-sparse-encoding-v2-distill` v1.0.0
  - MULTI-LINGUAL+SPARSE → `opensearch-neural-sparse-encoding-multilingual-v1` v1.0.1
  - ENGLISH+DENSE → `all-MiniLM-L6-v2` v1.0.1
- [ ] Register model via `mlClient.register()` with `deployModel=true`
- [ ] Poll task via `mlClient.getTask()` until COMPLETED → extract model_id
- [ ] Cache resolved model_ids (ConcurrentHashMap by language:model_type key)
- [ ] Unit tests (mock ML client, verify register/poll/cache)

---

### Task A3: `language`/`model_type` Parameters on SemanticFieldMapper

**Files:** `SemanticFieldMapper.java`, `SemanticParameters.java`
**Effort:** 0.5 day

- [ ] Add `Parameter<String> language` in Builder (updatable, default null)
- [ ] Add `Parameter<String> modelType` in Builder (updatable, default null)
- [ ] Add to `getParameters()` list
- [ ] Add fields to `SemanticParameters` DTO
- [ ] Add to `getSemanticParameters()` builder
- [ ] Unit test: verify stored in mapping, returned in GET, null omitted

---

### Task A4: Skip-Validation in SemanticMappingUtils

**File:** `SemanticMappingUtils.java`
**Effort:** 0.5 day

- [ ] `validateModelId()`: if model_id is null → return null (skip, don't throw)
- [ ] `extractModelIdToFieldPathMap()`: if model_id is null → `continue` (skip field)
- [ ] Unit tests: no exception for semantic field without model_id
- [ ] Regression test: existing model_id flow unchanged

---

### Task A5: Resolver Integration in SemanticMappingTransformer

**File:** `SemanticMappingTransformer.java`
**Effort:** 2-3 days

- [ ] Make `mlClientAccessor` / `xContentRegistry` settable (volatile + setters)
- [ ] Add `modelResolver` field + setter
- [ ] Split `transform()`: model_id fields → existing path; no-model_id fields → resolver path
- [ ] Implement `resolveManagedFields()` — sequential async iteration over managed fields
- [ ] `resolveNextField()`: call resolver.resolve(), then:
  - If managed model_id (hardcoded constant) → build semantic_info from known constants
  - Else → call `fetchModelAndModifyMapping()` (existing expansion with getModel)
- [ ] `ensureSpaceTypeForTextEmbeddingModel()` — inject space_type for pretrained dense models that don't include it
- [ ] Set language/model_type defaults on field config after resolution
- [ ] For dense: inject `dense_embedding_config` with `engine: "lucene"`
- [ ] Wire resolver in `NeuralSearch.getMappingTransformers()`:
  ```java
  semanticMappingTransformer.setModelResolver(
      new PretrainedSemanticModelResolver(clientAccessor.getMlClient())
  );
  ```
- [ ] Add `getMlClient()` to `MLCommonsClientAccessor`
- [ ] Unit tests (mock resolver + mock ML client)
- [ ] Integration tests (CreateIndex, PutMapping, GET with language/model_type)

---

### Task A6: KNN Setting Injection for Dense

**File:** `NeuralSearch.java` (add ActionFilter)
**Effort:** 0.5 day

- [ ] ActionFilter: detect `model_type: "DENSE"` in CreateIndex request body
- [ ] Inject `index.knn=true` into request settings
- [ ] Register in `getActionFilters()`
- [ ] Test: dense CreateIndex works without customer specifying `index.knn=true`

---

### Task A7: OSS End-to-End Testing

**Effort:** 1-2 days

- [ ] CreateIndex sparse English → ingest → neural_sparse search
- [ ] CreateIndex dense English → ingest → neural KNN search
- [ ] Bare `{"type":"semantic"}` → defaults to English sparse
- [ ] PutMapping adds semantic field with language/model_type
- [ ] Conflict: model_id + language → 400
- [ ] Conflict: DENSE + MULTI-LINGUAL → 400
- [ ] GET response shows language/model_type
- [ ] Hybrid query (BM25 + neural) on semantic field
- [ ] Python test script (`test_ase_e2e.py`)

---

### Task A8: OSS Code Review + Upstream PR

**Effort:** 2-3 days (calendar time, depends on review cycle)

- [ ] Raise PR to `opensearch-project/neural-search`
- [ ] Address review comments
- [ ] Merge to OSS mainline
- [ ] AutoSync picks up change → flows to AWSOpenSearchNeuralSearchPlugin

---

## Stream B: Managed Neural-Search Patch (closed-source)

### Task B1: `ManagedSemanticModelResolver` Implementation

**Package:** AWSOpenSearchNeuralSearchPlugin (patch)
**File:** `ManagedSemanticModelResolver.java` (~50 lines)
**Effort:** 0.5 day

- [ ] Implement `SemanticModelResolver`:
  - `resolve()`: return managed model IDs based on language/model_type
    - AOS: register model pointing to global OASis model via connector
    - AOSS: return hardcoded global model ID constant
  - `buildSemanticInfoConfig()`: build semantic_info from known constants
    - AOSS: hardcoded dimension/space_type
    - AOS: call getModel() on the registered model
- [ ] Constants: `managed-inference-sparse-model-id`, `managed-inference-multilingual-model-id`, `managed-inference-dense-model-id`
- [ ] Unit tests

---

### Task B2: One-Line Resolver Swap

**Package:** AWSOpenSearchNeuralSearchPlugin (patch)
**Effort:** 0.5 day

- [ ] In `NeuralSearch.getMappingTransformers()`, change:
  ```java
  new PretrainedSemanticModelResolver(mlClient)
  → new ManagedSemanticModelResolver(mlClient)
  ```
- [ ] Verify: CreateIndex with language/model_type returns managed model_id
- [ ] Verify: GET shows language/model_type
- [ ] Verify: PutMapping works
- [ ] Create patch file for AutoSync

---

### Task B3: Managed Integration Testing

**Package:** AWSOpenSearchNeuralSearchPlugin
**Effort:** 1 day

- [ ] Local cluster test: CreateIndex sparse/dense → verify mapping
- [ ] Ingestion test on AOS domain (with real OASis models)
- [ ] Search test on AOS domain
- [ ] PutMapping test
- [ ] Error cases (conflict, dense+multi)

---

## Stream C: Documentation & Cleanup

### Task C1: Documentation

**Effort:** 0.5 day

- [ ] DEVELOPER_GUIDE.md — build/run/test instructions (OSS)
- [ ] Update HLD design doc with final implementation details
- [ ] Document model mapping table

---

## Dependencies & Ordering

```
Stream A (OSS):
  A1 (interface) ──→ A2 (pretrained resolver) ──→ A5 (transformer integration) ──→ A7 (e2e) ──→ A8 (PR)
  A3 (params) ────→ A5
  A4 (skip patch) → A5
  A6 (knn filter) — parallel with A5

Stream B (Managed):
  Depends on: A8 merged (or: apply locally while PR is in review)
  B1 (managed resolver) → B2 (swap) → B3 (testing)

Stream C:
  After A7 + B3
```

**Critical path:** A1 → A2 → A5 → A7 → A8 → B1 → B2 → B3

---

## Timeline Estimate

### With 1 SDE

| Week | Tasks | Milestone |
| --- | --- | --- |
| Week 1 | A1 + A3 + A4 + A6 | Interface, params, skip patch, knn filter |
| Week 2 | A2 + A5 | Pretrained resolver + transformer integration |
| Week 3 | A7 + A8 (raise PR) | OSS e2e verified, PR submitted |
| Week 4 | A8 (review) + B1 + B2 | PR review; managed resolver ready (apply locally) |
| Week 5 | B3 + C1 | Managed e2e on AOS domain; docs |

**Total: ~5 weeks (1 SDE, AOS only)**

### With 2 SDEs

| Week | SDE-A | SDE-B |
| --- | --- | --- |
| Week 1 | A1 + A2 (interface + pretrained resolver) | A3 + A4 + A6 (params, skip, knn) |
| Week 2 | A5 (transformer integration) | B1 (managed resolver — apply OSS locally) |
| Week 3 | A7 (OSS e2e) + A8 (raise PR) | B2 + B3 (managed swap + testing) |
| Week 4 | A8 (PR review + merge) | C1 (docs) |

**Total: ~3-4 weeks (2 SDEs, AOS only)**

---

## Risk: OSS PR Review Latency

Option 3's critical dependency is the OSS PR being merged. Mitigation:
- Start managed work (Stream B) by applying OSS changes locally while PR is in review
- If PR stalls beyond 2 weeks, fall back to Option 2 (all changes in managed fork) and re-submit Option 3 when PR merges
- Since neural-search is owned by our team, we have influence over review velocity

---

## What's Already Done (POC on `dp-option-common-lib` branch)

| Task | Status |
| --- | --- |
| A1 (SemanticModelResolver interface) | ✅ Done |
| A2 (PretrainedSemanticModelResolver) | ✅ Done |
| A3 (language/model_type params) | ✅ Done |
| A4 (skip validation) | ✅ Done |
| A5 (transformer integration) | ✅ Done |
| A6 (KNN filter) | ✅ Done |
| A7 (OSS e2e — sparse + dense verified) | ✅ Done |
| B1 (ManagedSemanticModelResolver) | ✅ Done (on `dp-option-use-common-lib-poc`) |
| B2 (resolver swap verified) | ✅ Done |

**Remaining:** Polish for production quality, proper unit tests, OSS PR (A8), managed integration on real AOS domain (B3), docs (C1).
