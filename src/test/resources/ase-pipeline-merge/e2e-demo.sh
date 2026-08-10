#!/bin/bash
# ============================================================
# ASE Pipeline Merge - End-to-End Demo
# ============================================================
# Demonstrates pipeline merge behavior when enabling semantic
# enrichment on an index with existing ingest/search pipelines.
#
# Prerequisites: OpenSearch cluster running with neural-search plugin
# Usage: bash e2e-demo.sh [HOST:PORT]  (default: localhost:9200)
# ============================================================

HOST=${1:-localhost:9200}
INDEX="demo-merge-index"
CUSTOMER_INGEST_PIPELINE="customer-ingest-pipeline"
CUSTOMER_SEARCH_PIPELINE="customer-search-pipeline"
ASE_INGEST_PIPELINE="${INDEX}-ase-ingest-pipeline"
ASE_SEARCH_PIPELINE="${INDEX}-ase-search-pipeline"

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

pass() { echo -e "${GREEN}✓ PASS${NC}: $1"; }
fail() { echo -e "${RED}✗ FAIL${NC}: $1"; exit 1; }
header() { echo -e "\n${BLUE}=== $1 ===${NC}\n"; }

cleanup() {
    curl -s -X DELETE "$HOST/$INDEX" > /dev/null 2>&1
    curl -s -X DELETE "$HOST/_search/pipeline/$CUSTOMER_SEARCH_PIPELINE" > /dev/null 2>&1
    curl -s -X DELETE "$HOST/_search/pipeline/$ASE_SEARCH_PIPELINE" > /dev/null 2>&1
    curl -s -X DELETE "$HOST/_ingest/pipeline/$CUSTOMER_INGEST_PIPELINE" > /dev/null 2>&1
    curl -s -X DELETE "$HOST/_ingest/pipeline/$ASE_INGEST_PIPELINE" > /dev/null 2>&1
}

# ============================================================
header "SCENARIO 1: Merge with compatible customer pipelines (BOTH ingest + search)"
# ============================================================

cleanup

echo "1. Create customer ingest pipeline (lowercase — safe)"
curl -s -X PUT "$HOST/_ingest/pipeline/$CUSTOMER_INGEST_PIPELINE" -H 'Content-Type: application/json' -d '{
  "description": "Customer ingest pipeline",
  "processors": [{"lowercase": {"field": "title"}}]
}' | python3 -m json.tool
echo

echo "2. Create customer search pipeline (filter_query — compatible)"
curl -s -X PUT "$HOST/_search/pipeline/$CUSTOMER_SEARCH_PIPELINE" -H 'Content-Type: application/json' -d '{
  "request_processors": [
    {"filter_query": {"tag": "customer", "query": {"match_all": {}}}}
  ]
}' | python3 -m json.tool
echo

echo "3. Create index with both pipelines attached"
curl -s -X PUT "$HOST/$INDEX" -H 'Content-Type: application/json' -d "{
  \"settings\": {
    \"index.default_pipeline\": \"$CUSTOMER_INGEST_PIPELINE\",
    \"index.search.default_pipeline\": \"$CUSTOMER_SEARCH_PIPELINE\"
  },
  \"mappings\": {\"properties\": {\"title\": {\"type\": \"text\"}}}
}" | python3 -m json.tool
echo

echo "4. Enable semantic enrichment (should MERGE into both pipelines)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$HOST/_plugins/_neural/semantic/$INDEX/enable_semantic_enrichment" \
  -H 'Content-Type: application/json' -d '{
  "original_field": "title",
  "semantic_field": "title_semantic",
  "model_type": "SPARSE"
}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "$BODY" | python3 -m json.tool
if [ "$HTTP_CODE" = "200" ]; then
    pass "Enable returned 200 — merge succeeded"
else
    fail "Expected 200, got $HTTP_CODE. Body: $BODY"
fi
echo

echo "5. Verify ingest pipeline: customer processor + ASE set processor"
INGEST=$(curl -s "$HOST/_ingest/pipeline/$CUSTOMER_INGEST_PIPELINE")
echo "$INGEST" | python3 -m json.tool

if echo "$INGEST" | grep -q '"lowercase"' && echo "$INGEST" | grep -q '"set"'; then
    pass "Ingest pipeline has: lowercase (customer) + set (ASE)"
else
    fail "Ingest pipeline missing expected processors"
fi

if echo "$INGEST" | grep -q '"ase_managed"'; then
    pass "ASE processor is tagged with ase_managed"
else
    fail "ASE processor missing ase_managed tag"
fi
echo

echo "6. Verify search pipeline: ASE processors prepended + customer processor"
SEARCH=$(curl -s "$HOST/_search/pipeline/$CUSTOMER_SEARCH_PIPELINE")
echo "$SEARCH" | python3 -m json.tool

if echo "$SEARCH" | grep -q "match_to_neural_rewrite_processor" && \
   echo "$SEARCH" | grep -q "neural_sparse_two_phase_processor" && \
   echo "$SEARCH" | grep -q "filter_query"; then
    pass "Search pipeline has: match_to_neural_rewrite + neural_sparse_two_phase + filter_query"
else
    fail "Search pipeline missing expected processors"
fi
echo

echo "7. Verify index settings still point to customer pipelines (not ASE-named ones)"
SETTINGS=$(curl -s "$HOST/$INDEX/_settings")
if echo "$SETTINGS" | grep -q "$CUSTOMER_INGEST_PIPELINE" && \
   echo "$SETTINGS" | grep -q "$CUSTOMER_SEARCH_PIPELINE"; then
    pass "Index settings still reference customer pipeline names"
