# Option 3: SemanticModelResolver Interface + Managed Patch

## Overview

All `language`/`model_type` resolution logic lives in **OSS neural-search** behind a `SemanticModelResolver` interface. The managed service fork (AWSOpenSearchNeuralSearchPlugin) adds one file (`ManagedSemanticModelResolver.java`) and swaps one line to use it.

No separate library. No extra package. No dependency complexity.

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ OSS neural-search (synced from GitHub via AutoSync)               │
│                                                                   │
│  SemanticModelResolver (interface)                                │
│    • resolve(language, modelType, listener) → model_id            │
│    • validate(language, modelType)                                │
│    • providesStaticExpansion() → boolean                          │
│    • buildSemanticInfoConfig(language, modelType) → Map           │
│                                                                   │
│  PretrainedSemanticModelResolver (default implementation)         │
│    • Registers pretrained models from OpenSearch model hub        │
│    • Polls task until deployed, caches model_ids                  │
│    • Used in OSS / self-managed deployments                       │
│                                                                   │
│  SemanticMappingTransformer                                       │
│    • Splits fields: model_id → existing path; no model_id → resolver │
│    • Resolver set in NeuralSearch.getMappingTransformers()        │
│                                                                   │
│  SemanticFieldMapper                                              │
│    • Accepts language/model_type as stored parameters             │
│    • Returns them in GET response                                 │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ Managed neural-search patch (AWSOpenSearchNeuralSearchPlugin)     │
│                                                                   │
│  What AutoSync gives us: entire OSS source (including above)     │
│                                                                   │
│  Patch adds:                                                      │
│    1. ManagedSemanticModelResolver.java (~50 lines, new file)    │
│       • Returns hardcoded managed model IDs instantly             │
│       • providesStaticExpansion() = true                          │
│       • Builds semantic_info from known constants (no getModel)   │
│                                                                   │
│    2. One-line swap in NeuralSearch.getMappingTransformers():     │
│       - new PretrainedSemanticModelResolver(mlClient)             │
│       + new ManagedSemanticModelResolver()                        │
└──────────────────────────────────────────────────────────────────┘
```

## What Lives Where

| Component | Location | Open/Closed |
| --- | --- | --- |
| `SemanticModelResolver` interface | neural-search OSS source | Open-source |
| `PretrainedSemanticModelResolver` | neural-search OSS source | Open-source |
| `language`/`model_type` on SemanticFieldMapper | neural-search OSS source | Open-source |
| Resolver integration in SemanticMappingTransformer | neural-search OSS source | Open-source |
| Skip-validation patch (validateModelId) | neural-search OSS source | Open-source |
| `ManagedSemanticModelResolver` | Managed patch (1 new file) | Closed-source |
| Resolver swap (1 line) | Managed patch | Closed-source |

## How It Works

### OSS (self-managed OpenSearch)

1. Customer: `PUT /index {"type":"semantic", "language":"ENGLISH", "model_type":"SPARSE"}`
2. `SemanticMappingTransformer`: no model_id → calls `PretrainedSemanticModelResolver.resolve()`
3. Resolver registers `amazon/neural-sparse/opensearch-neural-sparse-encoding-v2-distill` via ML Commons
4. Polls task until DEPLOYED → gets model_id
5. Calls `getModel()` → reads dimension/space_type → `SemanticInfoConfigBuilder` expands semantic_info
6. Sets model_id + language + model_type on field config
7. Index created, ingest + search work

### Managed service (AOS/AOSS)

1. Same customer request
2. `SemanticMappingTransformer`: no model_id → calls `ManagedSemanticModelResolver.resolve()`
3. Resolver instantly returns `"managed-inference-sparse-model-id"` (hardcoded constant)
4. Since `providesStaticExpansion() = true`: builds semantic_info directly from known constants (rank_features for sparse, knn_vector dim 768 for dense)
5. No model registration, no deployment, no getModel call
6. Index created instantly

## The Managed Patch (2 changes total)

### Change 1: New file `ManagedSemanticModelResolver.java`

```java
package org.opensearch.neuralsearch.ml.resolver;

public class ManagedSemanticModelResolver implements SemanticModelResolver {

    public static final String MANAGED_SPARSE_EN = "managed-inference-sparse-model-id";
    public static final String MANAGED_SPARSE_ML = "managed-inference-multilingual-model-id";
    public static final String MANAGED_DENSE = "managed-inference-dense-model-id";

    @Override
    public void resolve(String language, String modelType, ActionListener<String> listener) {
        validate(language, modelType);
        String type = modelType != null ? modelType.toUpperCase() : "SPARSE";
        String lang = language != null ? language.toUpperCase() : "ENGLISH";
        if ("DENSE".equals(type))             listener.onResponse(MANAGED_DENSE);
        else if ("MULTI-LINGUAL".equals(lang)) listener.onResponse(MANAGED_SPARSE_ML);
        else                                   listener.onResponse(MANAGED_SPARSE_EN);
    }

    @Override
    public boolean providesStaticExpansion() { return true; }

