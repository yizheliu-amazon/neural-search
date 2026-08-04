#!/bin/bash
# ASE Pipeline Merge - Integration Tests (OSS-compatible)
#
# Tests pipeline merge patterns using processors available in OSS OpenSearch 3.7.0.
# The semantic_search_rewrite_processor is AOS-only, so we test with:
# - neural_sparse_two_phase_processor (ASE search processor - available in OSS)
# - filter_query, script (customer search processors)
# - sparse_encoding (ASE ingest processor)
# - lowercase, gsub, rename, remove (customer ingest processors)
#
# The merge PATTERN is identical regardless of which ASE processor is used.

set -e
MODEL_ID=$(cat /tmp/ase_model_id.txt)
BASE="http://localhost:9200"
PASS=0
FAIL=0
TOTAL=0

pass() { PASS=$((PASS+1)); TOTAL=$((TOTAL+1)); echo "  PASS: $1"; }
fail() { FAIL=$((FAIL+1)); TOTAL=$((TOTAL+1)); echo "  FAIL: $1 -- $2"; }

cleanup() {
    # Explicit pipeline cleanup (wildcards dont work for pipeline API)
    for idx in "$@"; do
        curl -s -X DELETE "$BASE/$idx" > /dev/null 2>&1
    done
    curl -s -X DELETE "$BASE/_ingest/pipeline/$1*" > /dev/null 2>&1
    curl -s -X DELETE "$BASE/_search/pipeline/$1*" > /dev/null 2>&1
}

assert_eq() { [ "$1" = "$2" ] && pass "$3" || fail "$3" "expected='$2' got='$1'"; }
assert_gt() { [ "$1" -gt "$2" ] 2>/dev/null && pass "$3" || fail "$3" "expected>$2 got='$1'"; }

# ============================================================================
echo "=== SCENARIO 1: Creation-time merge - customer ingest + ASE ingest ==="
echo "    (Customer specifies pipeline in CreateIndex body)"
# ============================================================================
cleanup "it1"
sleep 1
curl -s -X DELETE "$BASE/_ingest/pipeline/it1-merged" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_search/pipeline/it1-search" > /dev/null 2>&1

# Merged ingest: customer lowercase + ASE sparse_encoding
curl -s -X PUT "$BASE/_ingest/pipeline/it1-merged" -H 'Content-Type: application/json' -d "{
  \"description\": \"Merged: customer + ASE\",
  \"processors\": [
    {\"lowercase\": {\"field\": \"text\", \"tag\": \"customer\"}},
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" | python3 -c "import sys,json; assert json.load(sys.stdin).get('acknowledged')" 2>/dev/null || fail "Pipeline create"

# Merged search: ASE two_phase + customer filter_query
curl -s -X PUT "$BASE/_search/pipeline/it1-search" -H 'Content-Type: application/json' -d '{
  "request_processors": [
    {"neural_sparse_two_phase_processor": {"tag": "ase_managed", "enabled": true, "two_phase_parameter": {"prune_ratio": 0.4, "expansion_rate": 5, "max_window_size": 500}}},
    {"filter_query": {"tag": "customer", "query": {"term": {"status": "published"}}}}
  ]
}' | python3 -c "import sys,json; assert json.load(sys.stdin).get('acknowledged')" 2>/dev/null || fail "Pipeline create"

# Create index referencing both pipelines in the body
RESP=$(curl -s -X PUT "$BASE/it1" -H 'Content-Type: application/json' -d '{
  "settings": {"default_pipeline": "it1-merged", "index.search.default_pipeline": "it1-search"},
  "mappings": {"properties": {"text": {"type": "text"}, "status": {"type": "keyword"}, "text_sparse": {"type": "rank_features"}}}
}')
echo "$RESP" | python3 -c "import sys,json; assert json.load(sys.stdin).get('acknowledged')" 2>/dev/null && \
  pass "Index created with merged pipelines in body" || fail "Index creation" "$RESP"

# Ingest docs
curl -s -X POST "$BASE/_bulk?refresh=true" -H 'Content-Type: application/x-ndjson' -d '
{"index":{"_index":"it1","_id":"1"}}
{"text":"Machine Learning Algorithms","status":"published"}
{"index":{"_index":"it1","_id":"2"}}
{"text":"Natural Language Processing","status":"published"}
{"index":{"_index":"it1","_id":"3"}}
{"text":"Deep Neural Networks","status":"draft"}
' > /dev/null

# Verify ingest: lowercased + sparse tokens
DOC=$(curl -s "$BASE/it1/_doc/1")
TEXT=$(echo "$DOC" | python3 -c "import sys,json; print(json.load(sys.stdin)['_source']['text'])")
TOKENS=$(echo "$DOC" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['_source'].get('text_sparse',{})))")
assert_eq "$TEXT" "machine learning algorithms" "Customer lowercase ran"
assert_gt "$TOKENS" 0 "ASE sparse_encoding produced tokens ($TOKENS)"

