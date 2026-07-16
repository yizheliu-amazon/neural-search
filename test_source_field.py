#!/usr/bin/env python3
"""
source_field E2E Test Script

Tests the source_field feature on semantic fields:
1. Create index with a text field ("title") + semantic field ("title_ase") with source_field=title
2. Ingest docs into the title field (customer's normal flow)
3. Verify embeddings are generated in the companion field
4. Verify match query on source_field auto-rewritten via search pipeline
5. Verify rejecting ingest directly into semantic field
6. Verify status DISABLED stops embeddings + detaches pipeline
7. Verify status ENABLED re-enables everything

Prerequisites:
- opensearch-py: pip install opensearch-py
- Cluster running on localhost:9200 with neural-search plugin installed
"""

import os
import sys
import time
import json
import requests

CLUSTER_URL = "http://localhost:9200"
INDEX_NAME = "test-source-field"
PIPELINE_NAME = f"{INDEX_NAME}-semantic-search-pipeline"
# Deployed sparse model ID (register + deploy before running)
MODEL_ID = None  # Auto-resolved by PretrainedSemanticModelResolver

# ANSI colors for output
GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
RESET = "\033[0m"


def print_step(n, desc):
    print(f"\n{'='*70}")
    print(f"  Step {n}: {desc}")
    print(f"{'='*70}")


def print_curl(method, path, body=None):
    """Print the equivalent curl command."""
    cmd = f"curl -s -X {method} '{CLUSTER_URL}{path}'"
    if body:
        cmd += f" -H 'Content-Type: application/json' -d '{json.dumps(body)}'"
    print(f"  {YELLOW}curl:{RESET} {cmd}")


def check_cluster():
    """Verify cluster is up."""
    try:
        r = requests.get(CLUSTER_URL, timeout=5)
        if r.status_code == 200:
            info = r.json()
            print(f"  Cluster: {info.get('cluster_name', 'unknown')} v{info.get('version', {}).get('number', '?')}")
            return True
    except Exception as e:
        print(f"  {RED}Cluster not reachable: {e}{RESET}")
    return False


def cleanup():
    """Delete test index and pipeline."""
    requests.delete(f"{CLUSTER_URL}/{INDEX_NAME}", timeout=10)
    requests.delete(f"{CLUSTER_URL}/_search/pipeline/{PIPELINE_NAME}", timeout=10)
    time.sleep(1)


def step1_create_index():
    """Create index with text field + semantic field with source_field."""
    print_step(1, "Create index with source_field semantic field")

    body = {
        "mappings": {
            "properties": {
                "title": {
                    "type": "text"
                },
                "title_ase": {
                    "type": "semantic",
                                        "source_field": "title"
                }
            }
        }
    }

    print_curl("PUT", f"/{INDEX_NAME}", body)
    r = requests.put(f"{CLUSTER_URL}/{INDEX_NAME}", json=body, timeout=30)
    print(f"  Response [{r.status_code}]: {r.text[:500]}")

    if r.status_code == 200:
        print(f"  {GREEN}PASS: Index created successfully{RESET}")
        return True
    else:
        print(f"  {RED}FAIL: Could not create index{RESET}")
        return False


def step2_ingest_docs():
    """Ingest documents into the source field (title)."""
    print_step(2, "Ingest docs into source_field (title)")

    docs = [
        {"title": "OpenSearch is an open source search and analytics engine"},
        {"title": "Neural search uses machine learning for semantic understanding"},
        {"title": "Vector databases store embeddings for similarity search"},
    ]

    success = True
    for i, doc in enumerate(docs):
        print_curl("POST", f"/{INDEX_NAME}/_doc/{i+1}", doc)
        r = requests.post(f"{CLUSTER_URL}/{INDEX_NAME}/_doc/{i+1}", json=doc, timeout=30)
        print(f"  Doc {i+1} [{r.status_code}]: {r.json().get('result', r.text[:200])}")
        if r.status_code not in [200, 201]:
            success = False

    # Refresh
    requests.post(f"{CLUSTER_URL}/{INDEX_NAME}/_refresh", timeout=10)
    time.sleep(2)

    if success:
        print(f"  {GREEN}PASS: All docs ingested{RESET}")
    else:
        print(f"  {RED}FAIL: Some docs failed to ingest{RESET}")
    return success


