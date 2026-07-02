# ASE Data Plane Design — Hybrid Approach

**Amazon Semantic Enrichment (ASE) — Data Plane (DP)**

Status: Draft for review
Owner: OpenSearch Neural Search / AOS managed service
Related: [ASE approach comparison](https://chorus.aws.dev/doc/3pkPc89HLJRl)

---

## 1. Problem Statement

Customers of Amazon OpenSearch Service (AOS, managed domains) and Amazon OpenSearch
Serverless (AOSS) want semantic search — dense (vector) and sparse (learned-sparse /
neural-sparse) retrieval — **without** having to:

- Choose, host, deploy, or manage an ML model.
- Wire up ML Commons connectors, model groups, and model deployment.
- Author ingest pipelines that call `text_embedding` / `sparse_encoding` processors.
- Author search pipelines and rewrite their queries to `neural` / `neural_sparse`.
- Understand embedding dimensionality, `knn_vector` mappings, or `rank_features` fields.

The managed service already owns and operates the inference substrate (managed models
served through OASis / ML Commons). ASE's job is to expose a *simple, managed* semantic
search contract on top of that substrate so that a customer declares intent ("this field
should be semantically searchable, in English, using a sparse model") and the service
does the rest: model resolution, embedding generation at ingest, and query-time
retrieval.

Two customer populations exist, and they have materially different needs:

1. **New indices** — the customer is authoring a brand-new index and brand-new queries.
   They are willing to learn a clean, OpenSearch-native contract.
2. **Existing indices** — the customer already has a `text` field in production with
   `match` queries running against it. They want to "turn on" semantic search with
   *zero query changes* and be able to turn it back off.

A single mechanism cannot serve both populations well. This document proposes a
**hybrid approach** that uses the right primitive for each population, sharing one backend.

---

## 2. Decision: Hybrid Approach

We adopt **two customer-facing contracts** backed by **one inference/embedding backend**:

| | **Path 1 — New index** | **Path 2 — Existing index** |
|---|---|---|
| Primitive | `semantic` field type | `semantic_enrichment` on a `text` field |
| Customer declares | `{"type": "semantic", "language": ..., "model_type": ...}` | `PutMapping` adds `semantic_enrichment: {status: ENABLED, ...}` to an existing `text` field |
| Query contract | Customer writes `neural` / `neural_sparse` / `hybrid` explicitly | Customer keeps existing `match` queries — auto-rewritten |
| Mutable / reversible | Field type is fixed at creation (no text↔semantic swap in core today) | Toggle `ENABLED` / `DISABLED` in place, field stays `text` |
| OSS alignment | Yes — upstreamable, uses the OSS `semantic` mapper | No — managed-service-only surface |
| Typed-client support | Yes — `semantic` is a first-class OSS field type | Limited — custom mapping extension |

**Why both, and when to use which:**

- **New index → `semantic` field.** A customer creating a new index is already writing
  new mappings and new queries. They will read the OpenSearch semantic-search docs and
  learn the `neural` query type. There is therefore **no need** for automatic
  `match → neural` rewriting on this path — the customer knows they created a semantic
  field and queries it accordingly. This keeps the path clean, OSS-aligned, and free of
  a managed query-rewrite processor. This is the strategic, long-term-correct primitive.

- **Existing index → `semantic_enrichment`.** A customer with a production index cannot
  change the field type of an existing `text` field to `semantic` (OpenSearch core does
  not support a `text ↔ semantic` in-place type change today), and — more importantly —
  they do **not want to rewrite their queries**. `semantic_enrichment` is a *mutable
  attribute* on the existing `text` field. Turning it on provisions embeddings and a
  query-rewrite pipeline so that the customer's existing `match` queries transparently
  become semantic. Turning it off removes the pipelines and leaves a plain `text` field.
  This is a managed-service-only convenience that meets customers where they already are.

**Key insight driving the split:** the *automatic `match → neural` rewrite* is a cost
(a managed search pipeline, correctness edge cases, and a divergence from OSS query
semantics). We only pay that cost where it delivers real value — the existing-index path,
where the whole point is "don't touch my queries." On the new-index path we avoid it
entirely.

---

## 3. Path 1 — New Index with the `semantic` Field

### 3.1 Customer API

The customer creates an index and declares a field of type `semantic`. They do **not**
provide a `model_id`; instead they declare *intent* via `language` and `model_type`, and
the managed service resolves the concrete managed model.

```json
PUT /products
{
  "mappings": {
    "properties": {
      "description": {
        "type": "semantic",
        "language": "ENGLISH",
        "model_type": "SPARSE"
      }
    }
  }
}
```

Field parameters:

| Parameter | Values | Meaning |
|---|---|---|
| `type` | `semantic` | OSS semantic field type. |
| `language` | `ENGLISH` (Phase 1); more later | Selects the managed model family/locale. |
| `model_type` | `SPARSE` \| `DENSE` | Selects sparse (learned-sparse / neural-sparse) vs dense (vector) retrieval. |

In OSS today the `semantic` field requires an explicit `model_id`. In the managed
service, `language` + `model_type` are managed-friendly aliases that get resolved to a
managed `model_id` (see §3.2). This is the only managed-specific surface on this path,
and it is designed to be **upstreamable** as an optional resolver hook (see §3.6).

### 3.2 How it works (Option 3 — SemanticModelResolver + managed patch)

At index-creation time, the `semantic` field mapper needs a concrete `model_id` to
determine embedding type and to wire up embedding generation. We introduce a
**`SemanticModelResolver`** seam in neural-search:

```
CreateIndex / PutMapping
  └─ MapperService parses "semantic" field
       └─ SemanticFieldMapper.Builder
            └─ SemanticModelResolver.resolve(language, model_type) → model_id
                 ├─ OSS default: NoopSemanticModelResolver
                 │     - if model_id explicitly set, use it
                 │     - else fail with "model_id required"
                 └─ Managed (AOS patch): ManagedSemanticModelResolver
                       - (language=ENGLISH, model_type=SPARSE)
                            → MANAGED_INFERENCE_SPARSE_MODEL_ID
                       - (language=ENGLISH, model_type=DENSE)
                            → MANAGED_INFERENCE_DENSE_MODEL_ID
```

`SemanticModelResolver` is a small interface in neural-search with a no-op OSS default;
the managed build supplies `ManagedSemanticModelResolver` returning the well-known
managed model IDs (the same constants used by Path 2 — see §5).

Once the `model_id` is resolved, the **existing OSS semantic-field machinery** takes over
with no managed-specific changes:

1. **`SemanticMappingTransformer`** inspects the (managed) ML model to determine embedding
   type (dense vs sparse) and auto-adds the semantic-info sub-fields to the mapping — a
   `knn_vector` sub-field for dense, or a `rank_features` sub-field for sparse.
2. **`SemanticFieldProcessorFactory`** (a system ingest processor) generates embeddings
   during indexing by calling the resolved model through `MLCommonsClientAccessor`.
3. At query time, **`NeuralQueryBuilder`** auto-resolves the semantic field to the correct
   sub-field query (k-NN for dense, `rank_features`/SEISMIC for sparse).

### 3.3 Query — customer uses `neural` / `neural_sparse` explicitly

Because the customer created the semantic field and read the docs, they query it directly
with the OSS-native query types. **No managed `match → neural` rewrite is involved.**

Sparse (`model_type: SPARSE`):

```json
GET /products/_search
{
  "query": {
    "neural_sparse": {
      "description": { "query_text": "waterproof hiking boots" }
    }
  }
}
```

Dense (`model_type: DENSE`):

```json
GET /products/_search
{
  "query": {
    "neural": {
      "description": { "query_text": "waterproof hiking boots" }
    }
  }
}
```

Hybrid (dense/sparse + lexical, combined via a normalization search pipeline):

```json
GET /products/_search
{
  "query": {
    "hybrid": {
      "queries": [
        { "match":  { "description": "waterproof hiking boots" } },
        { "neural": { "description": { "query_text": "waterproof hiking boots" } } }
      ]
    }
  }
}
```

Note: the `neural` / `neural_sparse` query does **not** require the customer to specify a
`model_id` — the semantic field already carries the resolved model, and
`NeuralQueryBuilder` reads it from the field mapping. This is standard OSS semantic-field
behavior.

### 3.4 GetIndex response

Because the `semantic` field is a real OSS field type, `GET /products/_mapping` returns
the field as declared, plus the auto-generated semantic-info sub-fields. The managed
service echoes back the customer's `language` / `model_type` intent and does **not** leak
the internal managed `model_id`:

```json
{
  "products": {
    "mappings": {
      "properties": {
        "description": {
          "type": "semantic",
          "language": "ENGLISH",
          "model_type": "SPARSE"
        }
      }
    }
  }
}
```

The internal `knn_vector` / `rank_features` semantic-info sub-fields are managed by
OpenSearch under the semantic field and are not something the customer edits directly.

### 3.5 Validation rules

Enforced at `CreateIndex` / `PutMapping`:

- `language` must be a supported value (Phase 1: `ENGLISH`). Unsupported → 400.
- `model_type` must be `SPARSE` or `DENSE`. Otherwise → 400.
- If the customer supplies a raw `model_id` on a managed domain: **reject** (400) — the
  managed service owns model selection. (OSS behavior differs: OSS *requires* `model_id`.)
- `(language, model_type)` must resolve to an available managed model in the region /
  cell. If the model family isn't yet available → 400 with a clear message.
- The semantic field cannot later be changed to `text` (and vice versa) — this mirrors
  OSS core, which does not support the in-place field-type swap. Customers who need this
  use reindexing (see §10).

### 3.6 OSS alignment (upstreamable)

Everything on this path except the `language`/`model_type` → `model_id` resolution is
already OSS. The one new seam, `SemanticModelResolver`, is designed to be contributed
upstream as an **optional resolver extension point** on the semantic mapper: OSS keeps its
"explicit `model_id` required" default (`NoopSemanticModelResolver`), and deployments that
want managed model resolution register their own resolver. This keeps the managed patch
tiny and avoids forking the semantic mapper.

---

## 4. Path 2 — Existing Index with `semantic_enrichment`

### 4.1 Customer API

The customer has an existing index with a plain `text` field and existing `match` queries
in production. They enable ASE **in place** via `PutMapping`, adding a
`semantic_enrichment` attribute to the existing `text` field:

```json
PUT /logs/_mapping
{
  "properties": {
    "body": {
      "type": "text",
      "semantic_enrichment": {
        "status": "ENABLED",
        "language_option": "ENGLISH",
        "model_type": "SPARSE"
      }
    }
  }
}
```

The field **stays `text`**. `semantic_enrichment` is an *attribute* on it, not a new field
type. This is the crucial difference from Path 1 and is why it can be toggled: nothing
about the base field type changes.

This is also exposed through the AOS/AOSS Console as a per-field "Enable semantic search"
toggle, which issues the same `PutMapping` under the hood.

### 4.2 How it works

`semantic_enrichment` is a **managed-service-only** mapping extension. When the DP sees a
`PutMapping` (or `CreateIndex`) carrying `semantic_enrichment: {status: ENABLED}`, a DP
component (an **`ActionFilter`** on the mapping/index actions in the AES IAM plugin path,
mirroring how the existing SkyCrane semantic-index flow works today) provisions the
managed plumbing:

1. **Resolve the managed model** from `language_option` + `model_type` using the *same*
   `ManagedSemanticModelResolver` constants as Path 1 (§5).
2. **Create/attach an ingest pipeline** with a `sparse_encoding` processor (for
   `SPARSE`) or `text_embedding` processor (for `DENSE`) that reads `body` and writes an
   internal embedding field (`rank_features` for sparse, `knn_vector` for dense). The
   internal embedding field is added to the mapping as a hidden/managed sub-field.
3. **Create/attach a search pipeline** containing the **`SemanticSearchRewriteProcessor`**,
   a managed request processor that rewrites `match` (and other lexical) queries targeting
   `body` into the corresponding `neural` / `neural_sparse` query against the internal
   embedding field.
4. Reindex/backfill existing documents through the ingest pipeline if the customer opts in
   (or embeddings populate on the next write; a backfill job is offered for existing data).

The managed embedding sub-field, ingest pipeline, and search pipeline are **hidden managed
state** — the customer never authors or sees them directly. The `semantic_enrichment`
attribute on the `text` field is the single source of truth the customer manipulates.

This is deliberately the *same shape* as what SkyCrane / the managed control plane does
for semantic indices today, but expressed as a per-field, per-index DP action rather than
a whole-index construct — so it can be enabled selectively and reversibly.

### 4.3 Query — existing `match` queries auto-rewritten

The customer changes **nothing** about their queries. Their production `match` query:

```json
GET /logs/_search
{
  "query": {
    "match": { "body": "disk pressure on data node" }
  }
}
```

is intercepted by the attached search pipeline's `SemanticSearchRewriteProcessor` and
rewritten to:

```json
{
  "query": {
    "neural_sparse": {
      "body_semantic_embedding": {
        "query_text": "disk pressure on data node"
      }
    }
  }
}
```

(For `DENSE`, it rewrites to `neural` against the `knn_vector` sub-field.) The rewrite is
scoped to fields that carry `semantic_enrichment: ENABLED`; `match` queries on other
fields pass through unchanged. Optionally the rewrite produces a `hybrid` query combining
the original lexical `match` with the neural sub-query, so lexical recall is preserved —
this is a configurable rewrite mode.

### 4.4 Enable / disable toggle

`semantic_enrichment.status` is the on/off switch:

- **`ENABLED`** → provision embedding ingest pipeline + rewrite search pipeline + internal
  embedding sub-field (§4.2).
- **`DISABLED`** → detach and remove the managed ingest and search pipelines; stop
  rewriting. The internal embedding sub-field may be retained (to allow fast re-enable) or
  garbage-collected; the base `text` field and the customer's `match` queries revert to
  plain lexical behavior. **No customer query change is needed to disable** — once the
  rewrite pipeline is gone, `match` is just `match` again.

```json
PUT /logs/_mapping
{
  "properties": {
    "body": {
      "type": "text",
      "semantic_enrichment": { "status": "DISABLED" }
    }
  }
}
```

This reversibility is the defining advantage of Path 2 and is impossible on Path 1's
`semantic` field (which is a fixed field type).

### 4.5 GetIndex response (reconstructed from pipeline state)

Because `semantic_enrichment` is not a native core mapping attribute, the DP
**reconstructs** it on read. On `GET /logs/_mapping`, the DP inspects the attached managed
ingest/search pipelines and internal embedding sub-field for the index, and synthesizes
the `semantic_enrichment` block back onto the `text` field so the response is consistent
with what the customer wrote:

```json
{
  "logs": {
    "mappings": {
      "properties": {
        "body": {
          "type": "text",
          "semantic_enrichment": {
            "status": "ENABLED",
            "language_option": "ENGLISH",
            "model_type": "SPARSE"
          }
        }
      }
    }
  }
}
```

The internal embedding sub-field is hidden from this reconstructed view. This "reconstruct
from pipeline state" behavior is the same approach the existing managed semantic-index
flow uses, so we reuse that logic.

### 4.6 Managed-service only (not OSS)

`semantic_enrichment` is **not** contributed to OSS. It is a managed convenience that
depends on the managed inference substrate and on DP-side pipeline provisioning and
mapping reconstruction. OSS customers who want semantic search on an existing index use
the OSS-native path (add a `semantic` field / reindex). Long-term, if OSS core gains
in-place `text ↔ semantic` conversion, Path 2 can be deprecated in favor of Path 1
(see §10).

---

## 5. Shared Infrastructure

Both paths sit on **one backend**. The user-facing contracts differ; the machinery does
not.

### 5.1 Managed models (same constants for both paths)

Both `ManagedSemanticModelResolver` (Path 1) and the `semantic_enrichment` provisioner
(Path 2) resolve `(language, model_type)` to the **same** managed model constants:

| `(language, model_type)` | Managed model constant |
|---|---|
| `(ENGLISH, SPARSE)` | `MANAGED_INFERENCE_SPARSE_MODEL_ID` |
| `(ENGLISH, DENSE)`  | `MANAGED_INFERENCE_DENSE_MODEL_ID` |

These are the well-known managed model IDs registered in ML Commons in each cell/region.
Keeping a single resolver + single constant set means both paths always agree on which
model backs a given intent.

### 5.2 ML Commons / OASis (same inference)

Both paths call the same inference substrate:

- **Ingest-time embeddings**: the `sparse_encoding` / `text_embedding` processor (whether
  auto-generated by the semantic field's `SemanticFieldProcessorFactory` on Path 1, or by
  the attached managed ingest pipeline on Path 2) calls the managed model through
  `MLCommonsClientAccessor`, which routes to OASis-served inference.
- **Query-time embeddings** (dense) / sparse query encoding: `NeuralQueryBuilder` /
  `NeuralSparseQueryBuilder` call the same model through the same accessor.

No path-specific inference code exists — same model, same connector, same OASis calls.

### 5.3 k-NN plugin (same embedding storage)

Both paths store embeddings using the same primitives:

- **Sparse** (`SPARSE`) → `rank_features` (and, where enabled, the neural-sparse SEISMIC
  sparse-vector field) for learned-sparse retrieval.
- **Dense** (`DENSE`) → `knn_vector` fields backed by the k-NN plugin.

On Path 1 these are the semantic field's auto-generated semantic-info sub-fields; on Path
2 they are the internal managed embedding sub-field. Either way the storage and query
execution are identical k-NN / rank_features machinery.

---

## 6. AOS Implementation (managed domains)

### 6.1 Path 1 — Option 3 (resolver in neural-search + managed patch)

- **neural-search (OSS)**: add the `SemanticModelResolver` interface and
  `NoopSemanticModelResolver` default, wired into `SemanticFieldMapper.Builder`. Upstream
  this as an optional resolver extension point (§3.6).
- **Managed patch (AOS)**: provide `ManagedSemanticModelResolver` returning the §5.1
  managed model IDs from `(language, model_type)`. Registered via the managed plugin build
  so it replaces the no-op resolver on AOS domains.
- Validation (§3.5) — reject explicit `model_id`, validate `language`/`model_type`,
  validate model availability — lives in the managed patch's resolver / a lightweight
  mapping validator.

### 6.2 Path 2 — DP provisioning (Config Service / SkyCrane-style, in DP)

- Reuse the **existing managed semantic-index provisioning logic** (the same code path
  that today creates ingest + search pipelines and reconstructs mappings for managed
  semantic indices), but drive it from a **per-field `semantic_enrichment` attribute** on
  `PutMapping`/`CreateIndex` rather than a whole-index construct.
- A DP-side hook on the mapping actions detects `semantic_enrichment` and:
  - `ENABLED` → resolve model (§5.1), create/attach the `sparse_encoding`/`text_embedding`
    ingest pipeline and the `SemanticSearchRewriteProcessor` search pipeline, add the
    internal embedding sub-field.
  - `DISABLED` → tear those down.
- `GET _mapping` reconstruction (§4.5) reuses the existing managed reconstruction logic.

### 6.3 Config Service / control-plane touchpoints

- The set of supported `(language, model_type)` pairs and their managed model IDs is
  distributed to domains via the existing managed-model configuration mechanism (the same
  channel that publishes managed model IDs today). No new control-plane resource is needed
  for Phase 1.

---

## 7. AOSS Implementation (Serverless)

### 7.1 Path 1 — Option B (systemIngestPipelineConfig in metadata + resolver constants)

- **`ManagedSemanticModelResolver`** on AOSS returns the same managed model constants
  (§5.1) for `(language, model_type)`.
- The semantic field's system ingest processor is expressed through AOSS's
  **`systemIngestPipelineConfig`** in the index metadata (MD), so that AOSS's indexing
  pipeline generates embeddings for the semantic field without the customer authoring a
  pipeline. The rest of the semantic-field behavior (mapping transform, neural query
  resolution) is the standard OSS behavior running inside the AOSS data plane.
- `CreateIndex` for AOSS collections flows through SkyCrane's `Create/UpdateIndex` API
  (SkyCrane already supports semantic indices — see the SkyCrane index APIs); the resolver
  + `systemIngestPipelineConfig` are applied as the index metadata is materialized.

### 7.2 Path 2 — ActionFilter on the indexing/collection path

- `semantic_enrichment` on an existing AOSS index is handled by an **`ActionFilter`** on
  the index/mapping action (analogous to AOS §6.2) that provisions the managed embedding
  pipeline config and the rewrite search pipeline within the AOSS data plane, and tears
  them down on `DISABLED`.
- Because AOSS separates indexing and search fleets, the embedding-generation config
  attaches to the indexing pipeline and the rewrite processor attaches to the search
  pipeline; both are managed state keyed off the `semantic_enrichment` attribute in index
  metadata. Mapping reconstruction (§4.5) is performed when the collection's mappings are
  read back.

---

## 8. Component Changes

| Component | Path 1 (new index / `semantic`) | Path 2 (existing index / `semantic_enrichment`) |
|---|---|---|
| **neural-search (OSS)** | New `SemanticModelResolver` seam + `NoopSemanticModelResolver`; wire into `SemanticFieldMapper.Builder`. Upstreamable. | No OSS change. Reuses `sparse_encoding`/`text_embedding` processors and `neural`/`neural_sparse` queries as-is. |
| **Managed neural-search patch** | `ManagedSemanticModelResolver` (returns §5.1 constants); `model_id`/`language`/`model_type` validation. | `SemanticSearchRewriteProcessor` (managed request processor) — the `match → neural` rewriter. |
| **AOS DP (AES IAM plugin path)** | Register managed resolver via managed build. | `ActionFilter`/hook on mapping actions: provision + teardown pipelines; `_mapping` reconstruction. |
| **AOSS (SkyCrane + data plane)** | `systemIngestPipelineConfig` in index MD; managed resolver returns constants. | `ActionFilter` on index/mapping path; attach embedding config to indexing pipeline, rewrite to search pipeline; MD reconstruction. |
| **ML Commons / OASis** | No change — managed models already served. | No change — same. |
| **k-NN** | No change — `knn_vector` / `rank_features` storage as today. | No change — same. |
| **Config / control plane** | Distribute `(language, model_type) → model_id` map. | Same map; DP provisioning driven off the attribute. |

---

## 9. Phasing / Timeline

**Phase 1 — Path 1 (new index with `semantic` field) — target August.**
- `SemanticModelResolver` seam in neural-search + `ManagedSemanticModelResolver`.
- `language`/`model_type` → managed `model_id` resolution and validation.
- AOS: managed resolver registered on domains. AOSS: `systemIngestPipelineConfig` + resolver.
- Customer contract: create `semantic` field, query with `neural`/`neural_sparse`/`hybrid`.
- This is the clean, OSS-aligned MVP and unblocks all *new-index* semantic search.

**Phase 2 — Path 2 (existing index with `semantic_enrichment`).**
- Leverage the existing SkyCrane / managed semantic-index provisioning + mapping
  reconstruction logic; re-express it as the per-field `semantic_enrichment` attribute.
- Add `SemanticSearchRewriteProcessor` and the enable/disable toggle.
- Phase 2 reuses Phase 1's resolver + managed model constants, so the incremental work is
  the DP provisioning hook, the rewrite processor, and the reconstruction path — much of
  which already exists in SkyCrane today.

Sequencing rationale: Path 1 is the strategic primitive and has the smallest, cleanest
change surface, so it ships first. Path 2 is additive and reuses existing managed logic,
so it follows without blocking Path 1.

---

## 10. Migration / Deprecation Story

- **Both coexist** and serve different use cases: `semantic` for greenfield indices,
  `semantic_enrichment` for brownfield "turn it on/off" on existing `text` fields.
- **Path 2 is the eventual deprecation candidate.** `semantic_enrichment` exists because
  OpenSearch core does not support an in-place `text ↔ semantic` field-type conversion.
  If/when core gains that capability, an existing `text` field could be *upgraded* to a
  `semantic` field directly, and the managed `semantic_enrichment` shim (with its rewrite
  processor and mapping reconstruction) would no longer be needed. At that point Path 2
  can be deprecated in favor of Path 1.
- **"Upgrading" today**: a customer who starts on Path 2 (existing index) and later wants
  the clean Path 1 contract creates a **new index** with a `semantic` field and
  **reindexes** into it, then swaps an alias. This is the standard OpenSearch reindex
  pattern and needs no special ASE machinery.
- No customer is ever forced to migrate: Path 2 remains supported for as long as brownfield
  customers rely on zero-query-change enablement.

---

## 11. Comparison — Why Hybrid Beats Either Alone

### vs. `semantic`-field-only

- **No core change to support `text ↔ semantic` in place.** A `semantic`-only strategy
  would either strand existing-index customers or force a risky core change to convert
  `text` fields into `semantic` fields. Hybrid avoids that entirely: existing indices use
  `semantic_enrichment` on the unchanged `text` field.
- **No managed `match → neural` rewrite processor needed on the new-index path.** A
  `semantic`-only strategy that also wanted "don't change my queries" would have to bolt a
  rewrite processor onto the semantic field too. Hybrid confines the rewrite processor to
  Path 2, where it's genuinely needed, and keeps Path 1 pristine and OSS-aligned.
- **Reversibility.** A `semantic` field can't be turned off. `semantic_enrichment` can.

### vs. `semantic_enrichment`-only

- **OSS alignment.** `semantic_enrichment` is a managed-only surface. Building *all* new
  indices on it would mean no upstreamable path and permanent divergence from OSS. Path 1
  gives new indices the real OSS `semantic` field.
- **Typed-client support.** OSS clients and typed SDKs understand the `semantic` field
  type; they do not understand a bespoke `semantic_enrichment` mapping attribute. New
  indices on Path 1 get first-class client support.
- **No API fragmentation for new indices.** Customers reading OpenSearch docs expect the
  `semantic` field and `neural` queries. A `semantic_enrichment`-only world would force
  every new-index customer onto a non-standard, managed-only contract and a hidden rewrite,
  fragmenting the API story.

**Net:** hybrid pays the cost of the managed rewrite/enrichment machinery **only** for the
brownfield case that requires it, and gives greenfield customers the clean, standard,
upstreamable primitive — with both sharing one inference/embedding backend.

---

## 12. Open Questions

1. **Rewrite mode default (Path 2):** should `SemanticSearchRewriteProcessor` rewrite
   `match → neural` (pure semantic) or `match → hybrid(match, neural)` (preserve lexical
   recall) by default? Hybrid is safer for relevance but doubles query-time inference cost.
2. **Embedding retention on `DISABLED` (Path 2):** keep the internal embedding sub-field
   for fast re-enable, or GC it to reclaim storage? Proposal: retain for a grace period,
   then GC.
3. **Backfill on enable (Path 2):** for existing data, do we auto-trigger a reindex/backfill
   through the ingest pipeline, or only embed on next write? What's the cost/latency budget
   and how is it surfaced to the customer?
4. **Upstreaming the resolver (Path 1):** exact shape of the `SemanticModelResolver`
   extension point acceptable to the OSS community — SPI, plugin extension, or mapper
   setting?
5. **Language expansion:** Phase 1 is `ENGLISH` only. What is the ordering and model
   availability for additional `language` values, and how is per-region model availability
   validated at `CreateIndex`?
6. **`model_type` beyond SPARSE/DENSE:** do we need a `HYBRID` intent that provisions both
   sparse and dense sub-fields from a single field declaration, or do customers compose
   that themselves via `hybrid` queries?
7. **Which fields does the Path 2 rewrite scope to** when a `match` targets multiple fields
   (e.g., `multi_match`) where only some carry `semantic_enrichment`? Need precise scoping
   semantics.
8. **Consistency of managed model IDs** across cells/regions and during model version
   rollouts — how do we roll a managed model forward without re-embedding all data, and do
   both paths share the same rollout mechanism?