# Verify search: filter_query + two_phase both active
SEARCH=$(curl -s -X POST "$BASE/it1/_search" -H 'Content-Type: application/json' -d "{
  \"query\": {\"neural_sparse\": {\"text_sparse\": {\"query_text\": \"machine learning\", \"model_id\": \"$MODEL_ID\"}}}
}")
HITS=$(echo "$SEARCH" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['hits']['total']['value'])")
ALL_PUB=$(echo "$SEARCH" | python3 -c "
import sys,json
hits=json.load(sys.stdin)['hits']['hits']
print(all(h['_source']['status']=='published' for h in hits))")
assert_gt "$HITS" 0 "Search returns results ($HITS hits)"
assert_eq "$ALL_PUB" "True" "Customer filter_query applied (only published)"

# ============================================================================
echo ""
echo "=== SCENARIO 2: Post-creation merge - modify existing pipeline ==="
# ============================================================================
cleanup "it2"
sleep 1
curl -s -X DELETE "$BASE/_ingest/pipeline/it2-ingest" > /dev/null 2>&1

# Create customer pipeline FIRST
curl -s -X PUT "$BASE/_ingest/pipeline/it2-ingest" -H 'Content-Type: application/json' -d '{
  "description": "Customer HTML cleanup",
  "processors": [{"gsub": {"field": "text", "pattern": "<[^>]+>", "replacement": ""}}]
}' > /dev/null

# Create index using customer pipeline
curl -s -X PUT "$BASE/it2" -H 'Content-Type: application/json' -d '{
  "settings": {"default_pipeline": "it2-ingest"},
  "mappings": {"properties": {"text": {"type": "text"}, "text_sparse": {"type": "rank_features"}}}
}' > /dev/null

# Simulate merge: GET -> append ASE -> PUT
MERGED=$(curl -s "$BASE/_ingest/pipeline/it2-ingest" | python3 -c "
import sys,json
p = json.load(sys.stdin)['it2-ingest']
p['processors'].append({'sparse_encoding': {'tag': 'ase_managed', 'model_id': '$MODEL_ID', 'field_map': {'text': 'text_sparse'}}})
print(json.dumps(p))")
curl -s -X PUT "$BASE/_ingest/pipeline/it2-ingest" -H 'Content-Type: application/json' -d "$MERGED" > /dev/null

# Index doc with HTML
curl -s -X PUT "$BASE/it2/_doc/1?refresh=true" -H 'Content-Type: application/json' \
  -d '{"text":"<p>Graph neural networks</p> for <b>relational</b> data"}' > /dev/null

DOC=$(curl -s "$BASE/it2/_doc/1")
TEXT=$(echo "$DOC" | python3 -c "import sys,json; print(json.load(sys.stdin)['_source']['text'])")
TOKENS=$(echo "$DOC" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['_source'].get('text_sparse',{})))")
HAS_HTML=$(echo "$TEXT" | grep -c "<" || true)
assert_eq "$HAS_HTML" "0" "Customer gsub stripped HTML"
assert_gt "$TOKENS" 0 "ASE encoded cleaned text ($TOKENS tokens)"

# ============================================================================
echo ""
echo "=== SCENARIO 3: Conflict - remove source field = silent failure ==="
# ============================================================================
cleanup "it3"
sleep 1
curl -s -X DELETE "$BASE/_ingest/pipeline/it3-bad" > /dev/null 2>&1

curl -s -X PUT "$BASE/_ingest/pipeline/it3-bad" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"remove\": {\"field\": \"text\"}},
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" > /dev/null

curl -s -X PUT "$BASE/it3" -H 'Content-Type: application/json' -d '{
  "settings": {"default_pipeline": "it3-bad"},
  "mappings": {"properties": {"text": {"type": "text"}, "text_sparse": {"type": "rank_features"}}}
}' > /dev/null

curl -s -X PUT "$BASE/it3/_doc/1?refresh=true" -H 'Content-Type: application/json' \
  -d '{"text":"This will produce empty embeddings"}' > /dev/null

TOKENS=$(curl -s "$BASE/it3/_doc/1" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['_source'].get('text_sparse',{})))")
assert_eq "$TOKENS" "0" "Remove before encoding = 0 tokens (silent failure confirmed)"

# ============================================================================
echo ""
echo "=== SCENARIO 4: Conflict - rename source field away = silent failure ==="
# ============================================================================
cleanup "it4"
sleep 1
curl -s -X DELETE "$BASE/_ingest/pipeline/it4-bad" > /dev/null 2>&1

curl -s -X PUT "$BASE/_ingest/pipeline/it4-bad" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"rename\": {\"field\": \"text\", \"target_field\": \"original_text\"}},
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" > /dev/null

curl -s -X PUT "$BASE/it4" -H 'Content-Type: application/json' -d '{
  "settings": {"default_pipeline": "it4-bad"},
  "mappings": {"properties": {"text": {"type": "text"}, "original_text": {"type": "text"}, "text_sparse": {"type": "rank_features"}}}
}' > /dev/null

curl -s -X PUT "$BASE/it4/_doc/1?refresh=true" -H 'Content-Type: application/json' \
  -d '{"text":"This text gets renamed away"}' > /dev/null

TOKENS=$(curl -s "$BASE/it4/_doc/1" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['_source'].get('text_sparse',{})))")
assert_eq "$TOKENS" "0" "Rename away before encoding = 0 tokens (silent failure confirmed)"

# ============================================================================
echo ""
echo ""
echo "=== SCENARIO 5: Multi-field ASE + customer processors ==="
# ============================================================================
curl -s -X DELETE "$BASE/it5" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it5-ingest" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_search/pipeline/it5-search" > /dev/null 2>&1
sleep 1