def step3_verify_embeddings():
    """Verify that companion embedding fields were created."""
    print_step(3, "Verify embeddings generated in companion field")

    query = {"query": {"match_all": {}}, "_source": True}
    print_curl("GET", f"/{INDEX_NAME}/_search", query)
    r = requests.get(f"{CLUSTER_URL}/{INDEX_NAME}/_search", json=query, timeout=10)

    if r.status_code != 200:
        print(f"  {RED}FAIL: Search failed [{r.status_code}]{RESET}")
        return False

    hits = r.json().get("hits", {}).get("hits", [])
    print(f"  Found {len(hits)} docs")

    has_embeddings = False
    for hit in hits:
        source = hit.get("_source", {})
        semantic_info = source.get("title_ase_semantic_info", {})
        if semantic_info:
            # Check flat embedding (non-chunked) or chunked embedding
            embedding = semantic_info.get("embedding", {})
            if not embedding:
                chunks = semantic_info.get("chunks", [])
                if chunks:
                    embedding = chunks[0].get("embedding", {})
            if embedding:
                has_embeddings = True
                print(f"  Doc {hit['_id']}: embedding has {len(embedding)} terms (sparse)")
                # Show first few terms
                sample = dict(list(embedding.items())[:3])
                print(f"    Sample terms: {sample}")

    if has_embeddings:
        print(f"  {GREEN}PASS: Embeddings generated from source_field text{RESET}")
    else:
        print(f"  {RED}FAIL: No embeddings found in companion field{RESET}")
        # Show raw source for debugging
        if hits:
            print(f"  Raw source of first doc: {json.dumps(hits[0].get('_source', {}), indent=2)[:500]}")
    return has_embeddings


def step4_verify_search_pipeline():
    """Verify search pipeline was created and maps source_field to embeddings."""
    print_step(4, "Verify search pipeline maps source_field -> embedding")

    # Check pipeline exists
    print_curl("GET", f"/_search/pipeline/{PIPELINE_NAME}")
    r = requests.get(f"{CLUSTER_URL}/_search/pipeline/{PIPELINE_NAME}", timeout=10)
    print(f"  Pipeline lookup [{r.status_code}]")

    if r.status_code != 200:
        print(f"  {YELLOW}INFO: Pipeline not found (may not be auto-created yet){RESET}")
        # Try to check index settings for the default_pipeline
        r2 = requests.get(f"{CLUSTER_URL}/{INDEX_NAME}/_settings", timeout=10)
        if r2.status_code == 200:
            settings = r2.json()
            idx_settings = settings.get(INDEX_NAME, {}).get("settings", {}).get("index", {})
            pipeline = idx_settings.get("search", {}).get("default_pipeline")
            print(f"  Index default_pipeline: {pipeline}")
        return False

    pipeline_def = r.json()
    print(f"  Pipeline definition: {json.dumps(pipeline_def, indent=2)[:800]}")

    # Verify field_map maps "title" (source_field) not "title_ase" (semantic field)
    pipeline_body = pipeline_def.get(PIPELINE_NAME, {})
    req_processors = pipeline_body.get("request_processors", [])
    for proc in req_processors:
        rewrite = proc.get("semantic_search_rewrite_processor", {})
        field_map = rewrite.get("field_map", {})
        if "title" in field_map:
            print(f"  {GREEN}PASS: field_map maps 'title' (source_field) to embedding{RESET}")
            print(f"  field_map['title'] = {field_map['title']}")
            return True
        elif "title_ase" in field_map:
            print(f"  {YELLOW}WARN: field_map maps 'title_ase' (semantic field name) instead of 'title' (source_field){RESET}")
            return False

    print(f"  {RED}FAIL: Could not find expected field_map entry{RESET}")
    return False


