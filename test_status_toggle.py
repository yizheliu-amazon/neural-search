#!/usr/bin/env python3
"""
ASE Status Toggle E2E Test

Tests the full lifecycle of the semantic field status parameter:
  Stage 1: Create index with semantic field status=DISABLED, ingest docs (no embeddings)
  Stage 2: Enable via PutMapping status=ENABLED, ingest docs (embeddings generated)
  Stage 3: Disable via PutMapping status=DISABLED, ingest docs (no embeddings)
  Stage 4: Re-enable via PutMapping status=ENABLED, ingest docs (embeddings generated)

Verifies:
  - DISABLED: no model resolution, no companion field, no embeddings, BM25 works
  - ENABLED: model resolved, companion field created, embeddings generated, neural search works
  - Toggle preserves existing embeddings (not wiped on disable/re-enable)
  - Old docs are NOT re-processed on enable

Prerequisites:
  - OpenSearch cluster running with neural-search plugin (ase-status-parameter-poc branch)
  - ML Commons configured
  - opensearch-py installed: pip install opensearch-py
"""

import json
import sys
import time

try:
    from opensearchpy import OpenSearch
except ImportError:
    print("ERROR: pip install opensearch-py")
    sys.exit(1)

HOST = "localhost"
PORT = 9200
BASE_URL = f"http://{HOST}:{PORT}"
INDEX = "test-status-toggle"

client = OpenSearch(hosts=[{"host": HOST, "port": PORT}], timeout=180)


def print_curl(method, path, body=None):
    """Print the equivalent curl command."""
    display_path = path.replace(INDEX, "${indexName}")
    cmd = f'curl -s -X {method} "{BASE_URL}{display_path}"'
    if body:
        body_str = json.dumps(body).replace(INDEX, "${indexName}")
        cmd += f""" -H 'Content-Type: application/json' -d '{body_str}'"""
    if method == "GET" and not body:
        cmd += " | python3 -m json.tool"
    print(f"    [curl] {cmd}")
    print()


def wait_for_model(timeout=90):
    """Get model_id from mapping and wait for deployment."""
    mapping = client.indices.get_mapping(index=INDEX)
    model_id = mapping[INDEX]["mappings"]["properties"]["content"].get("model_id")
    if not model_id:
        return None
    for i in range(timeout):
        try:
            resp = client.transport.perform_request("GET", f"/_plugins/_ml/models/{model_id}")
            if resp.get("model_state") == "DEPLOYED":
                return model_id
        except Exception:
            pass
        time.sleep(1)
    print(f"    WARNING: Model {model_id} not deployed after {timeout}s")
    return model_id


def check_doc_embeddings(doc_id):
    """Check if a doc has embeddings."""
    doc = client.get(index=INDEX, id=doc_id)
    emb = doc["_source"].get("content_semantic_info", {}).get("embedding", {})
    return len(emb)