else
    fail "Index settings should still point to customer pipelines"
fi

# ============================================================
header "SCENARIO 2: Ingest conflict — removes source field"
# ============================================================

cleanup

echo "1. Create ingest pipeline that REMOVES the source field"
curl -s -X PUT "$HOST/_ingest/pipeline/$CUSTOMER_INGEST_PIPELINE" -H 'Content-Type: application/json' -d '{
  "processors": [{"remove": {"field": "title"}}]
}' | python3 -m json.tool
echo

echo "2. Create index with conflicting ingest pipeline"
curl -s -X PUT "$HOST/$INDEX" -H 'Content-Type: application/json' -d "{
  \"settings\": {\"index.default_pipeline\": \"$CUSTOMER_INGEST_PIPELINE\"},
  \"mappings\": {\"properties\": {\"title\": {\"type\": \"text\"}}}
}" | python3 -m json.tool
echo

echo "3. Try to enable (should be REJECTED with 409)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$HOST/_plugins/_neural/semantic/$INDEX/enable_semantic_enrichment" \
  -H 'Content-Type: application/json' -d '{
  "original_field": "title",
  "semantic_field": "title_semantic",
  "model_type": "SPARSE"
}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "$BODY" | python3 -m json.tool
if [ "$HTTP_CODE" = "409" ]; then
    pass "Enable returned 409 — ingest conflict detected"
else
    fail "Expected 409, got $HTTP_CODE"
fi

if echo "$BODY" | grep -q "removes field"; then
    pass "Error mentions 'removes field'"
else
    fail "Error should mention removes field"
fi
echo

echo "4. Verify no mapping mutation (semantic field should NOT exist)"
MAPPING=$(curl -s "$HOST/$INDEX/_mapping")
if echo "$MAPPING" | grep -q "title_semantic"; then
    fail "Semantic field should NOT have been created"
else
    pass "No mapping mutation — semantic field not created"
fi

# ============================================================
header "SCENARIO 3: Search conflict — ASE already enabled (double-enable)"
# ============================================================

cleanup

echo "1. Create index"
curl -s -X PUT "$HOST/$INDEX" -H 'Content-Type: application/json' -d '{
  "mappings": {"properties": {"title": {"type": "text"}}}
}' | python3 -m json.tool
echo

echo "2. Create search pipeline with existing ASE-managed processors"
curl -s -X PUT "$HOST/_search/pipeline/$CUSTOMER_SEARCH_PIPELINE" -H 'Content-Type: application/json' -d '{
  "request_processors": [
    {"match_to_neural_rewrite_processor": {"tag": "ase_managed", "fields": ["old_field"]}},
    {"neural_sparse_two_phase_processor": {"tag": "ase_managed", "enabled": true}}
  ]
}' | python3 -m json.tool
echo

echo "3. Attach to index"
curl -s -X PUT "$HOST/$INDEX/_settings" -H 'Content-Type: application/json' -d "{
  \"index.search.default_pipeline\": \"$CUSTOMER_SEARCH_PIPELINE\"
}" | python3 -m json.tool
echo

echo "4. Try to enable (should be REJECTED — already has ASE processors)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$HOST/_plugins/_neural/semantic/$INDEX/enable_semantic_enrichment" \
  -H 'Content-Type: application/json' -d '{
  "original_field": "title",
  "semantic_field": "title_semantic",
  "model_type": "SPARSE"
}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "$BODY" | python3 -m json.tool
if [ "$HTTP_CODE" = "409" ]; then
    pass "Enable returned 409 — double-enable detected"
else
    fail "Expected 409, got $HTTP_CODE"
fi

if echo "$BODY" | grep -q "ASE-managed"; then
    pass "Error mentions 'ASE-managed'"
else
    fail "Error should mention ASE-managed"
fi

# ============================================================
header "SCENARIO 4: Ingest conflict — ASE already enabled (double-enable)"
# ============================================================

cleanup

echo "1. Create ingest pipeline with existing ASE-managed processors"
curl -s -X PUT "$HOST/_ingest/pipeline/$CUSTOMER_INGEST_PIPELINE" -H 'Content-Type: application/json' -d '{
  "processors": [{"set": {"tag": "ase_managed", "field": "title_semantic", "value": "{{title}}"}}]
}' | python3 -m json.tool
echo

echo "2. Create index with that ingest pipeline"
curl -s -X PUT "$HOST/$INDEX" -H 'Content-Type: application/json' -d "{
  \"settings\": {\"index.default_pipeline\": \"$CUSTOMER_INGEST_PIPELINE\"},
  \"mappings\": {\"properties\": {\"title\": {\"type\": \"text\"}}}
}" | python3 -m json.tool
echo

echo "3. Try to enable (should be REJECTED — ASE already in ingest pipeline)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$HOST/_plugins/_neural/semantic/$INDEX/enable_semantic_enrichment" \
  -H 'Content-Type: application/json' -d '{
  "original_field": "title",
  "semantic_field": "title_semantic",
  "model_type": "SPARSE"
}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "$BODY" | python3 -m json.tool
if [ "$HTTP_CODE" = "409" ]; then
    pass "Enable returned 409 — ingest double-enable detected"
else
    fail "Expected 409, got $HTTP_CODE"
fi

if echo "$BODY" | grep -q "ASE-managed"; then
    pass "Error mentions 'ASE-managed'"
else
    fail "Error should mention ASE-managed"
fi

# ============================================================
header "CLEANUP"
# ============================================================
cleanup
echo -e "\n${GREEN}All scenarios completed successfully!${NC}"