curl -s -X PUT "$BASE/_ingest/pipeline/it5-ingest" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"lowercase\": {\"field\": \"title\"}},
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"title\": \"title_sparse\", \"body\": \"body_sparse\"}}}
  ]
}" > /dev/null

curl -s -X PUT "$BASE/_search/pipeline/it5-search" -H 'Content-Type: application/json' -d '{
  "request_processors": [
    {"filter_query": {"tag": "customer", "query": {"range": {"price": {"gte": 10}}}}}
  ]
}' > /dev/null

curl -s -X PUT "$BASE/it5" -H 'Content-Type: application/json' -d '{
  "settings": {"default_pipeline": "it5-ingest", "index.search.default_pipeline": "it5-search"},
  "mappings": {"properties": {"title": {"type": "text"}, "body": {"type": "text"}, "price": {"type": "float"}, "title_sparse": {"type": "rank_features"}, "body_sparse": {"type": "rank_features"}}}
}' > /dev/null

curl -s -X POST "$BASE/_bulk?refresh=true" -H 'Content-Type: application/x-ndjson' -d '
{"index":{"_index":"it5","_id":"1"}}
{"title":"KUBERNETES GUIDE","body":"Container orchestration at scale","price":29.99}
{"index":{"_index":"it5","_id":"2"}}
{"title":"FREE INTRO","body":"Basics of cloud computing","price":0}
' > /dev/null

DOC=$(curl -s "$BASE/it5/_doc/1")
T_TOK=$(echo "$DOC" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['_source'].get('title_sparse',{})))")
B_TOK=$(echo "$DOC" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['_source'].get('body_sparse',{})))")
TITLE=$(echo "$DOC" | python3 -c "import sys,json; print(json.load(sys.stdin)['_source']['title'])")
assert_eq "$TITLE" "kubernetes guide" "Customer lowercase on title"
assert_gt "$T_TOK" 0 "Title encoded ($T_TOK tokens)"
assert_gt "$B_TOK" 0 "Body encoded ($B_TOK tokens)"

# Search - filter should exclude doc 2 (price < 10)
SEARCH=$(curl -s -X POST "$BASE/it5/_search" -H 'Content-Type: application/json' -d "{
  \"query\": {\"neural_sparse\": {\"body_sparse\": {\"query_text\": \"container orchestration\", \"model_id\": \"$MODEL_ID\"}}}
}")
HITS=$(echo "$SEARCH" | python3 -c "import sys,json; print(json.load(sys.stdin)['hits']['total']['value'])")
assert_eq "$HITS" "1" "Price filter excluded free doc (1 paid result)"

echo ""
echo "=== SCENARIO 6: Lifecycle - overwrite destroys ASE processors ==="
# ============================================================================
cleanup "it6"
sleep 1
curl -s -X DELETE "$BASE/_search/pipeline/it6-search" > /dev/null 2>&1

curl -s -X PUT "$BASE/_search/pipeline/it6-search" -H 'Content-Type: application/json' -d '{
  "request_processors": [
    {"neural_sparse_two_phase_processor": {"tag": "ase_managed", "enabled": true}},
    {"filter_query": {"tag": "customer", "query": {"term": {"status": "active"}}}}
  ]
}' > /dev/null

# Customer overwrites
curl -s -X PUT "$BASE/_search/pipeline/it6-search" -H 'Content-Type: application/json' -d '{
  "request_processors": [
    {"filter_query": {"tag": "customer_v2", "query": {"term": {"status": "active"}}}}
  ]
}' > /dev/null

ASE_COUNT=$(curl -s "$BASE/_search/pipeline/it6-search" | python3 -c "
import sys,json
p=json.load(sys.stdin)['it6-search']
count=sum(1 for pr in p.get('request_processors',[]) for v in pr.values() if isinstance(v,dict) and v.get('tag')=='ase_managed')
print(count)")
assert_eq "$ASE_COUNT" "0" "Customer PUT removes ASE processors (lifecycle risk)"

# ============================================================================
echo ""
echo "=== SCENARIO 7: ASE tag detection for idempotency ==="
# ============================================================================
cleanup "it7"
sleep 1
curl -s -X DELETE "$BASE/_search/pipeline/it7-search" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it7-ingest" > /dev/null 2>&1

curl -s -X PUT "$BASE/_search/pipeline/it7-search" -H 'Content-Type: application/json' -d '{
  "request_processors": [
    {"neural_sparse_two_phase_processor": {"tag": "ase_managed", "enabled": true}},
    {"filter_query": {"tag": "customer", "query": {"term": {"active": "true"}}}}
  ]
}' > /dev/null

curl -s -X PUT "$BASE/_ingest/pipeline/it7-ingest" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"lowercase\": {\"field\": \"text\"}},
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" > /dev/null