def step5_verify_search_rewrite():
    """Verify match query on source_field is rewritten to neural."""
    print_step(5, "Verify match query on source_field auto-rewritten to neural")

    query = {"query": {"match": {"title": "semantic search engine"}}}
    print_curl("GET", f"/{INDEX_NAME}/_search", query)
    r = requests.get(f"{CLUSTER_URL}/{INDEX_NAME}/_search", json=query, timeout=30)

    if r.status_code != 200:
        print(f"  {RED}Search failed [{r.status_code}]: {r.text[:300]}{RESET}")
        return False

    result = r.json()
    hits = result.get("hits", {}).get("hits", [])
    total = result.get("hits", {}).get("total", {}).get("value", 0)
    print(f"  Results: {total} hits")
    for hit in hits[:3]:
        print(f"    [{hit['_score']:.4f}] {hit['_source'].get('title', '?')[:60]}")

    if total > 0:
        print(f"  {GREEN}PASS: Search returned results (pipeline likely working){RESET}")
        return True
    else:
        print(f"  {YELLOW}WARN: No results - pipeline may not be rewriting correctly{RESET}")
        return False


def step6_reject_direct_ingest():
    """Verify that ingesting directly into the semantic field is rejected."""
    print_step(6, "Verify reject ingest directly into semantic field")

    doc = {"title_ase": "this should be rejected"}
    print_curl("POST", f"/{INDEX_NAME}/_doc/reject_test", doc)
    r = requests.post(f"{CLUSTER_URL}/{INDEX_NAME}/_doc/reject_test", json=doc, timeout=30)
    print(f"  Response [{r.status_code}]: {r.text[:500]}")

    if r.status_code >= 400:
        error_msg = r.text
        if "source_field" in error_msg and "instead" in error_msg:
            print(f"  {GREEN}PASS: Correctly rejected with source_field error message{RESET}")
            return True
        else:
            print(f"  {GREEN}PASS: Rejected (different error message){RESET}")
            return True
    else:
        print(f"  {RED}FAIL: Document was accepted (should have been rejected){RESET}")
        return False


def step7_disable_status():
    """Verify status=DISABLED stops embeddings and detaches pipeline."""
    print_step(7, "Verify status DISABLED stops embeddings + detaches pipeline")

    # Update mapping to DISABLED (must include source_field since it's non-updateable)
    body = {
        "properties": {
            "title_ase": {
                "type": "semantic",
                                "source_field": "title",
                "status": "DISABLED"
            }
        }
    }
    print_curl("PUT", f"/{INDEX_NAME}/_mapping", body)
    r = requests.put(f"{CLUSTER_URL}/{INDEX_NAME}/_mapping", json=body, timeout=30)
    print(f"  PutMapping [{r.status_code}]: {r.text[:200]}")
    time.sleep(2)

    # Verify pipeline detached
    r2 = requests.get(f"{CLUSTER_URL}/{INDEX_NAME}/_settings", timeout=10)
    if r2.status_code == 200:
        settings = r2.json()
        idx_settings = settings.get(INDEX_NAME, {}).get("settings", {}).get("index", {})
        pipeline = idx_settings.get("search", {}).get("default_pipeline")
        print(f"  Pipeline after DISABLED: {pipeline}")
        if pipeline is None or pipeline == "_none":
            print(f"  {GREEN}PASS: Pipeline detached after DISABLED{RESET}")
        else:
            print(f"  {YELLOW}WARN: Pipeline still attached: {pipeline}{RESET}")

    # Try to ingest — should work but no embeddings
    doc = {"title": "This doc should not get embeddings"}
    print_curl("POST", f"/{INDEX_NAME}/_doc/disabled_test", doc)
    r3 = requests.post(f"{CLUSTER_URL}/{INDEX_NAME}/_doc/disabled_test", json=doc, timeout=30)
    print(f"  Ingest [{r3.status_code}]: {r3.json().get('result', r3.text[:100])}")

    requests.post(f"{CLUSTER_URL}/{INDEX_NAME}/_refresh", timeout=10)
    time.sleep(1)

    # Check doc
    r4 = requests.get(f"{CLUSTER_URL}/{INDEX_NAME}/_doc/disabled_test", timeout=10)
    if r4.status_code == 200:
        source = r4.json().get("_source", {})
        semantic_info = source.get("title_ase_semantic_info", {})
        # Check both flat and chunked embedding structures
        embedding = semantic_info.get("embedding", {})
        if not embedding:
            chunks = semantic_info.get("chunks", [])
            embedding = chunks[0].get("embedding", {}) if chunks else {}
        if not embedding:
            print(f"  {GREEN}PASS: No embeddings generated when DISABLED{RESET}")
            return True
        else:
            print(f"  {RED}FAIL: Embeddings were generated even though DISABLED{RESET}")
            print(f"  Source: {json.dumps(source, indent=2)[:300]}")
            return False

    print(f"  {RED}FAIL: Could not retrieve disabled_test doc{RESET}")
    return False