    @Override
    public Map<String, Object> buildSemanticInfoConfig(String language, String modelType) {
        String type = modelType != null ? modelType.toUpperCase() : "SPARSE";
        if ("DENSE".equals(type)) {
            return Map.of("properties", Map.of(
                "embedding", knnVectorConfig(768, "cosinesimil"),
                "model", modelMetadataConfig()));
        }
        return Map.of("properties", Map.of(
            "embedding", Map.of("type", "rank_features"),
            "model", modelMetadataConfig()));
    }
}
```

### Change 2: One-line swap in `NeuralSearch.getMappingTransformers()`

```java
// Before (OSS default):
semanticMappingTransformer.setModelResolver(
    new PretrainedSemanticModelResolver(clientAccessor.getMlClient())
);

// After (managed):
semanticMappingTransformer.setModelResolver(
    new ManagedSemanticModelResolver()
);
```

## Why No Separate Library

The managed neural-search (AWSOpenSearchNeuralSearchPlugin) is a **source fork** — it vendors the entire OSS neural-search source via AutoSync and compiles it directly. It does NOT consume the OSS jar.

This means:
- `SemanticModelResolver` interface is compiled inside the managed plugin itself
- A separate `opensearch-model-provider` library would need to depend on the managed plugin to see the interface → circular dependency
- **Solution**: just put `ManagedSemanticModelResolver` directly in the managed source as a patch file — no external dependency needed

## Pros and Cons

| Dimension | Pro | Con |
| --- | --- | --- |
| Simplicity | 1 new file + 1 line change. No extra packages/jars/dependencies | Slightly larger patch than "1 line" (but still ~50 lines, 1 file) |
| No dependency issues | Everything compiles in one unit. No circular deps | — |
| OSS-clean | All mechanism (interface, params, resolver integration) is open-source. Only model IDs are closed | — |
| AutoSync-friendly | Patch is additive (new file + 1 line). No merge conflicts with OSS sync | Must re-apply patch on each sync (trivial for 1 file + 1 line) |
| Testable | Can test managed resolver with unit tests in the managed package | — |
| Open-source path | If we upstream managed model concept, just move the file to OSS | — |
| No memory overhead | No new plugin, no new classloader. Zero runtime cost | — |

## Comparison with Options 1 and 2

| Dimension | Option 1 (Separate ASE Plugin) | Option 2 (All in neural-search) | Option 3 (Interface + Managed Patch) |
| --- | --- | --- | --- |
| Closed-source footprint | 14KB plugin + 10-line neural-search patch | ~150 lines in neural-search | 1 new file (~50 lines) + 1-line swap |
| Dependency complexity | Separate plugin, must install alongside | None | None |
| Circular dep risk | Needs careful plugin load ordering | None | None |
| OSS contamination | None | High (AWS logic in OSS) | None (only interface in OSS) |
| AutoSync compatibility | Must maintain separate plugin | Large patch conflicts on sync | Trivial patch, additive only |
| Open-source path | Plugin can be upstreamed | Must extract first | Interface already OSS; move 1 file to upstream |
| Memory overhead | ~14KB | Zero | Zero |
| Build pipeline | New package to build/deploy | Same package | Same package (patch applied at build time) |
| **model_id optional (ASE to OSS)** | No — ASE logic stays in closed-source plugin; OSS still requires model_id | Partially — logic in OSS but mixed with AWS specifics | **Yes — SemanticModelResolver + language/model_type are fully in OSS. OSS users can use semantic field without providing model_id (resolved to pretrained model automatically). This upstreams the ASE "no model_id needed" experience to open-source.** |
| **Feature ownership** | Fragmented — ASE is an enhancement of neural-search's semantic field, but lives in a separate plugin. Bug triage spans two plugins interacting at MappingTransformer level. Not a new product, doesn't warrant a new plugin. | Unified — all in one place | Unified — enhancement lives where the feature lives (neural-search). Managed patch is just a policy swap, not a separate product. |

## Validation Rules (handled in OSS, shared by both resolvers)

| Input | Result |
| --- | --- |
| Bare `{"type":"semantic"}` | Default: ENGLISH + SPARSE |
| `language` not specified | Default: ENGLISH |
| `model_type` not specified | Default: SPARSE |
| `model_id` + `language`/`model_type` | 400 — mutually exclusive |
| DENSE + MULTI-LINGUAL | 400 — not supported |
| `model_id` alone | Works as before (resolver not invoked) |

## Model Mapping

### OSS (PretrainedSemanticModelResolver)

| language | model_type | Pretrained Model | Version |
| --- | --- | --- | --- |
| ENGLISH | SPARSE | opensearch-neural-sparse-encoding-v2-distill | 1.0.0 |
| MULTI-LINGUAL | SPARSE | opensearch-neural-sparse-encoding-multilingual-v1 | 1.0.1 |
| ENGLISH | DENSE | all-MiniLM-L6-v2 | 1.0.1 |

### Managed (ManagedSemanticModelResolver)

| language | model_type | Managed Model ID |
| --- | --- | --- |
| ENGLISH | SPARSE | managed-inference-sparse-model-id |
| MULTI-LINGUAL | SPARSE | managed-inference-multilingual-model-id |
| ENGLISH | DENSE | managed-inference-dense-model-id |
