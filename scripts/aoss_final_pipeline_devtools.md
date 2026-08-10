# AOSS `index.final_pipeline` probe — Dashboards Dev Tools version

Manual equivalent of `aoss_final_pipeline_probe.py`, for when SigV4 REST is blocked
by authorization but Dashboards console access works.

**Where:** OpenSearch Dashboards Dev Tools console for the collection.
- Collection Dashboards: `https://vqhxctmqp3rmkji24eyi.us-east-1.aoss.amazonaws.com/_dashboards/app/dev_tools#/console`
- Or, from the Application UI you were browsing, look for **Dev Tools** /
  **Management → Dev Tools** (may be workspace-scoped under `/w/<workspace>/app/dev_tools`).

**Note:** AOSS does not support `refresh=true`. After indexing, wait ~5-10s before
reading the document back.

---

## Step 1 — create a marker pipeline

```
PUT _ingest/pipeline/ase-probe-final
{
  "description": "ASE probe: stamps probe_final",
  "processors": [ { "set": { "field": "probe_final", "value": "ran" } } ]
}
```

Expect `{"acknowledged": true}`. **If this 403s, Dev Tools has the same
restriction as SigV4 and the rest of this will not work.**

## Step 2 — create a plain index with NO pipeline settings

```
PUT ase-probe-existing
{
  "settings": {}
}
```

## Step 3 — THE PRIMARY QUESTION: set final_pipeline on the EXISTING index

```
PUT ase-probe-existing/_settings
{
  "index.final_pipeline": "ase-probe-final"
}
```

- `{"acknowledged": true}` -> **settable.** This is the answer ASE needs.
- 400 / error naming the setting -> **not settable on an existing index.**
- 403 -> authorization, not a real answer.

## Step 4 — confirm it persisted

```
GET ase-probe-existing/_settings
```

Look for `settings.index.final_pipeline: "ase-probe-final"`.

## Step 5 — confirm it actually RUNS (settable is not enough)

```
POST ase-probe-existing/_doc
{
  "content": "hello world"
}
```

Wait ~10s, then (substitute the `_id` returned above):

```
GET ase-probe-existing/_doc/<_id>
```

`_source` must contain `"probe_final": "ran"`. If the field is absent, the
setting persists but the pipeline is a no-op on AOSS — settable but useless.

---

## Optional extras

### Custom document ID + overwrite in place (backfill viability)

```
PUT ase-probe-existing/_doc/probe-doc-1
{
  "content": "original text"
}
```

- 201/200 -> custom document IDs work on this collection.
- 400 "Document ID is not supported in create/index operation request" ->
  first-gen behavior; backfill by overwriting a document with itself would not work.

Then overwrite the same id and confirm the pipeline re-ran:

```
PUT ase-probe-existing/_doc/probe-doc-1
{
  "content": "rewritten text"
}
```

```
GET ase-probe-existing/_doc/probe-doc-1
```

Expect `content: "rewritten text"` **and** `probe_final: "ran"`.

### Does `?pipeline=` bypass final_pipeline? (the property that motivated final)

```
PUT _ingest/pipeline/ase-probe-default
{
  "description": "ASE probe: stamps probe_default",
  "processors": [ { "set": { "field": "probe_default", "value": "ran" } } ]
}
```

```
PUT ase-probe-existing/_settings
{
  "index.default_pipeline": "ase-probe-default"
}
```

```
PUT _ingest/pipeline/ase-probe-request
{
  "description": "ASE probe: stamps probe_request",
  "processors": [ { "set": { "field": "probe_request", "value": "ran" } } ]
}
```

```
POST ase-probe-existing/_doc?pipeline=ase-probe-request
{
  "content": "override test"
}
```

Wait ~10s, GET the doc. Expected if final_pipeline behaves as designed:

| field | expected | meaning |
|---|---|---|
| `probe_request` | present | the request-level pipeline ran |
| `probe_final` | **present** | final_pipeline is NOT bypassable |
| `probe_default` | **absent** | default_pipeline WAS bypassed |

That combination is the whole reason ASE targets `final_pipeline`.

### Unset

```
PUT ase-probe-existing/_settings
{
  "index.final_pipeline": "_none"
}
```

---

## Cleanup

```
DELETE ase-probe-existing
```
```
DELETE _ingest/pipeline/ase-probe-final
```
```
DELETE _ingest/pipeline/ase-probe-default
```
```
DELETE _ingest/pipeline/ase-probe-request
```

---

# Read-only exploration (safe: no mutations)

Run these first. On this collection they double as reverse-engineering of what the
existing control-plane ASE actually deploys, since `sparse-model-test` appears to
have been produced by it (`{field}_embedding` is the CP `embedding_field`
convention, not neural-search's `{field}_semantic_info.embedding`).

## What indices exist

```
GET _cat/indices?v&s=index
```

## Does an ASE-enabled index carry pipeline settings, and which kind?

```
GET sparse-model-test/_settings
```

Look specifically for:
- `settings.index.default_pipeline`      <- CP-based ASE sets this
- `settings.index.final_pipeline`        <- would be surprising; nothing sets it today
- `settings.index.search.default_pipeline`
- `settings.index.knn`

## What does the ASE-produced mapping look like?

```
GET sparse-model-test/_mapping
```

Look for whether the enriched field carries a `semantic_enrichment` block (CP
convention, with `status` / `embedding_field` / `model_type`) versus
`"type": "semantic"` with `original_field` (the neural-search convention this
branch uses). Also note the embedding field's type (`rank_features` for sparse).

## What processors did ASE install?

Take the pipeline names from `_settings` above, then:

```
GET _ingest/pipeline/<ingest-pipeline-name-from-settings>
```
```
GET _search/pipeline/<search-pipeline-name-from-settings>
```

The ingest pipeline should reveal whether CP uses `sparse_encoding` /
`text_embedding` processors, and the search pipeline whether it uses
`semantic_search_rewrite_processor`. That tells us what convention a DP API would
need to coexist with, if coexistence matters.

Listing all pipelines is not supported on AOSS, so fetch by name:

```
GET _ingest/pipeline/_all
```
(expected to fail; use the explicit name instead)

## Compare against a non-ASE index

```
GET products-new/_settings
```
```
GET products-new/_mapping
```

## Confirm document identity behavior on existing data

```
GET sparse-model-test/_search
{
  "size": 3,
  "_source": false,
  "query": { "match_all": {} }
}
```

Auto-generated `_id`s look like `1:0:RlgPtJ4BsZHjfre0bUL5`. Customer-supplied ids
would appear verbatim. This is suggestive only, not proof of what is accepted.