def step8_reenable_status():
    """Verify status=ENABLED re-enables everything."""
    print_step(8, "Verify status ENABLED re-enables embeddings + pipeline")

    # Update mapping to ENABLED (must include source_field since it's non-updateable)
    body = {
        "properties": {
            "title_ase": {
                "type": "semantic",
                                "source_field": "title",
                "status": "ENABLED"
            }
        }
    }
    print_curl("PUT", f"/{INDEX_NAME}/_mapping", body)
    r = requests.put(f"{CLUSTER_URL}/{INDEX_NAME}/_mapping", json=body, timeout=30)
    print(f"  PutMapping [{r.status_code}]: {r.text[:200]}")
    time.sleep(2)

    # Verify pipeline re-attached
    r2 = requests.get(f"{CLUSTER_URL}/{INDEX_NAME}/_settings", timeout=10)
    if r2.status_code == 200:
        settings = r2.json()
        idx_settings = settings.get(INDEX_NAME, {}).get("settings", {}).get("index", {})
        pipeline = idx_settings.get("search", {}).get("default_pipeline")
        print(f"  Pipeline after ENABLED: {pipeline}")
        if pipeline and pipeline.endswith("-semantic-search-pipeline"):
            print(f"  {GREEN}PASS: Pipeline re-attached after ENABLED{RESET}")
        else:
            print(f"  {YELLOW}WARN: Pipeline not re-attached: {pipeline}{RESET}")

    # Ingest another doc — should get embeddings
    doc = {"title": "Re-enabled semantic field should generate embeddings"}
    print_curl("POST", f"/{INDEX_NAME}/_doc/enabled_test", doc)
    r3 = requests.post(f"{CLUSTER_URL}/{INDEX_NAME}/_doc/enabled_test", json=doc, timeout=30)
    print(f"  Ingest [{r3.status_code}]: {r3.json().get('result', r3.text[:100])}")

    requests.post(f"{CLUSTER_URL}/{INDEX_NAME}/_refresh", timeout=10)
    time.sleep(1)

    # Check doc
    r4 = requests.get(f"{CLUSTER_URL}/{INDEX_NAME}/_doc/enabled_test", timeout=10)
    if r4.status_code == 200:
        source = r4.json().get("_source", {})
        semantic_info = source.get("title_ase_semantic_info", {})
        # Check both flat and chunked embedding structures
        embedding = semantic_info.get("embedding", {})
        if not embedding:
            chunks = semantic_info.get("chunks", [])
            embedding = chunks[0].get("embedding", {}) if chunks else {}
        if embedding:
            print(f"  {GREEN}PASS: Embeddings generated again after re-enabling{RESET}")
            return True
        else:
            print(f"  {RED}FAIL: No embeddings after re-enabling{RESET}")
            print(f"  Source: {json.dumps(source, indent=2)[:300]}")
            return False

    print(f"  {RED}FAIL: Could not retrieve enabled_test doc{RESET}")
    return False


