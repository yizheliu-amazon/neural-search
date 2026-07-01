# Task Breakdown: Option 3 — ASE DP Support

## Summary

Deliver ASE Data Plane support for AOS using:
- neural-search OSS changes (SemanticModelResolver interface + language/model_type params)
- `opensearch-model-provider` library (ManagedSemanticModelResolver)
- Managed neural-search: 1-line patch to swap resolver

---

## Task 1: neural-search OSS — SemanticModelResolver Interface

**Package:** neural-search (OSS, open-sourceable)
**Branch:** `dp-option-common-lib`
**Effort:** 1-2 days

### Subtasks:
- [ ] 1.1 Create `SemanticModelResolver` interface (`ml/resolver/SemanticModelResolver.java`)
  - `resolve(language, modelType, ActionListener<String> listener)`
  - `validate(language, modelType)` (default: reject DENSE+MULTI-LINGUAL)
  - `providesStaticExpansion()` (default: false)
  - `buildSemanticInfoConfig(language, modelType)` (default: throws)
- [ ] 1.2 Create `PretrainedSemanticModelResolver` (`ml/resolver/PretrainedSemanticModelResolver.java`)
  - Resolves language/model_type → pretrained model name
  - Registers model via ML Commons (deploy=true)
  - Polls task until COMPLETED, extracts model_id
  - Caches resolved model_ids (ConcurrentHashMap)
  - Per-model version mapping (v2-distill=1.0.0, v1=1.0.1, etc.)
- [ ] 1.3 Unit tests for both classes

---

## Task 2: neural-search OSS — Resolver Integration in SemanticMappingTransformer

**Package:** neural-search (OSS)
**Branch:** `dp-option-common-lib`
**Effort:** 2-3 days

### Subtasks:
- [ ] 2.1 Make `mlClientAccessor` and `xContentRegistry` settable (volatile fields + setters)
- [ ] 2.2 Add `modelResolver` field + setter/getter
- [ ] 2.3 Split `transform()` logic: model_id fields → existing path; managed fields → resolver path
- [ ] 2.4 Implement `resolveManagedFields()` / `resolveNextField()` — sequential async resolution
- [ ] 2.5 Handle `providesStaticExpansion()` — build semantic_info directly from resolver
- [ ] 2.6 Handle OSS path — after resolve, call `fetchModelAndModifyMapping()` with resolved model_id
- [ ] 2.7 `ensureSpaceTypeForTextEmbeddingModel()` — inject space_type for pretrained dense models
- [ ] 2.8 Set default language/model_type on field config after resolution (so GET shows them)
- [ ] 2.9 For dense models: inject `dense_embedding_config` with `engine: "lucene"` (avoid faiss native lib)
- [ ] 2.10 Wire resolver in `NeuralSearch.getMappingTransformers()` — set PretrainedSemanticModelResolver
- [ ] 2.11 Add `getMlClient()` to `MLCommonsClientAccessor`
- [ ] 2.12 Unit tests for transformer with resolver (mock resolver, verify expansion)
- [ ] 2.13 Integration tests (CreateIndex with language/model_type, verify mapping expanded)

---

## Task 3: neural-search OSS — Add language/model_type Parameters to SemanticFieldMapper

**Package:** neural-search (OSS)
**Branch:** `dp-option-common-lib`
**Effort:** 0.5-1 day

### Subtasks:
- [ ] 3.1 Add `language` Parameter in `SemanticFieldMapper.Builder`
- [ ] 3.2 Add `model_type` Parameter in `SemanticFieldMapper.Builder`
- [ ] 3.3 Add fields to `SemanticParameters` DTO
- [ ] 3.4 Add to `getParameters()` list
- [ ] 3.5 Add to `getSemanticParameters()` builder
- [ ] 3.6 Verify: GET index returns language/model_type; null values omitted
- [ ] 3.7 Unit tests

---

## Task 4: neural-search OSS — Skip Validation Patch (SemanticMappingUtils)

**Package:** neural-search (OSS)
**Branch:** `dp-option-common-lib`
**Effort:** 0.5 day

### Subtasks:
- [ ] 4.1 `validateModelId()`: return null when model_id is absent (let resolver handle)
- [ ] 4.2 `extractModelIdToFieldPathMap()`: skip (continue) fields without model_id
- [ ] 4.3 Unit tests (verify no exception for fields without model_id)
- [ ] 4.4 Verify existing model_id flow is unchanged (regression test)

---

## Task 5: opensearch-model-provider Library

**Package:** `opensearch-model-provider` (new, closed-source)
**Effort:** 1 day

### Subtasks:
- [ ] 5.1 Create package structure (build.gradle, settings.gradle)
- [ ] 5.2 Implement `ManagedSemanticModelResolver`
  - `resolve()`: return hardcoded managed model IDs based on language/model_type
  - `providesStaticExpansion()`: return true
  - `buildSemanticInfoConfig()`: build rank_features (sparse) or knn_vector (dense, dim=768, cosinesimil)
- [ ] 5.3 Constants: `managed-inference-sparse-model-id`, `managed-inference-multilingual-model-id`, `managed-inference-dense-model-id`
- [ ] 5.4 Unit tests
- [ ] 5.5 Build pipeline setup (publish jar to internal artifact repo)

