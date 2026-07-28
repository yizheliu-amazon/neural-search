#!/usr/bin/env python3
"""
Semantic Enable API E2E Test Script

Tests the semantic enrichment lifecycle APIs:
  POST /_plugins/_neural/semantic/{index}/enable_semantic_enrichment
  POST /_plugins/_neural/semantic/{index}/disable_semantic_enrichment
  POST /_plugins/_neural/semantic/{index}/deploy_semantic_enrichment
  POST /_plugins/_neural/semantic/{index}/rollback_semantic_enrichment
  GET  /_plugins/_neural/semantic/{index}/list_semantic_enrichment

Design notes (post _rename_active removal):
  - Enrichment always runs in COPY mode: processor reads text from original_field and
    writes embeddings into the semantic field's companion embedding field. original_field
    text is left in place.
  - deploy_semantic_enrichment ONLY adds the FieldReplacementProcessor to the search
    pipeline (rewrites queries on original_field -> semantic_field -> neural). No mapping change.
  - rollback_semantic_enrichment ONLY removes the FieldReplacementProcessor. No mapping change.
  - State is derived: DISABLED (status), DEPLOYED (FieldReplacementProcessor present),
    else ENRICHING.
  - Request field is "original_field" (was "source_field").
"""

import sys
import time
import requests

CLUSTER_URL = "http://localhost:9200"
API_BASE = "/_plugins/_neural/semantic"

GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
RESET = "\033[0m"


def print_step(n, desc):
    print(f"\n{'='*70}")
    print(f"  Step {n}: {desc}")
    print(f"{'='*70}")


def check_cluster():
    try:
        r = requests.get(CLUSTER_URL, timeout=5)
        if r.status_code == 200:
            info = r.json()
            print(f"  Cluster: {info.get('cluster_name')} v{info.get('version', {}).get('number', '?')}")
            return True
    except Exception as e:
        print(f"  {RED}Cluster not reachable: {e}{RESET}")
    return False


def configure_ml():
    requests.put(f"{CLUSTER_URL}/_cluster/settings", json={
        "persistent": {
            "plugins.ml_commons.only_run_on_ml_node": False,
            "plugins.ml_commons.native_memory_threshold": 100,
            "plugins.ml_commons.jvm_heap_memory_threshold": 100
        }
    })


def wait_for_model(index, field_name, timeout=120):
    for i in range(timeout):
        try:
            r = requests.get(f"{CLUSTER_URL}/{index}/_mapping", timeout=30)
            if r.status_code == 200:
                model_id = r.json().get(index, {}).get("mappings", {}).get("properties", {}).get(field_name, {}).get("model_id")
                if model_id:
                    mr = requests.get(f"{CLUSTER_URL}/_plugins/_ml/models/{model_id}", timeout=30)
                    if mr.status_code == 200 and mr.json().get("model_state") == "DEPLOYED":
                        print(f"  Model {model_id[:12]}... DEPLOYED after {i}s")
                        return model_id
        except Exception:
            pass
        time.sleep(1)
    print(f"  {YELLOW}WARNING: Model may not be deployed yet{RESET}")
    return None


def cleanup(indices):
    for idx in indices:
        requests.delete(f"{CLUSTER_URL}/{idx}", timeout=10)
        requests.delete(f"{CLUSTER_URL}/_search/pipeline/{idx}-semantic-search-pipeline", timeout=10)
    time.sleep(1)


def has_field_replacement(index):
    pipeline_name = f"{index}-semantic-search-pipeline"
    r = requests.get(f"{CLUSTER_URL}/_search/pipeline/{pipeline_name}", timeout=10)
    if r.status_code == 200:
        req_procs = r.json().get(pipeline_name, {}).get("request_processors", [])
        return any("field_replacement_processor" in p for p in req_procs)
    return False


# ============================================================
# API Tests
# ============================================================