def stage1_create_disabled():
    """Stage 1: Create with semantic DISABLED, ingest docs 1-3."""
    print("\n" + "=" * 70)
    print("STAGE 1: Create index with semantic field (status=DISABLED)")
    print("=" * 70)

    create_body = {
        "mappings": {
            "properties": {
                "content": {
                    "type": "semantic",
                    "status": "DISABLED"
                }
            }
        }
    }
    print_curl("PUT", f"/{INDEX}", create_body)
    resp = client.indices.create(index=INDEX, body=create_body)
    print(f"    Response: {json.dumps(resp)}")

    # Verify mapping
    print_curl("GET", f"/{INDEX}/_mapping")
    mapping = client.indices.get_mapping(index=INDEX)
    props = mapping[INDEX]["mappings"]["properties"]
    content = props.get("content", {})
    print(f"    type: {content.get('type')}")
    print(f"    status: {content.get('status')}")
    print(f"    model_id: {content.get('model_id', 'NONE')}")
    print(f"    has companion: {'content_semantic_info' in props}")

    assert content.get("type") == "semantic"
    assert content.get("status") == "DISABLED"
    assert content.get("model_id") is None
    assert "content_semantic_info" not in props

    # Ingest docs
    print("\n    Ingesting docs 1-3...")
    docs = [
        {"content": "Stage 1 doc 1: OpenSearch distributed search engine"},
        {"content": "Stage 1 doc 2: Full text search and analytics"},
        {"content": "Stage 1 doc 3: Index and query documents"},
    ]
    for i, doc in enumerate(docs, 1):
        print_curl("PUT", f"/{INDEX}/_doc/{i}?refresh=true", doc)
        resp = client.index(index=INDEX, id=i, body=doc, refresh=True)
        print(f"    Response: result={resp.get('result')}")

    # Verify no embeddings
    print_curl("GET", f"/{INDEX}/_doc/1")
    doc1 = client.get(index=INDEX, id=1)
    print(f"    Doc 1 source: {json.dumps(doc1['_source'])}")
    assert "content_semantic_info" not in doc1["_source"]
    print("    ✅ No embeddings (as expected for DISABLED)")

    # BM25 works
    search_body = {"query": {"match": {"content": "search engine"}}}
    print_curl("POST", f"/{INDEX}/_search", search_body)
    results = client.search(index=INDEX, body=search_body)
    hits = results["hits"]["hits"]
    print(f"    BM25 hits: {len(hits)}")
    assert len(hits) > 0
    print("    ✅ BM25 search works on DISABLED field")


def stage2_enable():
    """Stage 2: Enable via PutMapping, ingest docs 4-6."""
    print("\n" + "=" * 70)
    print("STAGE 2: Enable semantic field (status=ENABLED)")
    print("=" * 70)

    put_body = {"properties": {"content": {"type": "semantic", "status": "ENABLED"}}}
    print_curl("PUT", f"/{INDEX}/_mapping", put_body)
    resp = client.indices.put_mapping(index=INDEX, body=put_body)
    print(f"    Response: {json.dumps(resp)}")

    # Verify mapping updated
    print_curl("GET", f"/{INDEX}/_mapping")
    mapping = client.indices.get_mapping(index=INDEX)
    props = mapping[INDEX]["mappings"]["properties"]
    content = props.get("content", {})
    print(f"    type: {content.get('type')}")
    print(f"    status: {content.get('status', 'ENABLED (default)')}")
    print(f"    model_id: {content.get('model_id', 'NONE')}")
    print(f"    has companion: {'content_semantic_info' in props}")

    assert content.get("model_id") is not None
    assert "content_semantic_info" in props

    # Wait for model
    model_id = wait_for_model()
    print(f"    Model ready: {model_id}")
    assert model_id is not None

    # Ingest docs
    print("\n    Ingesting docs 4-6...")
    docs = [
        {"content": "Stage 2 doc 4: Neural search with deep learning"},
        {"content": "Stage 2 doc 5: Semantic similarity matching"},
        {"content": "Stage 2 doc 6: Transformer models for NLP"},
    ]
    for i, doc in enumerate(docs, 4):
        print_curl("PUT", f"/{INDEX}/_doc/{i}?refresh=true", doc)
        resp = client.index(index=INDEX, id=i, body=doc, refresh=True)
        print(f"    Response: result={resp.get('result')}")

    # Verify new docs have embeddings
    tokens_4 = check_doc_embeddings(4)
    tokens_1 = check_doc_embeddings(1)
    print(f"\n    Doc 4 (stage 2): {tokens_4} tokens")
    print(f"    Doc 1 (stage 1, not re-processed): {tokens_1} tokens")
    assert tokens_4 > 0, "Stage 2 doc should have embeddings"
    assert tokens_1 == 0, "Stage 1 doc should NOT have embeddings (not re-processed)"
    print("    ✅ New docs have embeddings, old docs unchanged")

    # Neural search works
    search_body = {
        "query": {
            "neural_sparse": {
                "content_semantic_info.embedding": {
                    "query_text": "deep learning search",
                    "model_id": model_id
                }
            }
        }
    }
    print_curl("POST", f"/{INDEX}/_search", search_body)
    results = client.search(index=INDEX, body=search_body)
    hits = results["hits"]["hits"]
    print(f"    Neural search hits: {len(hits)}")
    for h in hits[:3]:
        print(f"      doc {h['_id']}: score={h['_score']:.3f} | {h['_source']['content'][:45]}")
    assert len(hits) > 0
    print("    ✅ Neural search works on ENABLED field")

    return model_id