# Detect ASE in search pipeline
SEARCH_ASE=$(curl -s "$BASE/_search/pipeline/it7-search" | python3 -c "
import sys,json
p=json.load(sys.stdin)['it7-search']
tags=[v.get('tag') for pr in p.get('request_processors',[]) for v in pr.values() if isinstance(v,dict)]
print('ase_managed' in tags)")

# Detect ASE in ingest pipeline
INGEST_ASE=$(curl -s "$BASE/_ingest/pipeline/it7-ingest" | python3 -c "
import sys,json
p=json.load(sys.stdin)['it7-ingest']
tags=[v.get('tag') for pr in p.get('processors',[]) for v in pr.values() if isinstance(v,dict)]
print('ase_managed' in tags)")

assert_eq "$SEARCH_ASE" "True" "ASE detected in search pipeline via tag"
assert_eq "$INGEST_ASE" "True" "ASE detected in ingest pipeline via tag"

# ============================================================================

# ============================================================================
echo ""
echo "=== SCENARIO 8: Reject - ASE already enabled (ase_managed tag present) ==="
# Simulate: caller tries to enable ASE but pipeline already has ase_managed processors
# ============================================================================
curl -s -X DELETE "$BASE/_ingest/pipeline/it8-ingest" > /dev/null 2>&1
sleep 1

# Pipeline that already has ASE
curl -s -X PUT "$BASE/_ingest/pipeline/it8-ingest" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"lowercase\": {\"field\": \"text\"}},
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" > /dev/null

# Read pipeline and check if merge should be rejected
PIPELINE=$(curl -s "$BASE/_ingest/pipeline/it8-ingest")
SHOULD_REJECT=$(echo "$PIPELINE" | python3 -c "
import sys,json
p = json.load(sys.stdin)['it8-ingest']
procs = p.get('processors', [])
has_ase = any(
    v.get('tag') == 'ase_managed'
    for proc in procs
    for v in proc.values()
    if isinstance(v, dict)
)
print(has_ase)")
assert_eq "$SHOULD_REJECT" "True" "Reject: ASE already enabled detected in ingest pipeline"

# Same for search pipeline
curl -s -X DELETE "$BASE/_search/pipeline/it8-search" > /dev/null 2>&1
curl -s -X PUT "$BASE/_search/pipeline/it8-search" -H 'Content-Type: application/json' -d '{
  "request_processors": [
    {"neural_sparse_two_phase_processor": {"tag": "ase_managed", "enabled": true}},
    {"filter_query": {"tag": "customer", "query": {"term": {"status": "active"}}}}
  ]
}' > /dev/null

PIPELINE=$(curl -s "$BASE/_search/pipeline/it8-search")
SHOULD_REJECT=$(echo "$PIPELINE" | python3 -c "
import sys,json
p = json.load(sys.stdin)['it8-search']
procs = p.get('request_processors', [])
has_ase = any(
    v.get('tag') == 'ase_managed'
    for proc in procs
    for v in proc.values()
    if isinstance(v, dict)
)
print(has_ase)")
assert_eq "$SHOULD_REJECT" "True" "Reject: ASE already enabled detected in search pipeline"

# ============================================================================
echo ""
echo "=== SCENARIO 9: Reject - existing non-ASE sparse_encoding in pipeline ==="
# ============================================================================
curl -s -X DELETE "$BASE/_ingest/pipeline/it9-ingest" > /dev/null 2>&1
sleep 1

# Customer has their own sparse_encoding (not ASE-managed)
curl -s -X PUT "$BASE/_ingest/pipeline/it9-ingest" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"sparse_encoding\": {\"model_id\": \"$MODEL_ID\", \"field_map\": {\"description\": \"desc_sparse\"}}}
  ]
}" > /dev/null

PIPELINE=$(curl -s "$BASE/_ingest/pipeline/it9-ingest")
SHOULD_REJECT=$(echo "$PIPELINE" | python3 -c "
import sys,json
p = json.load(sys.stdin)['it9-ingest']
procs = p.get('processors', [])
has_non_ase_sparse = any(
    'sparse_encoding' in proc and (
        not isinstance(proc['sparse_encoding'], dict)
        or proc['sparse_encoding'].get('tag') != 'ase_managed'
    )
    for proc in procs
)
print(has_non_ase_sparse)")
assert_eq "$SHOULD_REJECT" "True" "Reject: non-ASE sparse_encoding detected"

# ============================================================================
echo ""
echo "=== SCENARIO 10: Reject - remove processor targets ASE source field ==="
# ============================================================================
curl -s -X DELETE "$BASE/_ingest/pipeline/it10-ingest" > /dev/null 2>&1
sleep 1

curl -s -X PUT "$BASE/_ingest/pipeline/it10-ingest" -H 'Content-Type: application/json' -d '{
  "processors": [
    {"set": {"field": "processed", "value": true}},
    {"remove": {"field": "text"}}
  ]
}' > /dev/null

# Validation: scan for remove/rename conflicts
PIPELINE=$(curl -s "$BASE/_ingest/pipeline/it10-ingest")
CONFLICT=$(echo "$PIPELINE" | python3 -c "
import sys,json
p = json.load(sys.stdin)['it10-ingest']
source_fields = {'text'}
conflicts = []
for proc in p.get('processors', []):
    if 'remove' in proc:
        config = proc['remove']
        field = config.get('field')
        if isinstance(field, str) and field in source_fields:
            conflicts.append(f\"remove targets source field '{field}'\")
        elif isinstance(field, list):
            for f in field:
                if f in source_fields:
                    conflicts.append(f\"remove targets source field '{f}'\")
    if 'rename' in proc:
        config = proc['rename']
        field = config.get('field')
        target = config.get('target_field')
        if field in source_fields and target not in source_fields:
            conflicts.append(f\"rename '{field}' -> '{target}'\")
print(len(conflicts) > 0)")
assert_eq "$CONFLICT" "True" "Reject: remove on source field 'text' detected"

# ============================================================================
echo ""
echo "=== SCENARIO 11: Reject - rename processor moves source field away ==="
# ============================================================================
curl -s -X DELETE "$BASE/_ingest/pipeline/it11-ingest" > /dev/null 2>&1
sleep 1

curl -s -X PUT "$BASE/_ingest/pipeline/it11-ingest" -H 'Content-Type: application/json' -d '{
  "processors": [
    {"rename": {"field": "text", "target_field": "raw_text"}},
    {"set": {"field": "status", "value": "processed"}}
  ]
}' > /dev/null

PIPELINE=$(curl -s "$BASE/_ingest/pipeline/it11-ingest")
CONFLICT=$(echo "$PIPELINE" | python3 -c "
import sys,json
p = json.load(sys.stdin)['it11-ingest']
source_fields = {'text'}
for proc in p.get('processors', []):
    if 'rename' in proc:
        f = proc['rename'].get('field')
        t = proc['rename'].get('target_field')
        if f in source_fields and t not in source_fields:
            print(True)
            sys.exit()
print(False)")
assert_eq "$CONFLICT" "True" "Reject: rename 'text' -> 'raw_text' detected"

# ============================================================================
echo ""
echo "=== SCENARIO 12: Reject - embedding field type collision ==="
# Customer already has title_embedding as knn_vector, ASE wants rank_features
# ============================================================================
curl -s -X DELETE "$BASE/it12" > /dev/null 2>&1
sleep 1

curl -s -X PUT "$BASE/it12" -H 'Content-Type: application/json' -d '{
  "settings": {"index.knn": true},
  "mappings": {"properties": {
    "title": {"type": "text"},
    "title_embedding": {"type": "knn_vector", "dimension": 3, "method": {"name": "hnsw", "engine": "lucene"}}
  }}
}' > /dev/null

# Validation: check if target embedding field has incompatible type
MAPPING=$(curl -s "$BASE/it12/_mapping")
CONFLICT=$(echo "$MAPPING" | python3 -c "
import sys,json
props = json.load(sys.stdin)['it12']['mappings']['properties']
# ASE wants to use 'title_embedding' as rank_features
target = 'title_embedding'
expected_type = 'rank_features'
if target in props:
    actual_type = props[target].get('type','unknown')
    if actual_type != expected_type:
        print(f'True:{actual_type}')
    else:
        print('False')
else:
    print('False')")
echo "$CONFLICT" | grep -q "True" && \
  pass "Reject: title_embedding exists as $(echo $CONFLICT | cut -d: -f2), ASE needs rank_features" || \
  fail "Should detect type collision" "$CONFLICT"

# ============================================================================
echo ""
echo "=== SCENARIO 13: Reject - embedding field already targeted by another processor ==="
# ============================================================================
curl -s -X DELETE "$BASE/_ingest/pipeline/it13-ingest" > /dev/null 2>&1
sleep 1

# Customer has text_embedding processor writing to "text_sparse" 
curl -s -X PUT "$BASE/_ingest/pipeline/it13-ingest" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"text_embedding\": {\"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" > /dev/null

PIPELINE=$(curl -s "$BASE/_ingest/pipeline/it13-ingest")
CONFLICT=$(echo "$PIPELINE" | python3 -c "
import sys,json
p = json.load(sys.stdin)['it13-ingest']
ase_target_fields = {'text_sparse'}  # what ASE wants to write to
for proc in p.get('processors', []):
    for ptype, config in proc.items():
        if ptype in ('sparse_encoding', 'text_embedding') and isinstance(config, dict):
            field_map = config.get('field_map', {})
            for target in field_map.values():
                if target in ase_target_fields:
                    print(True)
                    sys.exit()
print(False)")
assert_eq "$CONFLICT" "True" "Reject: text_embedding already writes to 'text_sparse'"

# ============================================================================
echo ""
echo "=== SCENARIO 14: Allow - rename INTO source field (not a conflict) ==="
# ============================================================================
curl -s -X DELETE "$BASE/it14" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it14-ingest" > /dev/null 2>&1
sleep 1

# Rename "body" -> "text" means "text" ARRIVES, which is what ASE reads
curl -s -X PUT "$BASE/_ingest/pipeline/it14-ingest" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"rename\": {\"field\": \"body\", \"target_field\": \"text\"}},
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" > /dev/null

curl -s -X PUT "$BASE/it14" -H 'Content-Type: application/json' -d '{
  "settings": {"default_pipeline": "it14-ingest"},
  "mappings": {"properties": {"text": {"type": "text"}, "body": {"type": "text"}, "text_sparse": {"type": "rank_features"}}}
}' > /dev/null

curl -s -X PUT "$BASE/it14/_doc/1?refresh=true" -H 'Content-Type: application/json' \
  -d '{"body": "Transfer learning reduces training time for models"}' > /dev/null

DOC=$(curl -s "$BASE/it14/_doc/1")
TOKENS=$(echo "$DOC" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['_source'].get('text_sparse',{})))")
TEXT=$(echo "$DOC" | python3 -c "import sys,json; print(json.load(sys.stdin)['_source'].get('text','MISSING'))")
assert_gt "$TOKENS" 0 "Allow: rename into source field works ($TOKENS tokens)"

# ============================================================================
echo ""
echo "=== SCENARIO 15: Reject - ASE tag in ONLY ingest (search is clean) ==="
# ============================================================================
curl -s -X DELETE "$BASE/_ingest/pipeline/it15-ingest" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_search/pipeline/it15-search" > /dev/null 2>&1
sleep 1

# Ingest HAS ase_managed
curl -s -X PUT "$BASE/_ingest/pipeline/it15-ingest" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" > /dev/null

# Search does NOT have ase_managed
curl -s -X PUT "$BASE/_search/pipeline/it15-search" -H 'Content-Type: application/json' -d '{
  "request_processors": [
    {"filter_query": {"tag": "customer", "query": {"term": {"status": "active"}}}}
  ]
}' > /dev/null

# Check ingest -- should reject
INGEST_REJECT=$(curl -s "$BASE/_ingest/pipeline/it15-ingest" | python3 -c "
import sys,json
p = json.load(sys.stdin)['it15-ingest']
has_ase = any(v.get('tag')=='ase_managed' for proc in p.get('processors',[]) for v in proc.values() if isinstance(v,dict))
print(has_ase)")
assert_eq "$INGEST_REJECT" "True" "Reject: ASE in ingest only - still detected"

# Check search -- should NOT reject
SEARCH_REJECT=$(curl -s "$BASE/_search/pipeline/it15-search" | python3 -c "
import sys,json
p = json.load(sys.stdin)['it15-search']
has_ase = any(v.get('tag')=='ase_managed' for proc in p.get('request_processors',[]) for v in proc.values() if isinstance(v,dict))
print(has_ase)")
assert_eq "$SEARCH_REJECT" "False" "Search pipeline is clean (no ASE tag)"

# Overall decision: reject because EITHER has ASE
EITHER=$(python3 -c "print($INGEST_REJECT or $SEARCH_REJECT)")

# ============================================================================
echo ""
echo "=== SCENARIO 16: Reject - sub-pipeline contains remove of source field ==="
# ============================================================================
curl -s -X DELETE "$BASE/it16" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it16-sub" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it16-parent" > /dev/null 2>&1
sleep 1

# Sub-pipeline removes the text field
curl -s -X PUT "$BASE/_ingest/pipeline/it16-sub" -H 'Content-Type: application/json' -d '{
  "processors": [{"remove": {"field": "text"}}]
}' > /dev/null

# Parent pipeline delegates to sub-pipeline
curl -s -X PUT "$BASE/_ingest/pipeline/it16-parent" -H 'Content-Type: application/json' -d '{
  "processors": [
    {"set": {"field": "processed", "value": true}},
    {"pipeline": {"name": "it16-sub"}}
  ]
}' > /dev/null

# Validation: resolve sub-pipeline and detect conflict
PARENT=$(curl -s "$BASE/_ingest/pipeline/it16-parent" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)['it16-parent']))")
SUB=$(curl -s "$BASE/_ingest/pipeline/it16-sub" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)['it16-sub']))")