def test_enable_enrichment():
    """enable_semantic_enrichment: creates semantic field + search pipeline (ENRICHING)."""
    print_step("A1", "enable_semantic_enrichment — creates field + pipeline")

    idx = "test-api-enable"
    cleanup([idx])

    r = requests.put(f"{CLUSTER_URL}/{idx}", json={
        "mappings": {"properties": {"title": {"type": "text"}, "category": {"type": "keyword"}}}
    }, timeout=30)
    assert r.status_code == 200

    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/enable_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=120)
    print(f"  Enable [{r.status_code}]: {r.text[:400]}")
    if r.status_code != 200:
        print(f"  {RED}FAIL: enable_semantic_enrichment failed{RESET}")
        return False

    # Verify semantic field created with original_field
    r = requests.get(f"{CLUSTER_URL}/{idx}/_mapping", timeout=10)
    props = r.json()[idx]["mappings"]["properties"]
    assert "title_semantic" in props
    assert props["title_semantic"]["type"] == "semantic"
    assert props["title_semantic"].get("original_field") == "title"
    # No _rename_active should ever appear in mapping
    assert "_rename_active" not in props["title_semantic"], "unexpected _rename_active in mapping"

    # Verify search pipeline created + attached, keyed on semantic field name
    r = requests.get(f"{CLUSTER_URL}/{idx}/_settings", timeout=10)
    pipeline = r.json()[idx]["settings"]["index"].get("search", {}).get("default_pipeline")
    print(f"  Pipeline: {pipeline}")
    assert pipeline and pipeline.endswith("-semantic-search-pipeline")

    r = requests.get(f"{CLUSTER_URL}/_search/pipeline/{pipeline}", timeout=10)
    if r.status_code == 200:
        req_procs = r.json().get(pipeline, {}).get("request_processors", [])
        for p in req_procs:
            if "semantic_search_rewrite_processor" in p:
                field_map = p["semantic_search_rewrite_processor"].get("field_map", {})
                print(f"  rewrite field_map keys: {list(field_map.keys())}")
                assert "title_semantic" in field_map

    # In ENRICHING state there is no field_replacement_processor yet
    assert not has_field_replacement(idx), "field_replacement should not be present after enable"

    wait_for_model(idx, "title_semantic")
    r = requests.post(f"{CLUSTER_URL}/{idx}/_doc/1", json={"title": "Semantic search engine"}, timeout=30)
    assert r.status_code in (200, 201)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(1)

    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/1", timeout=10)
    src = r.json().get("_source", {})
    emb = src.get("title_semantic_semantic_info", {}).get("embedding", {})
    print(f"  Embeddings: {len(emb)} terms; original 'title' present: {'title' in src}")
    # copy mode: original field text is preserved
    assert "title" in src

    if len(emb) > 0 and pipeline:
        print(f"  {GREEN}PASS: enable_semantic_enrichment — field + pipeline + embeddings (copy mode){RESET}")
        return True
    print(f"  {RED}FAIL{RESET}")
    return False


def test_disable_enrichment():
    """disable_semantic_enrichment: stops embeddings, full cleanup."""
    print_step("A2", "disable_semantic_enrichment — stops embeddings")

    idx = "test-api-enable"

    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/disable_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=30)
    print(f"  Disable [{r.status_code}]: {r.text[:200]}")
    assert r.status_code == 200
    time.sleep(2)

    # status should be DISABLED, field_replacement removed
    r = requests.get(f"{CLUSTER_URL}/{idx}/_mapping", timeout=10)
    title_sem = r.json()[idx]["mappings"]["properties"].get("title_semantic", {})
    print(f"  status after disable: {title_sem.get('status')}")
    assert title_sem.get("status") == "DISABLED"
    assert not has_field_replacement(idx)

    r = requests.post(f"{CLUSTER_URL}/{idx}/_doc/disabled1", json={"title": "No embeddings"}, timeout=30)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(1)

    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/disabled1", timeout=10)
    emb = r.json().get("_source", {}).get("title_semantic_semantic_info", {}).get("embedding", {})
    print(f"  Embeddings: {len(emb)} (expected 0)")

    if len(emb) == 0:
        print(f"  {GREEN}PASS: disable_semantic_enrichment — no embeddings{RESET}")
        # Re-enable for subsequent tests
        requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/enable_semantic_enrichment", json={
            "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
        }, timeout=120)
        time.sleep(2)
        return True
    print(f"  {RED}FAIL: Still got embeddings{RESET}")
    return False


