# Option 3: Managed Model Provider Library

## Overview

A **library jar** (`opensearch-model-provider`) that contains managed-service-specific model resolution logic. Managed neural-search depends on it as a compile-time dependency and uses `ManagedSemanticModelResolver` instead of the OSS default `PretrainedSemanticModelResolver`.

No ExtensiblePlugin, no SPI, no runtime discovery — just a direct dependency swap in the managed fork.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│ neural-search (OSS)                                                  │
│                                                                      │
│  SemanticModelResolver (interface)                                   │
│    └── void resolve(language, modelType, listener)                   │
│    └── void validate(language, modelType)                            │
│                                                                      │
│  PretrainedSemanticModelResolver (default OSS impl)                  │
│    └── Registers pretrained models from hub, polls, caches           │
│                                                                      │
│  SemanticMappingTransformer                                          │
│    └── Uses resolver.resolve() for fields without model_id           │
│    └── resolver is set in NeuralSearch.getMappingTransformers()      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ opensearch-model-provider (library jar, closed-source)               │
│                                                                      │
│  ManagedSemanticModelResolver                                        │
│    └── Returns hardcoded managed model IDs (constants)               │
│    └── No registration, no deployment — models pre-deployed          │
│    └── providesStaticExpansion() → builds semantic_info directly     │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ managed neural-search (closed-source fork, patches OSS)              │
│                                                                      │
│  Depends on: opensearch-model-provider (compile dependency)          │
│  Patch: one line in NeuralSearch.getMappingTransformers():            │
│                                                                      │
│    // OSS:                                                           │
│    resolver = new PretrainedSemanticModelResolver(mlClient);          │
│                                                                      │
│    // Managed (patched):                                             │
│    resolver = new ManagedSemanticModelResolver();                     │
└─────────────────────────────────────────────────────────────────────┘
```

## What Lives Where

| Component | Location | Visibility |
| --- | --- | --- |
| `SemanticModelResolver` interface | neural-search (OSS) | Open-source |
| `PretrainedSemanticModelResolver` | neural-search (OSS) | Open-source |
| `language`/`model_type` parameters on SemanticFieldMapper | neural-search (OSS) | Open-source |
| Resolver integration in SemanticMappingTransformer | neural-search (OSS) | Open-source |
| `ManagedSemanticModelResolver` | opensearch-model-provider (library) | Closed-source |
| One-line resolver swap | managed neural-search fork | Closed-source |

## How It Works

### OSS path (self-managed OpenSearch)

1. Customer creates index with `{"type": "semantic", "language": "ENGLISH", "model_type": "SPARSE"}`
2. `SemanticMappingTransformer` sees no `model_id`, calls `PretrainedSemanticModelResolver.resolve()`
3. Resolver registers pretrained model from hub (e.g., `amazon/neural-sparse/opensearch-neural-sparse-encoding-v1`), polls until deployed
4. Sets `model_id` on field config, fetches model metadata via `getModel()`, expands `semantic_info`
5. Index created with working ingest + search

### Managed service path (AOS/AOSS)

1. Same customer request
2. `SemanticMappingTransformer` calls `ManagedSemanticModelResolver.resolve()`
3. Resolver immediately returns hardcoded model ID (e.g., `managed-inference-sparse-model-id`) — no registration, no deployment
4. Since `providesStaticExpansion() = true`, builds `semantic_info` directly from known constants (dimension, space_type) — no `getModel()` call needed
5. Index created instantly

## The One-Line Patch

In the managed neural-search fork, `NeuralSearch.getMappingTransformers()`:

```java
// OSS version:
semanticMappingTransformer.setModelResolver(
    new PretrainedSemanticModelResolver(clientAccessor.getMlClient())
);

// Managed version (the only change):
semanticMappingTransformer.setModelResolver(
    new ManagedSemanticModelResolver()
);
```

## ManagedSemanticModelResolver Implementation

```java
public class ManagedSemanticModelResolver implements SemanticModelResolver {

    public static final String MANAGED_SPARSE_ENGLISH_MODEL_ID = "managed-inference-sparse-model-id";
    public static final String MANAGED_SPARSE_MULTILINGUAL_MODEL_ID = "managed-inference-multilingual-model-id";
    public static final String MANAGED_DENSE_MODEL_ID = "managed-inference-dense-model-id";