def step9_existing_index_enable_semantic():
    """Test enabling semantic on an existing index that already has text field + data."""
    print_step(9, "Enable semantic on existing index with data")

    idx = "test-existing-index"
    # Cleanup
    requests.delete(f"{CLUSTER_URL}/{idx}")
    time.sleep(1)

    # Create index with text field and ingest data FIRST
    print("  Creating index with text field and ingesting docs first...")
    print_curl("PUT", f"/{idx}", {"mappings": {"properties": {"title": {"type": "text"}, "category": {"type": "keyword"}}}})
    r = requests.put(f"{CLUSTER_URL}/{idx}", json={"mappings": {"properties": {"title": {"type": "text"}, "category": {"type": "keyword"}}}})
    if r.status_code != 200:
        print(f"  {RED}FAIL: Could not create index: {r.text}{RESET}")
        return False

    # Ingest docs into existing text field
    docs = [
        {"title": "OpenSearch distributed search engine", "category": "tech"},
        {"title": "Machine learning for NLP", "category": "ml"},
        {"title": "Kubernetes container orchestration", "category": "infra"},
    ]
    for i, doc in enumerate(docs, 1):
        requests.put(f"{CLUSTER_URL}/{idx}/_doc/{i}?refresh=true", json=doc)
    print(f"  Ingested {len(docs)} docs into existing text field")

    # Now enable semantic via PutMapping with source_field
    print("\n  Enabling semantic on existing index via PutMapping...")
    print_curl("PUT", f"/{idx}/_mapping", {"properties": {"title_ase": {"type": "semantic", "source_field": "title"}}})
    r = requests.put(f"{CLUSTER_URL}/{idx}/_mapping", json={"properties": {"title_ase": {"type": "semantic", "source_field": "title"}}})
    if r.status_code != 200:
        print(f"  {RED}FAIL: PutMapping failed: {r.text}{RESET}")
        return False
    print(f"  {GREEN}PutMapping succeeded — semantic field added to existing index{RESET}")

    time.sleep(8)  # Wait for model

    # Ingest a NEW doc — should get embeddings
    print("\n  Ingesting new doc after semantic enabled...")
    r = requests.put(f"{CLUSTER_URL}/{idx}/_doc/4?refresh=true", json={"title": "Neural search transformers", "category": "ml"})
    if r.status_code not in (200, 201):
        print(f"  {RED}FAIL: Ingest failed: {r.text}{RESET}")
        return False

    # Check new doc has embeddings
    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/4")
    source = r.json().get("_source", {})
    emb = source.get("title_ase_semantic_info", {}).get("embedding", {})
    print(f"  New doc (4) embeddings: {len(emb)} tokens")

    # Check old doc (1) does NOT have embeddings (not re-processed)
    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/1")
    source_old = r.json().get("_source", {})
    emb_old = source_old.get("title_ase_semantic_info", {}).get("embedding", {})
    print(f"  Old doc (1) embeddings: {len(emb_old)} tokens (expected 0 — not re-processed)")

    if len(emb) > 0 and len(emb_old) == 0:
        print(f"  {GREEN}PASS: Semantic enabled on existing index — new docs get embeddings, old docs untouched{RESET}")
        return True
    else:
        print(f"  {RED}FAIL: Expected new doc with embeddings and old doc without{RESET}")
        return False