---

## Task 6: Managed neural-search — 1-Line Resolver Swap

**Package:** AWSOpenSearchNeuralSearchPlugin (internal fork)
**Effort:** 0.5 day

### Subtasks:
- [ ] 6.1 Add `opensearch-model-provider` as compile dependency in build.gradle
- [ ] 6.2 Patch `NeuralSearch.getMappingTransformers()`:
  ```java
  // Change from:
  new PretrainedSemanticModelResolver(clientAccessor.getMlClient())
  // To:
  new ManagedSemanticModelResolver()
  ```
- [ ] 6.3 Verify: CreateIndex with language/model_type returns managed model_id
- [ ] 6.4 Verify: GET shows language/model_type
- [ ] 6.5 Verify: PutMapping with language/model_type works
- [ ] 6.6 Integration test on managed cluster

---

## Task 7: ActionFilter — KNN Setting Injection (for dense)

**Package:** Either in neural-search OSS or managed fork
**Effort:** 0.5 day

### Subtasks:
- [ ] 7.1 Implement ActionFilter that detects `model_type: "DENSE"` in CreateIndex request
- [ ] 7.2 Inject `index.knn=true` into request settings
- [ ] 7.3 Register in `NeuralSearch.getActionFilters()`
- [ ] 7.4 Test: dense index creation without customer specifying `index.knn=true`

---

## Task 8: Validation & Error Handling

**Package:** neural-search (OSS, in SemanticMappingTransformer or resolver)
**Effort:** 0.5 day

### Subtasks:
- [ ] 8.1 Conflict: model_id + language/model_type → 400 error
- [ ] 8.2 DENSE + MULTI-LINGUAL → 400 error
- [ ] 8.3 Invalid language value → 400 error
- [ ] 8.4 Invalid model_type value → 400 error
- [ ] 8.5 Unit tests for all error cases

---

## Task 9: End-to-End Testing

**Effort:** 1-2 days

### Subtasks:
- [ ] 9.1 OSS e2e: CreateIndex → ingest → search (sparse English)
- [ ] 9.2 OSS e2e: CreateIndex → ingest → search (dense English)
- [ ] 9.3 OSS e2e: PutMapping adds semantic field to existing index
- [ ] 9.4 OSS e2e: Bare `{"type":"semantic"}` defaults to English sparse
- [ ] 9.5 OSS e2e: Hybrid query (BM25 + neural) on semantic field
- [ ] 9.6 Managed e2e: CreateIndex with managed resolver (hardcoded model_id)
- [ ] 9.7 Managed e2e: GET index shows language/model_type
- [ ] 9.8 Error cases: conflict, dense+multi, invalid params
- [ ] 9.9 Python test script (`test_ase_e2e.py`) covers all above
- [ ] 9.10 Performance: measure CreateIndex latency (first-time vs cached)

---

## Task 10: Documentation

**Effort:** 0.5 day

### Subtasks:
- [ ] 10.1 DEVELOPER_GUIDE.md — build/run/test instructions
- [ ] 10.2 Update design doc with final implementation details
- [ ] 10.3 Document model mapping table (language/model_type → model name/ID)

---

## Dependencies & Ordering

```
Task 1 (interface) ──→ Task 2 (transformer integration) ──→ Task 6 (managed swap)
                  ──→ Task 5 (model-provider library) ──→ Task 6
Task 3 (params) ────→ Task 2 (uses params in transformer)
Task 4 (skip patch) → Task 2 (transformer relies on skip)
Task 7 (knn filter) — independent, can parallel with Task 2
Task 8 (validation) — part of Task 2, can parallel
Task 9 (testing) ───→ after Tasks 1-8
Task 10 (docs) ────→ after Task 9
```

## Timeline Estimate

| Task | Effort | Can Parallelize? |
| --- | --- | --- |
| Tasks 1+3+4 (interface + params + skip) | 2-3 days | Together |
| Task 2 (transformer integration) | 2-3 days | After 1+3+4 |
| Task 5 (model-provider library) | 1 day | Parallel with Task 2 |
| Task 6 (managed swap) | 0.5 day | After 2+5 |
| Task 7 (knn filter) | 0.5 day | Parallel |
| Task 8 (validation) | 0.5 day | Part of Task 2 |
| Task 9 (e2e testing) | 1-2 days | After all above |
| Task 10 (docs) | 0.5 day | After 9 |

**Total: ~8-10 days for 1 SDE (AOS side only)**

With 2 SDEs:
- SDE-A: Tasks 1-4 + 7 + 8 (neural-search OSS) → 4-5 days
- SDE-B: Task 5 + 6 + 9 + 10 (model-provider + managed + testing) → 3-4 days
- **Critical path: ~5 days**

---

## What's Already Done (POC)

The `dp-option-common-lib` branch already has working implementations of Tasks 1-4, 7, 8, and partial Task 9. The remaining work is:
- Polish + code review readiness
- Task 5 (model-provider library as proper package)
- Task 6 (patch in managed fork)
- Full Task 9 (comprehensive e2e)
- Task 10 (docs)