CONFLICT=$(python3 -c "
import sys, json

parent = json.loads('$PARENT')
sub_pipelines = {'it16-sub': json.loads('$SUB')}
source_fields = {'text'}

def resolve(name):
    return sub_pipelines.get(name)

def scan_conflicts(processors, source_fields, resolver, visited=None):
    if visited is None:
        visited = set()
    conflicts = []
    for proc in processors:
        for ptype, config in proc.items():
            if not isinstance(config, dict):
                continue
            if ptype == 'remove':
                field = config.get('field')
                if isinstance(field, str) and field in source_fields:
                    conflicts.append(f\"remove targets '{field}'\")
                elif isinstance(field, list):
                    for f in field:
                        if f in source_fields:
                            conflicts.append(f\"remove targets '{f}'\")
            elif ptype == 'rename':
                f = config.get('field')
                t = config.get('target_field')
                if f in source_fields and t not in source_fields:
                    conflicts.append(f\"rename '{f}' -> '{t}'\")
            elif ptype == 'pipeline':
                sub_name = config.get('name')
                if sub_name and sub_name not in visited and resolver:
                    visited.add(sub_name)
                    sub_def = resolver(sub_name)
                    if sub_def:
                        sub_procs = sub_def.get('processors', [])
                        sub_conflicts = scan_conflicts(sub_procs, source_fields, resolver, visited)
                        for c in sub_conflicts:
                            conflicts.append(f\"[via sub-pipeline '{sub_name}'] {c}\")
    return conflicts

conflicts = scan_conflicts(parent.get('processors', []), source_fields, resolve)
print(len(conflicts) > 0)
for c in conflicts:
    print(f'  {c}', file=sys.stderr)
")
assert_eq "$CONFLICT" "True" "Reject: sub-pipeline 'it16-sub' removes source field (recursive detection)"

# Prove the silent failure actually happens
curl -s -X PUT "$BASE/_ingest/pipeline/it16-merged" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"set\": {\"field\": \"processed\", \"value\": true}},
    {\"pipeline\": {\"name\": \"it16-sub\"}},
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" > /dev/null

curl -s -X PUT "$BASE/it16" -H 'Content-Type: application/json' -d '{
  "settings": {"default_pipeline": "it16-merged"},
  "mappings": {"properties": {"text": {"type": "text"}, "text_sparse": {"type": "rank_features"}}}
}' > /dev/null

curl -s -X PUT "$BASE/it16/_doc/1?refresh=true" -H 'Content-Type: application/json' \
  -d '{"text": "This will be removed by sub-pipeline"}' > /dev/null

TOKENS=$(curl -s "$BASE/it16/_doc/1" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['_source'].get('text_sparse',{})))")
assert_eq "$TOKENS" "0" "Proved: sub-pipeline remove causes 0 tokens (why we must check recursively)"

# ============================================================================
echo ""
echo "=== SCENARIO 17: Allow - sub-pipeline modifies but doesn't remove source field ==="
# ============================================================================
curl -s -X DELETE "$BASE/it17" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it17-sub" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it17-parent" > /dev/null 2>&1
sleep 1

# Sub-pipeline lowercases the text field (safe)
curl -s -X PUT "$BASE/_ingest/pipeline/it17-sub" -H 'Content-Type: application/json' -d '{
  "processors": [{"lowercase": {"field": "text"}}]
}' > /dev/null

# Parent delegates to safe sub-pipeline
curl -s -X PUT "$BASE/_ingest/pipeline/it17-parent" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"pipeline\": {\"name\": \"it17-sub\"}},
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" > /dev/null

curl -s -X PUT "$BASE/it17" -H 'Content-Type: application/json' -d '{
  "settings": {"default_pipeline": "it17-parent"},
  "mappings": {"properties": {"text": {"type": "text"}, "text_sparse": {"type": "rank_features"}}}
}' > /dev/null

curl -s -X PUT "$BASE/it17/_doc/1?refresh=true" -H 'Content-Type: application/json' \
  -d '{"text": "UPPERCASE TEXT Gets Lowercased By Sub-Pipeline"}' > /dev/null

DOC=$(curl -s "$BASE/it17/_doc/1")
TEXT=$(echo "$DOC" | python3 -c "import sys,json; print(json.load(sys.stdin)['_source']['text'])")
TOKENS=$(echo "$DOC" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['_source'].get('text_sparse',{})))")
assert_eq "$TEXT" "uppercase text gets lowercased by sub-pipeline" "Sub-pipeline lowercase ran"
assert_gt "$TOKENS" 0 "Allow: safe sub-pipeline + ASE works ($TOKENS tokens)"

# ============================================================================
echo ""
echo "=== SCENARIO 18: Reject - nested sub-pipeline (pipeline -> pipeline -> remove) ==="
# ============================================================================
curl -s -X DELETE "$BASE/it18" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it18-leaf" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it18-mid" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it18-top" > /dev/null 2>&1
sleep 1

# Leaf: removes field
curl -s -X PUT "$BASE/_ingest/pipeline/it18-leaf" -H 'Content-Type: application/json' -d '{
  "processors": [{"remove": {"field": "text"}}]
}' > /dev/null