def test_deploy():
    """deploy_semantic_enrichment: adds FieldReplacementProcessor (copy mode stays)."""
    print_step("A3", "deploy_semantic_enrichment — search switch via field replacement")

    idx = "test-api-deploy"
    cleanup([idx])

    requests.put(f"{CLUSTER_URL}/{idx}", json={
        "mappings": {"properties": {"title": {"type": "text"}}}
    }, timeout=30)
    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/enable_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=120)
    assert r.status_code == 200
    wait_for_model(idx, "title_semantic")

    requests.post(f"{CLUSTER_URL}/{idx}/_doc/1", json={"title": "Pre-deploy document"}, timeout=30)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(1)

    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/deploy_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=30)
    print(f"  Deploy [{r.status_code}]: {r.text[:400]}")
    if r.status_code != 200:
        print(f"  {RED}FAIL: deploy failed{RESET}")
        return False
    time.sleep(2)

    # No mapping change: _rename_active must NOT exist
    r = requests.get(f"{CLUSTER_URL}/{idx}/_mapping", timeout=10)
    title_sem = r.json()[idx]["mappings"]["properties"].get("title_semantic", {})
    assert "_rename_active" not in title_sem, "deploy must not add _rename_active to mapping"

    # FieldReplacementProcessor now present
    has_replacement = has_field_replacement(idx)
    print(f"  field_replacement_processor in pipeline: {has_replacement}")

    # Copy mode still: ingest doc with "title" keeps title AND gets embeddings
    r = requests.post(f"{CLUSTER_URL}/{idx}/_doc/2", json={"title": "Post-deploy document"}, timeout=30)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(1)
    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/2", timeout=10)
    source = r.json().get("_source", {})
    emb = source.get("title_semantic_semantic_info", {}).get("embedding", {})
    print(f"  Post-deploy doc: has 'title'={'title' in source}, embeddings={len(emb)}")

    # Match on "title" is now rewritten to neural via field replacement
    r = requests.get(f"{CLUSTER_URL}/{idx}/_search", json={"query": {"match": {"title": "document"}}}, timeout=30)
    hits = r.json().get("hits", {}).get("total", {}).get("value", 0)
    print(f"  Match on 'title': {hits} hits (neural via field replacement)")

    if len(emb) > 0 and has_replacement and "title" in source:
        print(f"  {GREEN}PASS: deploy_semantic_enrichment works (copy mode + field replacement){RESET}")
        return True
    print(f"  {RED}FAIL: deploy incomplete (emb={len(emb)}, replacement={has_replacement}){RESET}")
    return False


def test_deploy_rejects_already_deployed():
    """deploy again should be rejected (already DEPLOYED)."""
    print_step("A3b", "deploy again — rejected (already DEPLOYED)")

    idx = "test-api-deploy"
    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/deploy_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=30)
    print(f"  [{r.status_code}]: {r.text[:200]}")
    if r.status_code == 400 and "already DEPLOYED" in r.text:
        print(f"  {GREEN}PASS{RESET}")
        return True
    print(f"  {RED}FAIL{RESET}")
    return False


def test_rollback():
    """rollback_semantic_enrichment: removes FieldReplacementProcessor (back to ENRICHING)."""
    print_step("A4", "rollback_semantic_enrichment — undo deploy")

    idx = "test-api-deploy"  # Reuse from A3 (currently DEPLOYED)

    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/rollback_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=30)
    print(f"  Rollback [{r.status_code}]: {r.text[:400]}")
    if r.status_code != 200:
        print(f"  {RED}FAIL: rollback failed{RESET}")
        return False
    time.sleep(2)

    # FieldReplacementProcessor removed
    has_replacement = has_field_replacement(idx)
    print(f"  field_replacement_processor: {has_replacement} (expected False)")

    # Copy mode continues: ingest keeps title + embeddings
    r = requests.post(f"{CLUSTER_URL}/{idx}/_doc/3", json={"title": "Post-rollback document"}, timeout=30)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(1)
    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/3", timeout=10)
    source = r.json().get("_source", {})
    emb = source.get("title_semantic_semantic_info", {}).get("embedding", {})
    print(f"  Post-rollback doc: has 'title'={'title' in source}, embeddings={len(emb)}")

    if not has_replacement and len(emb) > 0 and "title" in source:
        print(f"  {GREEN}PASS: rollback_semantic_enrichment — enrichment continues, search reverted{RESET}")
        return True
    print(f"  {RED}FAIL: rollback incomplete{RESET}")
    return False