def stage3_disable():
    """Stage 3: Disable via PutMapping, ingest docs 7-9."""
    print("\n" + "=" * 70)
    print("STAGE 3: Disable semantic field (status=DISABLED)")
    print("=" * 70)

    put_body = {"properties": {"content": {"type": "semantic", "status": "DISABLED"}}}
    print_curl("PUT", f"/{INDEX}/_mapping", put_body)
    resp = client.indices.put_mapping(index=INDEX, body=put_body)
    print(f"    Response: {json.dumps(resp)}")

    # Verify status updated
    print_curl("GET", f"/{INDEX}/_mapping")
    mapping = client.indices.get_mapping(index=INDEX)
    content = mapping[INDEX]["mappings"]["properties"]["content"]
    print(f"    status: {content.get('status')}")
    assert content.get("status") == "DISABLED"

    # Ingest docs
    print("\n    Ingesting docs 7-9...")
    docs = [
        {"content": "Stage 3 doc 7: Kubernetes container orchestration"},
        {"content": "Stage 3 doc 8: Docker microservices deployment"},
        {"content": "Stage 3 doc 9: Cloud infrastructure management"},
    ]
    for i, doc in enumerate(docs, 7):
        print_curl("PUT", f"/{INDEX}/_doc/{i}?refresh=true", doc)
        resp = client.index(index=INDEX, id=i, body=doc, refresh=True)
        print(f"    Response: result={resp.get('result')}")

    # Verify no embeddings on new docs
    tokens_7 = check_doc_embeddings(7)
    tokens_4 = check_doc_embeddings(4)
    print(f"\n    Doc 7 (stage 3): {tokens_7} tokens")
    print(f"    Doc 4 (stage 2, preserved): {tokens_4} tokens")
    assert tokens_7 == 0, "Stage 3 doc should NOT have embeddings"
    assert tokens_4 > 0, "Stage 2 doc embeddings should be preserved"
    print("    ✅ No embeddings on new docs, old embeddings preserved")


def stage4_reenable(model_id):
    """Stage 4: Re-enable via PutMapping, ingest docs 10-12."""
    print("\n" + "=" * 70)
    print("STAGE 4: Re-enable semantic field (status=ENABLED)")
    print("=" * 70)

    put_body = {"properties": {"content": {"type": "semantic", "status": "ENABLED"}}}
    print_curl("PUT", f"/{INDEX}/_mapping", put_body)
    resp = client.indices.put_mapping(index=INDEX, body=put_body)
    print(f"    Response: {json.dumps(resp)}")

    time.sleep(3)

    # Ingest docs
    print("\n    Ingesting docs 10-12...")
    docs = [
        {"content": "Stage 4 doc 10: Vector database similarity search"},
        {"content": "Stage 4 doc 11: Embedding models for retrieval"},
        {"content": "Stage 4 doc 12: Approximate nearest neighbor algorithms"},
    ]
    for i, doc in enumerate(docs, 10):
        print_curl("PUT", f"/{INDEX}/_doc/{i}?refresh=true", doc)
        resp = client.index(index=INDEX, id=i, body=doc, refresh=True)
        print(f"    Response: result={resp.get('result')}")

    # Verify embeddings on new docs
    tokens_10 = check_doc_embeddings(10)
    tokens_7 = check_doc_embeddings(7)
    tokens_4 = check_doc_embeddings(4)
    print(f"\n    Doc 10 (stage 4): {tokens_10} tokens")
    print(f"    Doc 7 (stage 3, still no embeddings): {tokens_7} tokens")
    print(f"    Doc 4 (stage 2, still has embeddings): {tokens_4} tokens")
    assert tokens_10 > 0, "Stage 4 doc should have embeddings"
    assert tokens_7 == 0, "Stage 3 doc should still NOT have embeddings"
    assert tokens_4 > 0, "Stage 2 doc should still have embeddings"
    print("    ✅ New docs have embeddings, previous states preserved")

    # Neural search finds stage 2 + 4 docs
    search_body = {
        "query": {
            "neural_sparse": {
                "content_semantic_info.embedding": {
                    "query_text": "search engine",
                    "model_id": model_id
                }
            }
        }
    }
    print_curl("POST", f"/{INDEX}/_search", search_body)
    results = client.search(index=INDEX, body=search_body)
    hits = results["hits"]["hits"]
    print(f"    Neural search hits: {len(hits)}")
    for h in hits[:6]:
        print(f"      doc {h['_id']}: score={h['_score']:.3f} | {h['_source']['content'][:45]}")
    # Doc 6 ("Transformer models for NLP") has embeddings but no token overlap with "search engine" query
    # so neural sparse returns 5 hits, not 6 — this is expected sparse matching behavior
    assert len(hits) == 5, f"Expected 5 hits (stage 2 + 4 docs with token overlap), got {len(hits)}"
    # Verify no stage 1 or stage 3 docs appear (they have no embeddings)
    hit_ids = {int(h["_id"]) for h in hits}
    stage1_3_ids = {1, 2, 3, 7, 8, 9}
    unexpected = hit_ids & stage1_3_ids
    assert not unexpected, f"Docs without embeddings should not appear in neural search: {unexpected}"
    print("    ✅ Neural search finds 5 docs (only from stages with embeddings, 1 excluded due to no token overlap)")