# Mid: delegates to leaf
curl -s -X PUT "$BASE/_ingest/pipeline/it18-mid" -H 'Content-Type: application/json' -d '{
  "processors": [{"pipeline": {"name": "it18-leaf"}}]
}' > /dev/null

# Top: delegates to mid
curl -s -X PUT "$BASE/_ingest/pipeline/it18-top" -H 'Content-Type: application/json' -d '{
  "processors": [
    {"set": {"field": "level", "value": "top"}},
    {"pipeline": {"name": "it18-mid"}}
  ]
}' > /dev/null

# Validation with recursive resolution
TOP=$(curl -s "$BASE/_ingest/pipeline/it18-top" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)['it18-top']))")
MID=$(curl -s "$BASE/_ingest/pipeline/it18-mid" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)['it18-mid']))")
LEAF=$(curl -s "$BASE/_ingest/pipeline/it18-leaf" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)['it18-leaf']))")

CONFLICT=$(python3 -c "
import sys, json

pipelines = {
    'it18-top': json.loads('$TOP'),
    'it18-mid': json.loads('$MID'),
    'it18-leaf': json.loads('$LEAF')
}
source_fields = {'text'}

def resolve(name):
    return pipelines.get(name)

def scan(processors, src, resolver, visited=None):
    if visited is None:
        visited = set()
    conflicts = []
    for proc in processors:
        for ptype, config in proc.items():
            if not isinstance(config, dict): continue
            if ptype == 'remove':
                field = config.get('field')
                if isinstance(field, str) and field in src:
                    conflicts.append(f\"remove targets '{field}'\")
            elif ptype == 'rename':
                f, t = config.get('field'), config.get('target_field')
                if f in src and t not in src:
                    conflicts.append(f\"rename '{f}' -> '{t}'\")
            elif ptype == 'pipeline':
                sub = config.get('name')
                if sub and sub not in visited and resolver:
                    visited.add(sub)
                    sub_def = resolver(sub)
                    if sub_def:
                        for c in scan(sub_def.get('processors', []), src, resolver, visited):
                            conflicts.append(f\"[via '{sub}'] {c}\")
    return conflicts

conflicts = scan(pipelines['it18-top'].get('processors', []), source_fields, resolve)
print(len(conflicts) > 0)
")

# ============================================================================
echo ""
echo "=== SCENARIO 19: Allow - text_chunking to different field + ASE on original ==="
# The common safe pattern: chunk to separate field, encode the original
# ============================================================================
curl -s -X DELETE "$BASE/it19" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it19-ingest" > /dev/null 2>&1
sleep 1

curl -s -X PUT "$BASE/_ingest/pipeline/it19-ingest" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"text_chunking\": {\"field_map\": {\"text\": \"text_chunks\"}, \"algorithm\": {\"fixed_token_length\": {\"token_limit\": 10, \"overlap\": 2}}}},
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" > /dev/null

curl -s -X PUT "$BASE/it19" -H 'Content-Type: application/json' -d '{
  "settings": {"default_pipeline": "it19-ingest"},
  "mappings": {"properties": {"text": {"type": "text"}, "text_chunks": {"type": "text"}, "text_sparse": {"type": "rank_features"}}}
}' > /dev/null

curl -s -X PUT "$BASE/it19/_doc/1?refresh=true" -H 'Content-Type: application/json' -d '{
  "text": "Machine learning is a subset of artificial intelligence that enables systems to learn from data. Neural networks are inspired by biological neurons and can model complex patterns."
}' > /dev/null

DOC=$(curl -s "$BASE/it19/_doc/1")
TOKENS=$(echo "$DOC" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['_source'].get('text_sparse',{})))")
CHUNKS=$(echo "$DOC" | python3 -c "import sys,json; d=json.load(sys.stdin)['_source']; print(len(d.get('text_chunks',[])) if isinstance(d.get('text_chunks'),list) else 0)")
TEXT_TYPE=$(echo "$DOC" | python3 -c "import sys,json; print(type(json.load(sys.stdin)['_source']['text']).__name__)")

assert_gt "$TOKENS" 0 "ASE encoded original text ($TOKENS tokens)"
assert_gt "$CHUNKS" 1 "text_chunking produced $CHUNKS chunks"
assert_eq "$TEXT_TYPE" "str" "Original text field remains a string (not array)"

# ============================================================================
echo ""
echo "=== SCENARIO 20: Fail - text_chunking overwrites source field in-place ==="
# If chunking writes back to same field, text becomes array, sparse_encoding
# produces array output, rank_features rejects it at index time.
# This is a HARD failure (not silent) - documents fail to index.
# ============================================================================
curl -s -X DELETE "$BASE/it20" > /dev/null 2>&1
curl -s -X DELETE "$BASE/_ingest/pipeline/it20-ingest" > /dev/null 2>&1
sleep 1

curl -s -X PUT "$BASE/_ingest/pipeline/it20-ingest" -H 'Content-Type: application/json' -d "{
  \"processors\": [
    {\"text_chunking\": {\"field_map\": {\"text\": \"text\"}, \"algorithm\": {\"fixed_token_length\": {\"token_limit\": 10, \"overlap\": 2}}}},
    {\"sparse_encoding\": {\"tag\": \"ase_managed\", \"model_id\": \"$MODEL_ID\", \"field_map\": {\"text\": \"text_sparse\"}}}
  ]
}" > /dev/null

curl -s -X PUT "$BASE/it20" -H 'Content-Type: application/json' -d '{
  "settings": {"default_pipeline": "it20-ingest"},
  "mappings": {"properties": {"text": {"type": "text"}, "text_sparse": {"type": "rank_features"}}}
}' > /dev/null