def test_list():
    """list_semantic_enrichment: shows derived state (ENRICHING)."""
    print_step("A5", "list_semantic_enrichment — shows state")

    idx = "test-api-enable"

    r = requests.get(f"{CLUSTER_URL}{API_BASE}/{idx}/list_semantic_enrichment", timeout=10)
    print(f"  List [{r.status_code}]: {r.text[:500]}")
    if r.status_code != 200:
        print(f"  {RED}FAIL: list failed{RESET}")
        return False

    fields = r.json().get("fields", [])
    found = any(f.get("original_field") == "title" and f.get("semantic_field") == "title_semantic" for f in fields)
    if found:
        field = next(f for f in fields if f.get("semantic_field") == "title_semantic")
        print(f"  State: {field.get('state')}, status: {field.get('status')}")
        # test-api-enable was re-enabled (no field replacement) => ENRICHING
        if field.get("state") == "ENRICHING":
            print(f"  {GREEN}PASS: list_semantic_enrichment returns derived state{RESET}")
            return True
        print(f"  {RED}FAIL: expected ENRICHING, got {field.get('state')}{RESET}")
        return False
    print(f"  {RED}FAIL: enrichment not found in list{RESET}")
    return False


def test_list_deployed_state():
    """list_semantic_enrichment: DEPLOYED after deploy, ENRICHING after rollback."""
    print_step("A5b", "list_semantic_enrichment — DEPLOYED/ENRICHING transitions")

    idx = "test-api-liststate"
    cleanup([idx])
    requests.put(f"{CLUSTER_URL}/{idx}", json={"mappings": {"properties": {"title": {"type": "text"}}}}, timeout=30)
    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/enable_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=120)
    assert r.status_code == 200
    wait_for_model(idx, "title_semantic")

    def state():
        r = requests.get(f"{CLUSTER_URL}{API_BASE}/{idx}/list_semantic_enrichment", timeout=10)
        f = next((x for x in r.json().get("fields", []) if x.get("semantic_field") == "title_semantic"), {})
        return f.get("state")

    s_enrich = state()
    print(f"  After enable: {s_enrich}")

    requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/deploy_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=30)
    time.sleep(1)
    s_deployed = state()
    print(f"  After deploy: {s_deployed}")

    requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/rollback_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=30)
    time.sleep(1)
    s_rolled = state()
    print(f"  After rollback: {s_rolled}")

    if s_enrich == "ENRICHING" and s_deployed == "DEPLOYED" and s_rolled == "ENRICHING":
        print(f"  {GREEN}PASS: state transitions ENRICHING -> DEPLOYED -> ENRICHING{RESET}")
        return True
    print(f"  {RED}FAIL: got {s_enrich} -> {s_deployed} -> {s_rolled}{RESET}")
    return False


def test_multi_field():
    """Multi-field: enable two fields in one call, then deploy both."""
    print_step("A6", "Multi-field enable + deploy + search")

    idx = "test-api-multi"
    cleanup([idx])

    requests.put(f"{CLUSTER_URL}/{idx}", json={
        "mappings": {"properties": {"title": {"type": "text"}, "description": {"type": "text"}}}
    }, timeout=30)

    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/enable_semantic_enrichment", json={
        "fields": [
            {"original_field": "title", "semantic_field": "title_semantic"},
            {"original_field": "description", "semantic_field": "desc_semantic"}
        ]
    }, timeout=120)
    print(f"  Enable [{r.status_code}]: {r.text[:300]}")
    assert r.status_code == 200

    wait_for_model(idx, "title_semantic")

    requests.post(f"{CLUSTER_URL}/{idx}/_doc/1", json={
        "title": "Machine learning NLP", "description": "Using transformers for search"
    }, timeout=30)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(2)

    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/1", timeout=10)
    src = r.json().get("_source", {})
    t_emb = src.get("title_semantic_semantic_info", {}).get("embedding", {})
    d_emb = src.get("desc_semantic_semantic_info", {}).get("embedding", {})
    print(f"  title embeddings: {len(t_emb)}, desc embeddings: {len(d_emb)}")

    # Deploy both (search switch)
    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/deploy_semantic_enrichment", json={
        "fields": [
            {"original_field": "title", "semantic_field": "title_semantic"},
            {"original_field": "description", "semantic_field": "desc_semantic"}
        ]
    }, timeout=30)
    print(f"  Deploy [{r.status_code}]")
    time.sleep(1)

    r = requests.get(f"{CLUSTER_URL}/{idx}/_search", json={"query": {"match": {"title": "machine learning"}}}, timeout=30)
    hits = r.json().get("hits", {}).get("total", {}).get("value", 0)
    print(f"  Match on 'title': {hits} hits")

    if len(t_emb) > 0 and len(d_emb) > 0 and hits > 0:
        print(f"  {GREEN}PASS: Multi-field works{RESET}")
        return True
    print(f"  {RED}FAIL{RESET}")
    return False