def print_summary():
    """Print final summary table."""
    print("\n" + "=" * 70)
    print("SUMMARY: Embedding status per document")
    print("=" * 70)
    print(f"    {'Doc':<5} {'Stage':<18} {'Has Embeddings':<16} {'Tokens'}")
    print(f"    {'---':<5} {'---':<18} {'---':<16} {'---'}")
    for doc_id in range(1, 13):
        tokens = check_doc_embeddings(doc_id)
        stage = (
            "1-disabled" if doc_id <= 3
            else "2-enabled" if doc_id <= 6
            else "3-disabled" if doc_id <= 9
            else "4-re-enabled"
        )
        has = "YES" if tokens > 0 else "NO"
        print(f"    {doc_id:<5} {stage:<18} {has:<16} {tokens}")

    print("\n    Expected pattern:")
    print("    Stage 1 (DISABLED):    NO embeddings  ✅")
    print("    Stage 2 (ENABLED):     YES embeddings ✅")
    print("    Stage 3 (DISABLED):    NO embeddings  ✅")
    print("    Stage 4 (RE-ENABLED):  YES embeddings ✅")


def main():
    print("=" * 70)
    print("ASE Status Toggle E2E Test")
    print("=" * 70)

    # Check cluster
    try:
        health = client.cluster.health()
        print(f"Cluster: {health['status']}")
    except Exception as e:
        print(f"ERROR: Cannot connect to cluster: {e}")
        sys.exit(1)

    # Configure ML
    print("\n>>> Configuring ML Commons...")
    ml_settings = {
        "persistent": {
            "plugins.ml_commons.only_run_on_ml_node": False,
            "plugins.ml_commons.native_memory_threshold": 100,
            "plugins.ml_commons.jvm_heap_memory_threshold": 100
        }
    }
    print_curl("PUT", "/_cluster/settings", ml_settings)
    resp = client.cluster.put_settings(body=ml_settings)
    print(f"    Response: acknowledged={resp.get('acknowledged')}")

    # Cleanup
    try:
        client.indices.delete(index=INDEX, ignore=[404])
    except Exception:
        pass

    # Run stages
    stage1_create_disabled()
    model_id = stage2_enable()
    stage3_disable()
    stage4_reenable(model_id)
    print_summary()

    print("\n" + "=" * 70)
    print("ALL TESTS PASSED ✅")
    print("=" * 70)


if __name__ == "__main__":
    main()
