#!/bin/bash
# ============================================================
# ASE Pipeline Merge - End-to-End Demo
# ============================================================
# This script demonstrates the pipeline merge behavior when
# enabling semantic enrichment on an index that already has
# a customer-owned search pipeline.
#
# Prerequisites: OpenSearch cluster running with neural-search plugin
# Usage: bash e2e-demo.sh [HOST:PORT]  (default: localhost:9200)
# ============================================================

HOST=${1:-localhost:9200}
INDEX="demo-merge-index"
CUSTOMER_PIPELINE="customer-pipeline"
ASE_PIPELINE="${INDEX}-semantic-search-pipeline"

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

pass() { echo -e "${GREEN}✓ PASS${NC}: $1"; }
fail() { echo -e "${RED}✗ FAIL${NC}: $1"; exit 1; }
header() { echo -e "\n${BLUE}=== $1 ===${NC}\n"; }

cleanup() {
    curl -s -X DELETE "$HOST/$INDEX" > /dev/null 2>&1
    curl -s -X DELETE "$HOST/_search/pipeline/$CUSTOMER_PIPELINE" > /dev/null 2>&1
    curl -s -X DELETE "$HOST/_search/pipeline/$ASE_PIPELINE" > /dev/null 2>&1
}

# ============================================================
header "SCENARIO 1: Merge with compatible customer pipeline"
# ============================================================

cleanup

echo "1. Create index with a text field"
curl -s -X PUT "$HOST/$INDEX" -H 'Content-Type: application/json' -d '{
  "mappings": {"properties": {"title": {"type": "text"}}}
}' | python3 -m json.tool
echo

echo "2. Create customer search pipeline with filter_query"
curl -s -X PUT "$HOST/_search/pipeline/$CUSTOMER_PIPELINE" -H 'Content-Type: application/json' -d '{
  "request_processors": [
    {"filter_query": {"tag": "customer", "query": {"match_all": {}}}}
  ]
}' | python3 -m json.tool
echo

echo "3. Attach customer pipeline to index"
curl -s -X PUT "$HOST/$INDEX/_settings" -H 'Content-Type: application/json' -d "{
  \"index.search.default_pipeline\": \"$CUSTOMER_PIPELINE\"
}" | python3 -m json.tool
echo

echo "4. Enable semantic enrichment (should MERGE, not reject)"
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
    fail "Expected 200, got $HTTP_CODE"
fi
echo

echo "5. Verify merged pipeline contains BOTH ASE and customer processors"
echo "   GET /_search/pipeline/$ASE_PIPELINE"
PIPELINE=$(curl -s "$HOST/_search/pipeline/$ASE_PIPELINE")
echo "$PIPELINE" | python3 -m json.tool

# Check for ASE processors AND customer's filter_query
if echo "$PIPELINE" | grep -q "semantic_search_rewrite_processor" && \
   echo "$PIPELINE" | grep -q "neural_sparse_two_phase_processor" && \
   echo "$PIPELINE" | grep -q "filter_query"; then
    pass "Merged pipeline has: semantic_search_rewrite + neural_sparse_two_phase + filter_query"
else
    fail "Merged pipeline is missing expected processors"
fi

# ============================================================
header "SCENARIO 2: Conflict detection (ASE already enabled)"
# ============================================================

cleanup

echo "1. Create index with a text field"
curl -s -X PUT "$HOST/$INDEX" -H 'Content-Type: application/json' -d '{
  "mappings": {"properties": {"title": {"type": "text"}}}
}' | python3 -m json.tool
echo

echo "2. Create pipeline that already has ASE-managed processors"
curl -s -X PUT "$HOST/_search/pipeline/$ASE_PIPELINE" -H 'Content-Type: application/json' -d '{
  "request_processors": [
    {"semantic_search_rewrite_processor": {"tag": "ase_managed", "field_map": {"old": {"type": "sparse", "target_field": "old_emb", "model_id": "old-model"}}}},
    {"neural_sparse_two_phase_processor": {"tag": "ase_managed", "enabled": true}}
  ]
}' | python3 -m json.tool
echo

echo "3. Try to enable semantic enrichment (should be REJECTED — already enabled)"
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
    pass "Enable returned 409 — conflict detected (ASE already enabled)"
else
    fail "Expected 409, got $HTTP_CODE"
fi

if echo "$BODY" | grep -q "ASE-managed"; then
    pass "Error message mentions 'ASE-managed' — actionable for customer"
else
    fail "Error message should mention ASE-managed"
fi

# ============================================================
header "CLEANUP"
# ============================================================
cleanup
echo "Done."
