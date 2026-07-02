# Comparison: `semantic` field vs `semantic_enrichment` for ASE DP

## User Story

Customer has an existing index with a `text` field. They want to:
1. Enable ASE on that field (embeddings generated for new + backfilled for old docs)
2. Later disable ASE (too expensive)
3. Possibly re-enable later

## Enable/Disable Use Case Comparison

| Dimension | `semantic` field | `semantic_enrichment` |
|---|---|---|
| **Enable ASE on existing text field** | Can't change `type: text` to `type: semantic` (types immutable). Requires: AOS core change, AOSS Phase 2 rollover + MD slim transform. Complex. | Just add `semantic_enrichment: {status: ENABLED}` to existing `text` field via PutMapping. Field stays `text`. Simple. |
| **Disable ASE on existing field** | Can't change `type: semantic` back to `type: text`. Requires core change or AOSS rollover. Orphans companion field. | Set `status: DISABLED`. Field stays `text`. Ingest stops generating embeddings. Clean. |
| **Re-enable after disable** | Another type change needed (same complexity) | Set `status: ENABLED` again. Pipeline resumes. Trivial. |
| **What happens to existing data on enable** | Old docs have no embeddings. Need backfill. | Old docs have no embeddings. Need backfill. (Same) |
| **What happens to existing data on disable** | `_semantic_info` companion field orphaned forever (wastes storage) | Embeddings stay but stop updating. Can optionally delete embedding field. |
| **Mapping change complexity (AOS)** | Core change: allow text↔semantic in FieldMapper.merge() | No core change. `semantic_enrichment` is a sub-parameter of `text` field, already mutable. |
| **Mapping change complexity (AOSS)** | Phase 2: remove PutMapping block + slim transform on MD + rollover | No rollover needed. PutMapping updates `semantic_enrichment` param. MD stores directly. |
| **Console UX** | Toggle triggers field type change (feels permanent, scary) | Toggle flips a status flag (feels like a switch, reversible) |
| **Customer mental model** | "I changed my field type" | "I turned on/off a feature on my field" |
| **Backward compatibility** | May break existing queries referencing the field differently | Existing `match` queries unaffected (field is still `text`) |
| **Storage after disable** | `_semantic_info` companion field stays in mapping + data forever | Can choose to keep or delete embedding field |
| **Implementation effort for enable/disable** | High — core/MD changes, rollover handling, companion lifecycle | Low — already a mutable sub-parameter |

## Full Feature Comparison

| Dimension | `semantic` field | `semantic_enrichment` |
|---|---|---|
| **CreateIndex (new index)** | Works (SemanticMappingTransformer) | Works (ActionFilter intercepts, creates pipelines) |
| **Ingest (embeddings)** | Works (SemanticFieldProcessor, system ingest) | Works (explicit ingest pipeline with sparse_encoding/text_embedding) |
| **Search — `neural` query** | Works (NeuralQueryBuilder auto-rewrites) | Works (via search pipeline) |
| **Search — plain `match` auto-rewrite** | NOT supported natively. Need System-Generated Search Request Processor. | Already works (SemanticSearchRewriteProcessor) |
| **Hybrid search (BM25 + neural)** | Need explicit `hybrid` query or new system processor | Already works (`type: hybrid` → auto HybridQueryBuilder) |
| **Status toggle (enable/disable)** | NOT supported. Field types immutable. Need core change. | Native — `status: ENABLED/DISABLED` |
| **GetIndex response** | Returns language/model_type from stored mapping | Needs reconstruction from pipeline state (already exists) |
| **OSS alignment** | Native OSS field type, portable | AWS-proprietary, not portable |
| **Typed-client codegen** | `SemanticProperty` exists in spec | No spec support, requires `withJson()` |
| **Open-source path** | All mechanism upstreamable | Proprietary forever |
| **Existing customers** | 0 (new experience) | 12 active customers |
| **AOSS support** | Needs Option B (systemIngestPipelineConfig) — designed but not built | Already wired through SkyCrane CP (production) |
| **model_id optional** | Yes (SemanticModelResolver resolves automatically) | Yes (language_option/model_type resolve automatically) |
| **Pipeline artifacts visible to customer** | No (system ingest processor, invisible) | No (if ActionFilter approach) / Yes (if explicit pipeline approach) |
| **Total new effort for full parity** | High: match rewrite processor + status workaround + AOSS Option B | Low-Medium: ActionFilter + proven SkyCrane pattern |

## Where Each Approach Excels

| Best for... | Winner |
|---|---|
| **New indices (greenfield)** | `semantic` field — clean, one declaration, no pipelines |
| **Enabling ASE on existing index** | `semantic_enrichment` — just add parameter, no type change |
| **Disabling ASE** | `semantic_enrichment` — flip status, clean and reversible |
| **Re-enabling ASE** | `semantic_enrichment` — flip status back |
| **OSS portability** | `semantic` field |
| **Typed-client support** | `semantic` field |
| **Ship fast (August)** | `semantic_enrichment` — less new work, proven components |
| **Console toggle UX** | `semantic_enrichment` — matches "feature switch" mental model |
| **Long-term architecture** | `semantic` field — but needs gaps closed |
| **Open-source contribution** | `semantic` field — upstreamable |

## Possible Approaches

### Approach A: Use `semantic_enrichment` only

- Ship fast, full feature parity with CP API
- Proven pattern (SkyCrane already does it)
- Sacrifice: OSS alignment, typed-client support, open-source path

### Approach B: Use `semantic` field only

- OSS-aligned, clean architecture
- Must close gaps: match rewrite processor (~2 weeks), status toggle (~2 weeks, core change)
- Risk: August timeline if gaps take longer

### Approach C: Hybrid — both

- `semantic` field for new indices (OSS-aligned, best greenfield experience)
- `semantic_enrichment` for enable/disable on existing `text` fields (Console toggle)
- Both resolve to same underlying machinery (managed models, embeddings, neural queries)
- Migration: new indices use `semantic`; existing fields use `semantic_enrichment`
- Doubles API surface but covers all use cases
- Can deprecate `semantic_enrichment` once `semantic` field gaps are closed

### Approach D: `semantic` field + `status` parameter

- Add `status` parameter to semantic field (like chunking, skip_existing_embedding)
- `status: DISABLED` → system ingest processor skips this field, query rewrite skips
- Still can't convert `text → semantic` easily (types immutable)
- Helps with disable but not with "enable on existing field"

## Recommendation

**For the "enable ASE on existing text field" use case: `semantic_enrichment` is clearly simpler.**

**For the "create new index with ASE" use case: `semantic` field is clearly better.**

If both use cases matter equally, **Approach C (hybrid)** gives the best customer experience at the cost of API surface complexity. If forced to pick one: `semantic_enrichment` ships faster and covers the Console toggle use case natively; `semantic` field is the better long-term architecture but needs more work.
