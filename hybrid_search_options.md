# Hybrid Search Support in the ASE API — Design Options

## Background

In the OSS neural-search plugin, hybrid search is expressed by the **customer writing a `hybrid` query** (a `match` sub-query + a `neural` sub-query), combined by a `normalization-processor`. The `semantic` field type is fully supported inside hybrid queries — a `neural` sub-query targeting a semantic field is a first-class hybrid sub-query, and it rewrites itself into a plain `knn` / `neural_sparse` (optionally `nested`, when chunking is enabled) query before hybridization runs.

By contrast, the AOSS managed control plane (SkyCrane `CreateIndexHandler` / `SemanticEnrichmentUtil`) exposes hybrid as a **per-field schema flag** — `semantic_enrichment.hybrid_search.enabled`, DENSE-only. The control plane translates that flag into the rewrite processor's field `type: "hybrid"` vs `"dense"`, so a plain `match` on the field is auto-expanded into a hybrid query. The customer never writes a `hybrid` query.

The ASE API POC currently sits on the OSS mechanism, so hybrid is not automatic today.

### AOSS managed behavior (reference)

- `hybrid_search.enabled` is parsed by `isHybridEnabled()`:
  - Omitted → `false` (opt-in for DENSE, unsupported for SPARSE)
  - Present without `enabled` field → ValidationException
  - `SPARSE` + `enabled: true` → ValidationException ("hybrid_search is not supported for SPARSE model_type")
- When enabled, the field name is added to `hybridFieldNames`, and the unified search rewrite processor emits `type: "hybrid"` (instead of `"dense"`) for that field's `field_map` entry.
- Ingest pipeline and index creation are unchanged; only the search rewrite processor's per-field `type` changes.

---

## Option 1 — Rewrite-processor synthesizes hybrid (mirror AOSS managed)

- Add a per-field `hybrid` flag to `enable_semantic_enrichment`, e.g.
  `{"original_field": "title", "semantic_field": "title_semantic", "hybrid": true}`.
- Teach the rewrite processor a hybrid mode: when rewriting a `match` on the field, emit a `hybrid` query = `match` on the original text field + `neural` on the semantic field.
- Requires attaching a `normalization-processor` (score combination) to the search pipeline.

**Pros**
- Fully transparent — the customer's existing `match` query becomes hybrid with no app change.
- Matches the AOSS managed UX (flag-driven).

**Cons**
- Most code.
- Rewrite processor diverges from vanilla OSS behavior → more custom code to carry, which works against the "separate plugin, avoid auto-sync conflict" goal.
- Needs normalization-pipeline wiring and score-combination configuration.

---

## Option 2 — Explicit hybrid query + hybrid-aware field replacement (recommended for POC)

- Keep hybrid as an explicit customer-written `hybrid` query (the OSS model).
- The existing `FieldReplacementProcessor` already walks `HybridQueryBuilder`, so a `match` on `title` inside a customer's `hybrid` query is already rewritten to `title_semantic` → then resolved to neural. **The query-time path already works today.**
- Remaining work: attach a `normalization-processor` to the pipeline so hybrid scores combine correctly, and document that hybrid requires an explicit `hybrid` query.

**Pros**
- Minimal code.
- Stays close to OSS; no divergent processor logic (keeps the separate-plugin path clean).

**Cons**
- Not transparent — the customer must write a `hybrid` query (not just a `match`).

---

## Open item to verify (applies to both options)

The current `enable_semantic_enrichment` builds a search pipeline with
`semantic_search_rewrite_processor` + `neural_sparse_two_phase_processor`, but **no `normalization-processor`**.
Without a normalization phase, hybrid queries will run but produce **uncombined scores**.
Either option requires adding the normalization phase to the managed search pipeline.

---

## Recommendation

For the POC, **Option 2** is the pragmatic call: hybrid works when the customer writes a `hybrid` query, the existing `FieldReplacementProcessor` already handles the field rewrite inside it, and we avoid divergent processor code. Document it as "hybrid supported via explicit hybrid query; automatic-hybrid-on-a-flag is future work (Option 1)."