def test_validation_original_not_exist():
    """Validation: original_field not found."""
    print_step("A7", "Validation — original_field not found")

    idx = "test-api-enable"
    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/enable_semantic_enrichment", json={
        "fields": [{"original_field": "nonexistent", "semantic_field": "x_semantic"}]
    }, timeout=30)
    print(f"  [{r.status_code}]: {r.text[:200]}")
    if r.status_code == 400 and "does not exist" in r.text:
        print(f"  {GREEN}PASS{RESET}")
        return True
    print(f"  {RED}FAIL{RESET}")
    return False


def test_validation_incompatible_type():
    """Validation: incompatible type."""
    print_step("A8", "Validation — incompatible type")

    idx2 = "test-api-typecheck"
    cleanup([idx2])
    requests.put(f"{CLUSTER_URL}/{idx2}", json={"mappings": {"properties": {"flag": {"type": "boolean"}}}}, timeout=30)

    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx2}/enable_semantic_enrichment", json={
        "fields": [{"original_field": "flag", "semantic_field": "flag_semantic"}]
    }, timeout=30)
    print(f"  [{r.status_code}]: {r.text[:200]}")
    if r.status_code == 400 and "not compatible" in r.text:
        print(f"  {GREEN}PASS{RESET}")
        return True
    print(f"  {RED}FAIL{RESET}")
    return False


def test_deploy_rejects_disabled():
    """deploy from DISABLED should be rejected."""
    print_step("A9", "deploy from DISABLED — rejected")

    idx = "test-api-disabledeploy"
    cleanup([idx])
    requests.put(f"{CLUSTER_URL}/{idx}", json={"mappings": {"properties": {"title": {"type": "text"}}}}, timeout=30)
    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/enable_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=120)
    assert r.status_code == 200
    wait_for_model(idx, "title_semantic")

    requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/disable_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=30)
    time.sleep(1)

    r = requests.post(f"{CLUSTER_URL}{API_BASE}/{idx}/deploy_semantic_enrichment", json={
        "fields": [{"original_field": "title", "semantic_field": "title_semantic"}]
    }, timeout=30)
    print(f"  [{r.status_code}]: {r.text[:200]}")
    if r.status_code == 400 and "DISABLED" in r.text:
        print(f"  {GREEN}PASS{RESET}")
        return True
    print(f"  {RED}FAIL{RESET}")
    return False


# ============================================================
# Regression Tests
# ============================================================

def test_regression_semantic_field_cycle():
    """Regression: plain semantic field (no API) — enable/disable/re-enable cycle."""
    print_step("R1", "Regression — semantic field enable/disable/re-enable")

    idx = "test-regression-r1"
    cleanup([idx])

    r = requests.put(f"{CLUSTER_URL}/{idx}", json={
        "mappings": {"properties": {"content": {"type": "semantic"}}}
    }, timeout=120)
    assert r.status_code == 200, f"Create failed: {r.text}"
    wait_for_model(idx, "content")

    requests.post(f"{CLUSTER_URL}/{idx}/_doc/1", json={"content": "Test text"}, timeout=30)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(1)
    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/1", timeout=10)
    emb1 = r.json().get("_source", {}).get("content_semantic_info", {}).get("embedding", {})
    if len(emb1) == 0:
        print(f"  {RED}FAIL: No embeddings when ENABLED{RESET}")
        return False

    requests.put(f"{CLUSTER_URL}/{idx}/_mapping", json={"properties": {"content": {"type": "semantic", "status": "DISABLED"}}}, timeout=30)
    time.sleep(1)
    requests.post(f"{CLUSTER_URL}/{idx}/_doc/2", json={"content": "Disabled text"}, timeout=30)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(1)
    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/2", timeout=10)
    emb2 = r.json().get("_source", {}).get("content_semantic_info", {}).get("embedding", {})
    if len(emb2) != 0:
        print(f"  {RED}FAIL: Embeddings when DISABLED{RESET}")
        return False

    requests.put(f"{CLUSTER_URL}/{idx}/_mapping", json={"properties": {"content": {"type": "semantic", "status": "ENABLED"}}}, timeout=30)
    time.sleep(1)
    requests.post(f"{CLUSTER_URL}/{idx}/_doc/3", json={"content": "Re-enabled text"}, timeout=30)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(1)
    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/3", timeout=10)
    emb3 = r.json().get("_source", {}).get("content_semantic_info", {}).get("embedding", {})
    if len(emb3) > 0:
        print(f"  {GREEN}PASS: enable/disable/re-enable cycle works{RESET}")
        return True
    print(f"  {RED}FAIL: No embeddings after re-enable{RESET}")
    return False