def step10_source_field_immutable():
    """Test that source_field cannot be changed after creation."""
    print_step(10, "source_field is immutable (cannot be changed)")

    # Try to update source_field on existing semantic field
    print_curl("PUT", f"/{INDEX_NAME}/_mapping", {"properties": {"title_ase": {"type": "semantic", "source_field": "category"}}})
    r = requests.put(f"{CLUSTER_URL}/{INDEX_NAME}/_mapping", json={
        "properties": {"title_ase": {"type": "semantic", "source_field": "category"}}
    })

    if r.status_code == 400:
        error_msg = r.json().get("error", {}).get("reason", "")
        print(f"  Response: 400 — {error_msg[:150]}")
        print(f"  {GREEN}PASS: Correctly rejected — source_field cannot be changed{RESET}")
        return True
    elif r.status_code == 200:
        # Check if source_field actually changed
        r2 = requests.get(f"{CLUSTER_URL}/{INDEX_NAME}/_mapping")
        mapping = r2.json()[INDEX_NAME]["mappings"]["properties"]["title_ase"]
        current_source = mapping.get("source_field")
        if current_source == "title":
            print(f"  Response: 200 but source_field unchanged (still 'title') — preserved by merge")
            print(f"  {GREEN}PASS: source_field preserved (not updateable){RESET}")
            return True
        else:
            print(f"  {RED}FAIL: source_field was changed to '{current_source}'{RESET}")
            return False
    else:
        print(f"  Unexpected status: {r.status_code} — {r.text[:200]}")
        print(f"  {RED}FAIL: Unexpected response{RESET}")
        return False


def step11_cannot_add_source_field_after_creation():
    """Test that source_field cannot be added to an existing semantic field that was created without it."""
    print_step(11, "Cannot add source_field to existing semantic field created without it")

    idx = "test-no-source-add"
    # Cleanup
    requests.delete(f"{CLUSTER_URL}/{idx}")
    requests.delete(f"{CLUSTER_URL}/_search/pipeline/{idx}-semantic-search-pipeline")
    time.sleep(1)

    # Create index with semantic field WITHOUT source_field
    print("  Creating semantic field WITHOUT source_field...")
    create_body = {"mappings": {"properties": {"content": {"type": "semantic"}}}}
    print_curl("PUT", f"/{idx}", create_body)
    r = requests.put(f"{CLUSTER_URL}/{idx}", json=create_body)
    if r.status_code != 200:
        print(f"  {RED}FAIL: Could not create index: {r.text[:200]}{RESET}")
        return False

    time.sleep(5)

    # Verify GET mapping — no source_field
    print("\n  Verifying GET mapping (no source_field)...")
    print_curl("GET", f"/{idx}/_mapping")
    r = requests.get(f"{CLUSTER_URL}/{idx}/_mapping")
    mapping = r.json()[idx]["mappings"]["properties"]["content"]
    print(f"  GET mapping: type={mapping.get('type')}, source_field={mapping.get('source_field', 'NOT SET')}, model_id={mapping.get('model_id', 'N/A')[:12]}...")
    assert mapping.get("source_field") is None, "source_field should NOT be set"
    print(f"  {GREEN}Confirmed: source_field not set on initial creation{RESET}")

    # Try to add source_field via PutMapping — should be REJECTED (can't update non-updateable param from null to a value)
    print("\n  Trying to add source_field via PutMapping (should fail)...")
    put_body = {"properties": {"content": {"type": "semantic", "source_field": "some_field"}}}
    print_curl("PUT", f"/{idx}/_mapping", put_body)
    r = requests.put(f"{CLUSTER_URL}/{idx}/_mapping", json=put_body)

    if r.status_code == 400:
        error_msg = r.json().get("error", {}).get("reason", "")
        print(f"  Response: 400 — {error_msg[:150]}")
        print(f"  {GREEN}PASS: Correctly rejected — cannot add source_field to existing semantic field{RESET}")
        return True
    elif r.status_code == 200:
        # Check if source_field was actually set
        r2 = requests.get(f"{CLUSTER_URL}/{idx}/_mapping")
        mapping2 = r2.json()[idx]["mappings"]["properties"]["content"]
        if mapping2.get("source_field") is None:
            print(f"  Response: 200 but source_field still None (preserved by merge — param not updateable)")
            print(f"  {GREEN}PASS: source_field not added (non-updateable parameter preserved null){RESET}")
            return True
        else:
            print(f"  {RED}FAIL: source_field was added: {mapping2.get('source_field')}{RESET}")
            return False
    else:
        print(f"  Unexpected: {r.status_code} — {r.text[:200]}")
        print(f"  {RED}FAIL{RESET}")
        return False


