# ASE Pipeline Merge - Conflict Reference

This document catalogs all known conflicts that prevent or break ASE pipeline merging.
Intended as source material for customer-facing documentation of the DP API.

---

## Conflicts That Block Merge (4xx at enable-time)

These are detected proactively. The enable request fails with an actionable error message.

### Ingest Pipeline Conflicts

| Conflict | Detection | Error Message | Customer Fix |
|----------|-----------|---------------|--------------|
| `remove` processor targets ASE source field | Static scan of processors list | "Pipeline removes field 'X' before encoding would run" | Remove the `remove` processor, or choose a different source field for ASE |
| `rename` processor moves source field away | Static scan | "Pipeline renames 'X' to 'Y'; encoding target missing" | Use 'Y' as the ASE source field, or remove the rename |
| `text_chunking` writes output to ASE source field | Static scan of field_map values | "text_chunking writes chunks to field 'X' which ASE reads as source. After chunking, this field becomes an array and encoding will fail" | Use a different output field for chunking (e.g., 'X_chunks') |
| `remove` or `rename` in a sub-pipeline | Recursive scan through `pipeline` processor references | "[via sub-pipeline 'Y'] Pipeline removes field 'X'" | Fix the sub-pipeline, or use a different source field |
| Non-ASE `sparse_encoding` processor already exists | Tag check | "Pipeline already contains a sparse_encoding processor (not ASE-managed). Merging another may cause duplicate embeddings" | Remove existing sparse_encoding, or tag it as ase_managed if it IS ASE |
| ASE already enabled (ase_managed tag present) | Tag check on EITHER ingest or search pipeline | "Pipeline already contains ASE-managed 'X' processor. Disable ASE first before re-enabling" | Call disable API first, then re-enable with new config |

### Search Pipeline Conflicts

| Conflict | Detection | Error Message | Customer Fix |
|----------|-----------|---------------|--------------|
| Existing `semantic_search_rewrite_processor` (not ASE) | Processor type check | "Search pipeline already contains a semantic_search_rewrite_processor (not ASE-managed). Adding another would cause double-rewriting" | Remove existing rewrite processor |
| Existing `neural_sparse_two_phase_processor` (not ASE) | Processor type check | "Search pipeline already contains a neural_sparse_two_phase_processor (not ASE-managed). Adding another may cause unexpected rescore behavior" | Remove existing two_phase processor |
| ASE already enabled (ase_managed tag present) | Tag check | "Search pipeline already contains ASE-managed 'X' processor" | Call disable API first |

### Mapping Conflicts

| Conflict | Detection | Error Message | Customer Fix |
|----------|-----------|---------------|--------------|
| Source field does not exist | Mapping inspection | "Source field 'X' does not exist in index mapping" | Create the field first, or use a field that exists |
| Source field is not text type | Mapping inspection | "Source field 'X' has type 'keyword'; expected 'text'" | Choose a text-typed field |
| Embedding field exists with incompatible type | Mapping inspection | "Target embedding field 'X_embedding' already exists with type 'knn_vector'; ASE requires 'rank_features'" | Specify a different embedding field name, or remove the existing field (requires reindex) |
| Embedding field already targeted by another processor | Ingest pipeline scan | "Existing 'text_embedding' processor already writes to field 'X_sparse'" | Use a different embedding field name for ASE |

---

## Conditions That Cause Runtime Failures (NOT blocked at merge time)

These are situations where the merge succeeds but problems occur later.

### Silent Failures (worst case - no error, wrong results)

| Condition | What Happens | Why We Can't Detect It | Mitigation |
|-----------|-------------|----------------------|------------|
| Customer adds `remove` processor AFTER merge | Source field removed before encoding runs | Lifecycle: customer PUT replaces pipeline | Detect-and-repair via ClusterStateListener (deferred) |
| `script` processor does `ctx.remove('source_field')` | Same as remove, but in script | Undetectable statically (script is opaque) | Document: "scripts that remove or rename ASE source fields will break encoding" |
| Customer overwrites merged pipeline entirely | ASE processors lost | PUT is full replacement, no partial update | Detect-and-repair, or intercept-and-reinject |

### Hard Failures (documents fail to index - visible error)

| Condition | Error | When It Occurs |
|-----------|-------|---------------|
| `text_chunking` overwrites source field in-place, then `sparse_encoding` runs | `mapper_parsing_exception: rank_features fields take hashes... got unexpected token START_OBJECT` | Document index time |
| Model not deployed or unavailable | ML inference failure | Document index time |
| Embedding field mapping missing from index | Mapping error | Document index time |

---

## Conditions That Are Safe (No Conflict)

These processor types coexist safely with ASE and should NOT trigger rejection.

### Safe Ingest Processors (any order, ASE appended last)

- `lowercase`, `uppercase`, `trim` (modify text content - ASE encodes the result)
- `gsub`, `html_strip` (clean text - ASE encodes cleaned version)
- `grok`, `dissect` (extract structured data - don't remove source)
- `set`, `append`, `convert`, `date`, `fingerprint`, `sort`, `copy` (don't touch source field)
- `script` (can do anything but usually safe; undetectable conflicts)
- `rename` INTO source field (e.g., rename "body" to "text" - source field arrives)
- `text_chunking` to a DIFFERENT output field (original source remains)
- `foreach` (iterates arrays, doesn't remove source)
- `drop` (conditional doc removal - if dropped, no encoding needed)
- `pipeline` referencing a sub-pipeline that only modifies/adds fields

### Safe Search Processors

- `filter_query` (wraps query in bool.filter, doesn't touch neural_sparse leaves)
- `script` (can only modify size/from/explain - cannot access query structure)
- `neural_query_enricher` (sets default model_id on existing neural queries)
- `oversample` + `truncate_hits` (size adjustment + truncation)
- `normalization` (phase_results_processor - different stage)
- `collapse` (post-retrieval)
- `rerank` / `personalize_search_ranking` (response processors)
- `rename_field` (response processor)

---

## Query Types That Do NOT Get Semantic Enrichment

These query types pass through the search pipeline without rewriting. Customers using these will get BM25 results, not semantic results.

| Query Type | Why | Workaround |
|-----------|-----|------------|
| `multi_match` | Not instanceof MatchQueryBuilder; separate type | Restructure as `bool.should` with individual `match` per field |
| `query_string` | Parsed by Lucene into arbitrary tree; separate type | Use explicit `match` queries |
| `match_phrase` / `match_phrase_prefix` | Different type, not rewritten | Use `match` for semantic; keep `match_phrase` for exact phrase |
| `term`, `terms`, `range`, `wildcard`, `prefix`, `regexp`, `fuzzy` | Term-level queries (not full-text) | These are intentionally not semantic |
| `match` in `bool.filter` or `bool.must_not` | Design choice: filters are binary, no scoring | Move to `bool.must`/`bool.should` if semantic scoring is needed |
| `script_score` inner query | Not recursed into | Extract the match query outside script_score |
| `has_child` / `has_parent` | Not recursed into (N/A on AOSS) | N/A |

---

## Version Notes

- Sub-pipeline recursive detection: requires the enable API to resolve sub-pipeline definitions at validation time
- `text_chunking` conflict: applies when the chunking output field name equals the ASE source field name
- All conflicts are detectable at enable-time EXCEPT: script-based modifications and post-merge pipeline overwrites