def test_regression_original_field_cycle():
    """Regression: original_field semantic field (direct mapping) — full cycle."""
    print_step("R2", "Regression — original_field direct mapping cycle")

    idx = "test-regression-r2"
    cleanup([idx])

    r = requests.put(f"{CLUSTER_URL}/{idx}", json={
        "mappings": {"properties": {
            "title": {"type": "text"},
            "title_semantic": {"type": "semantic", "original_field": "title"}
        }}
    }, timeout=120)
    assert r.status_code == 200, f"Create failed: {r.text}"
    wait_for_model(idx, "title_semantic")

    requests.post(f"{CLUSTER_URL}/{idx}/_doc/1", json={"title": "Neural search test"}, timeout=30)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(1)
    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/1", timeout=10)
    emb = r.json().get("_source", {}).get("title_semantic_semantic_info", {}).get("embedding", {})
    if len(emb) == 0:
        print(f"  {RED}FAIL: No embeddings{RESET}")
        return False
    print(f"  Embeddings: {len(emb)} terms")

    requests.put(f"{CLUSTER_URL}/{idx}/_mapping", json={
        "properties": {"title_semantic": {"type": "semantic", "original_field": "title", "status": "DISABLED"}}
    }, timeout=30)
    time.sleep(2)
    requests.post(f"{CLUSTER_URL}/{idx}/_doc/2", json={"title": "Disabled"}, timeout=30)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(1)
    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/2", timeout=10)
    emb2 = r.json().get("_source", {}).get("title_semantic_semantic_info", {}).get("embedding", {})
    if len(emb2) != 0:
        print(f"  {RED}FAIL: Embeddings when DISABLED{RESET}")
        return False

    requests.put(f"{CLUSTER_URL}/{idx}/_mapping", json={
        "properties": {"title_semantic": {"type": "semantic", "original_field": "title", "status": "ENABLED"}}
    }, timeout=30)
    time.sleep(2)
    requests.post(f"{CLUSTER_URL}/{idx}/_doc/3", json={"title": "Re-enabled"}, timeout=30)
    requests.post(f"{CLUSTER_URL}/{idx}/_refresh", timeout=10)
    time.sleep(1)
    r = requests.get(f"{CLUSTER_URL}/{idx}/_doc/3", timeout=10)
    emb3 = r.json().get("_source", {}).get("title_semantic_semantic_info", {}).get("embedding", {})
    if len(emb3) > 0:
        print(f"  {GREEN}PASS: original_field direct mapping cycle works{RESET}")
        return True
    print(f"  {RED}FAIL: No embeddings after re-enable{RESET}")
    return False


# ============================================================
# Main
# ============================================================

def main():
    print("=" * 70)
    print("  Semantic Enable API — E2E Test")
    print("=" * 70)

    if not check_cluster():
        print(f"\n{RED}ERROR: Cluster not available{RESET}")
        sys.exit(1)

    configure_ml()
    cleanup(["test-api-enable", "test-api-deploy", "test-api-multi", "test-api-typecheck",
             "test-api-liststate", "test-api-disabledeploy",
             "test-regression-r1", "test-regression-r2"])
    time.sleep(1)

    results = {}

    results["A1. enable_semantic_enrichment"] = test_enable_enrichment()
    results["A2. disable_semantic_enrichment"] = test_disable_enrichment()
    results["A3. deploy_semantic_enrichment"] = test_deploy()
    results["A3b. deploy rejects already-deployed"] = test_deploy_rejects_already_deployed()
    results["A4. rollback_semantic_enrichment"] = test_rollback()
    results["A5. list_semantic_enrichment"] = test_list()
    results["A5b. list state transitions"] = test_list_deployed_state()
    results["A6. Multi-field"] = test_multi_field()
    results["A7. Validation: original not exist"] = test_validation_original_not_exist()
    results["A8. Validation: incompatible type"] = test_validation_incompatible_type()
    results["A9. deploy rejects DISABLED"] = test_deploy_rejects_disabled()

    results["R1. Regression: semantic field cycle"] = test_regression_semantic_field_cycle()
    results["R2. Regression: original_field mapping"] = test_regression_original_field_cycle()

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