# This should FAIL at index time
RESP=$(curl -s -X PUT "$BASE/it20/_doc/1" -H 'Content-Type: application/json' -d '{
  "text": "Machine learning is a subset of artificial intelligence that enables systems to learn."
}')
HAS_ERROR=$(echo "$RESP" | python3 -c "import sys,json; print('error' in json.load(sys.stdin))")
ERROR_MSG=$(echo "$RESP" | python3 -c "
import sys,json
r=json.load(sys.stdin)
if 'error' in r:
    reason = r['error'].get('caused_by',{}).get('reason', r['error'].get('reason',''))
    print(reason[:100])
else:
    print('no error')")

assert_eq "$HAS_ERROR" "True" "Hard fail: text_chunking in-place + sparse_encoding = index error"

# ============================================================================
echo ""
echo "=== SCENARIO 21: Reject - text_chunking overwrites ASE source field ==="
# ============================================================================
curl -s -X DELETE "$BASE/_ingest/pipeline/it21-ingest" > /dev/null 2>&1
sleep 1

curl -s -X PUT "$BASE/_ingest/pipeline/it21-ingest" -H 'Content-Type: application/json' -d '{
  "processors": [
    {"text_chunking": {"field_map": {"text": "text"}, "algorithm": {"fixed_token_length": {"token_limit": 10}}}}
  ]
}' > /dev/null

PIPELINE=$(curl -s "$BASE/_ingest/pipeline/it21-ingest")
CONFLICT=$(echo "$PIPELINE" | python3 -c "
import sys,json
p = json.load(sys.stdin)['it21-ingest']
source_fields = {'text'}
for proc in p.get('processors', []):
    if 'text_chunking' in proc:
        fm = proc['text_chunking'].get('field_map', {})
        for src, tgt in fm.items():
            if tgt in source_fields:
                print(True)
                sys.exit()
print(False)")
assert_eq "$CONFLICT" "True" "Reject: text_chunking writes to ASE source field 'text' (would become array)"

echo "RESULTS: $PASS passed, $FAIL failed, $TOTAL total"
echo "================================================================"
[ "$FAIL" = "0" ] && exit 0 || exit 1