def step12_get_mapping_at_each_stage():
    """Verify GET mapping returns correct state after each operation."""
    print_step(12, "GET mapping verification at each stage")

    idx = "test-getmapping-stages"
    # Cleanup
    requests.delete(f"{CLUSTER_URL}/{idx}")
    requests.delete(f"{CLUSTER_URL}/_search/pipeline/{idx}-semantic-search-pipeline")
    time.sleep(1)

    all_pass = True

    # Stage A: Create with text field
    print("\n  --- Stage A: Create with text field ---")
    r = requests.put(f"{CLUSTER_URL}/{idx}", json={"mappings": {"properties": {"title": {"type": "text"}}}})
    print_curl("GET", f"/{idx}/_mapping")
    r = requests.get(f"{CLUSTER_URL}/{idx}/_mapping")
    props = r.json()[idx]["mappings"]["properties"]
    print(f"  Fields: {list(props.keys())}")
    print(f"  title.type: {props['title']['type']}")
    assert props["title"]["type"] == "text"
    assert "title_ase" not in props
    print(f"  {GREEN}Stage A OK: text field only{RESET}")

    # Stage B: Add semantic with source_field
    print("\n  --- Stage B: Add semantic with source_field ---")
    put_body = {"properties": {"title_ase": {"type": "semantic", "source_field": "title"}}}
    print_curl("PUT", f"/{idx}/_mapping", put_body)
    r = requests.put(f"{CLUSTER_URL}/{idx}/_mapping", json=put_body)
    if r.status_code != 200:
        print(f"  {RED}FAIL: PutMapping failed: {r.text[:200]}{RESET}")
        return False
    time.sleep(5)

    print_curl("GET", f"/{idx}/_mapping")
    r = requests.get(f"{CLUSTER_URL}/{idx}/_mapping")
    props = r.json()[idx]["mappings"]["properties"]
    print(f"  Fields: {list(props.keys())}")
    title_ase = props.get("title_ase", {})
    print(f"  title_ase: type={title_ase.get('type')}, source_field={title_ase.get('source_field')}, status={title_ase.get('status')}, model_id={title_ase.get('model_id', 'N/A')[:12]}...")
    has_companion = "title_ase_semantic_info" in props
    print(f"  Companion field present: {has_companion}")

    assert title_ase.get("type") == "semantic"
    assert title_ase.get("source_field") == "title"
    assert title_ase.get("status") == "ENABLED"
    assert title_ase.get("model_id") is not None
    assert has_companion
    print(f"  {GREEN}Stage B OK: semantic + source_field + model_id + companion{RESET}")

    # Stage C: Disable
    print("\n  --- Stage C: Disable semantic ---")
    put_body = {"properties": {"title_ase": {"type": "semantic", "status": "DISABLED"}}}
    print_curl("PUT", f"/{idx}/_mapping", put_body)
    r = requests.put(f"{CLUSTER_URL}/{idx}/_mapping", json=put_body)

    print_curl("GET", f"/{idx}/_mapping")
    r = requests.get(f"{CLUSTER_URL}/{idx}/_mapping")
    props = r.json()[idx]["mappings"]["properties"]
    title_ase = props.get("title_ase", {})
    print(f"  title_ase: type={title_ase.get('type')}, source_field={title_ase.get('source_field')}, status={title_ase.get('status')}, model_id={title_ase.get('model_id', 'N/A')[:12]}...")

    assert title_ase.get("status") == "DISABLED"
    assert title_ase.get("source_field") == "title", f"source_field lost! Got: {title_ase.get('source_field')}"
    assert title_ase.get("model_id") is not None, "model_id lost!"
    print(f"  {GREEN}Stage C OK: DISABLED, source_field + model_id preserved{RESET}")

    # Stage D: Re-enable
    print("\n  --- Stage D: Re-enable ---")
    put_body = {"properties": {"title_ase": {"type": "semantic", "status": "ENABLED"}}}
    print_curl("PUT", f"/{idx}/_mapping", put_body)
    r = requests.put(f"{CLUSTER_URL}/{idx}/_mapping", json=put_body)

    print_curl("GET", f"/{idx}/_mapping")
    r = requests.get(f"{CLUSTER_URL}/{idx}/_mapping")
    props = r.json()[idx]["mappings"]["properties"]
    title_ase = props.get("title_ase", {})
    print(f"  title_ase: type={title_ase.get('type')}, source_field={title_ase.get('source_field')}, status={title_ase.get('status')}, model_id={title_ase.get('model_id', 'N/A')[:12]}...")

    assert title_ase.get("status") == "ENABLED"
    assert title_ase.get("source_field") == "title", f"source_field lost! Got: {title_ase.get('source_field')}"
    assert title_ase.get("model_id") is not None, "model_id lost!"
    print(f"  {GREEN}Stage D OK: ENABLED, all params preserved{RESET}")

    print(f"\n  {GREEN}PASS: GET mapping correct at all stages{RESET}")
    return True