    @Override
    public void resolve(String language, String modelType, ActionListener<String> listener) {
        validate(language, modelType);
        // Instant resolution — no registration, no network call
        if ("DENSE".equals(modelType))        listener.onResponse(MANAGED_DENSE_MODEL_ID);
        else if ("MULTI-LINGUAL".equals(language)) listener.onResponse(MANAGED_SPARSE_MULTILINGUAL_MODEL_ID);
        else                                  listener.onResponse(MANAGED_SPARSE_ENGLISH_MODEL_ID);
    }

    @Override
    public boolean providesStaticExpansion() { return true; }

    @Override
    public Map<String, Object> buildSemanticInfoConfig(String language, String modelType) {
        // Build companion field config from known constants (no getModel call)
        if ("DENSE".equals(modelType)) {
            return knnVectorConfig(768, "cosinesimil");   // managed dense model specs
        }
        return rankFeaturesConfig();  // sparse: no dimension needed
    }
}
```

## Managed ML Commons Client

If the managed service needs a different ML Commons interaction pattern (e.g., calling OASis instead of local ML Commons), the `opensearch-model-provider` library can also provide a `ManagedMLCommonsClient` that the managed neural-search fork uses. This follows the same pattern:

- Interface lives in OSS neural-search (`MLCommonsClientAccessor`)
- Managed override lives in `opensearch-model-provider`
- Managed fork patches the wiring

For v1, this is likely not needed — `ManagedSemanticModelResolver` with `providesStaticExpansion=true` bypasses all ML Commons calls entirely.

## Pros and Cons

| Dimension | Pro | Con |
| --- | --- | --- |
| Simplicity | One-line patch, direct dependency, no runtime magic | — |
| OSS impact | Zero — interface + default impl are fully open-source | — |
| Managed footprint | Just a library jar (~10KB) — not a plugin, no memory overhead | — |
| Maintenance | Library versioned independently; neural-search patch is one line | Must keep interface stable |
| Open-source path | `SemanticModelResolver` + `language`/`model_type` upstreamed; only model IDs stay closed | — |
| Build complexity | Just add a jar dependency to managed neural-search | Extra jar to build/publish |
| No ExtensiblePlugin | No SPI, no loadExtensions, no runtime discovery | Less "pluggable" (but we don't need pluggability) |

## Comparison with Options 1 and 2

| Dimension | Option 1 (ASE Plugin) | Option 2 (Patch neural-search) | Option 3 (Model Provider Library) |
| --- | --- | --- | --- |
| Closed-source size | 14KB plugin | ~150 lines in neural-search | ~10KB library + 1-line patch |
| Deployment | Separate plugin to install | Single plugin | Library jar (not a plugin) + patched neural-search |
| OSS contamination | None (separate plugin) | High (AWS logic in OSS code) | None (interface is OSS-clean) |
| Open-source path | Plugin can be upstreamed | Must extract AWS logic first | Interface already OSS; library stays closed |
| Memory overhead | ~14KB (negligible) | Zero | Zero (library loaded by neural-search classloader) |
| Maintenance | 2 artifacts (plugin + 10-line patch) | 1 artifact (150+ lines) | 2 artifacts (library + 1-line patch) |
| Resolver swap | ExtensiblePlugin runtime | Hardcoded in neural-search | Compile-time dependency swap |

## Why Option 3

1. **Cleanest OSS story** — the entire `language`/`model_type` mechanism + `SemanticModelResolver` interface can be upstreamed. Nothing AWS-specific touches OSS code.
2. **Simplest managed patch** — literally one line changes which resolver to use.
3. **No plugin overhead** — the library is loaded by neural-search's own classloader, not as a separate plugin. Zero memory cost, no plugin lifecycle.
4. **Future-proof** — if we add more managed-service substitutions (e.g., `ManagedMLCommonsClient`, `ManagedChunkerProvider`), they all go in the same library. One jar, many overrides.
5. **No runtime complexity** — no ExtensiblePlugin, no SPI, no service discovery. A compile-time dependency is the simplest possible mechanism.