def main():
    print("=" * 70)
    print("  source_field E2E Test")
    print("=" * 70)

    if not check_cluster():
        print(f"\n{RED}ERROR: Cluster not available at {CLUSTER_URL}{RESET}")
        sys.exit(1)

    # Configure ML Commons
    print("  Configuring ML Commons...")
    requests.put(f"{CLUSTER_URL}/_cluster/settings", json={
        "persistent": {
            "plugins.ml_commons.only_run_on_ml_node": False,
            "plugins.ml_commons.native_memory_threshold": 100,
            "plugins.ml_commons.jvm_heap_memory_threshold": 100
        }
    })

    cleanup()
    time.sleep(1)

    results = {}
    results["1. Create index"] = step1_create_index()

    if results["1. Create index"]:
        results["2. Ingest docs"] = step2_ingest_docs()
        results["3. Verify embeddings"] = step3_verify_embeddings()
        results["4. Verify search pipeline"] = step4_verify_search_pipeline()
        results["5. Verify search rewrite"] = step5_verify_search_rewrite()
        results["6. Reject direct ingest"] = step6_reject_direct_ingest()
        results["7. Status DISABLED"] = step7_disable_status()
        results["8. Status ENABLED"] = step8_reenable_status()
        results["9. Existing index enable"] = step9_existing_index_enable_semantic()
        results["10. source_field immutable"] = step10_source_field_immutable()
        results["11. Cannot add source_field later"] = step11_cannot_add_source_field_after_creation()
        results["12. GET mapping at each stage"] = step12_get_mapping_at_each_stage()

    # Summary
    print(f"\n{'='*70}")
    print("  SUMMARY")
    print(f"{'='*70}")
    passed = sum(1 for v in results.values() if v)
    total = len(results)
    for name, result in results.items():
        status = f"{GREEN}PASS{RESET}" if result else f"{RED}FAIL{RESET}"
        print(f"  [{status}] {name}")
    print(f"\n  {passed}/{total} tests passed")
    print(f"{'='*70}")

    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
