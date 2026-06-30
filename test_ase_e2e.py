#!/usr/bin/env python3
"""
ASE End-to-End Test Script (dp-option-common-lib branch)

This script:
1. Kills any running OpenSearch cluster
2. Rebuilds neural-search plugin
3. Starts a fresh local cluster
4. Creates sparse English + dense English indices
5. Ingests documents into both
6. Queries both indices
7. Prints results

Prerequisites:
- opensearch-py: pip install opensearch-py
- Java 21 at /local/home/yizheliu/.sdkman/candidates/java/21.0.2-open
- neural-search source at current directory
"""

import os
import sys
import time
import subprocess
import json

try:
    from opensearchpy import OpenSearch
except ImportError:
    print("ERROR: opensearch-py not installed. Run: pip install opensearch-py")
    sys.exit(1)

JAVA_HOME = "/local/home/yizheliu/.sdkman/candidates/java/21.0.2-open"
NEURAL_SEARCH_DIR = os.path.dirname(os.path.abspath(__file__))
CLUSTER_URL = "http://localhost:9200"

os.environ["JAVA_HOME"] = JAVA_HOME


def run_cmd(cmd, cwd=None, timeout=300):
    """Run a shell command and return output."""
    result = subprocess.run(cmd, shell=True, cwd=cwd, capture_output=True, text=True, timeout=timeout,
                           env={**os.environ, "JAVA_HOME": JAVA_HOME})
    return result.returncode, result.stdout, result.stderr


def kill_cluster():
    """Kill any running OpenSearch processes."""
    print(">>> Killing existing cluster...")
    os.system("pkill -9 -f 'java.*opensearch' 2>/dev/null")
    os.system("pkill -9 -f gradlew 2>/dev/null")
    time.sleep(3)
    os.system(f"rm -rf {NEURAL_SEARCH_DIR}/build/testclusters 2>/dev/null")
    print("    Done.")


def build_plugin():
    """Build the neural-search plugin."""
    print(">>> Building neural-search plugin...")
    rc, out, err = run_cmd("./gradlew clean bundlePlugin", cwd=NEURAL_SEARCH_DIR, timeout=120)
    if rc != 0:
        print(f"    BUILD FAILED:\n{err[-500:]}")
        sys.exit(1)
    print("    BUILD SUCCESSFUL")


def start_cluster():
    """Start the cluster in background and wait for green."""
    print(">>> Starting cluster...")
    proc = subprocess.Popen(
        f"{JAVA_HOME}/bin/java -version && ./gradlew run",
        shell=True, cwd=NEURAL_SEARCH_DIR,
        stdout=open("/tmp/ns-test-run.log", "w"),
        stderr=subprocess.STDOUT,
        env={**os.environ, "JAVA_HOME": JAVA_HOME}
    )
    print(f"    PID: {proc.pid}")

    # Wait up to 90s for cluster to be green
    client = OpenSearch(hosts=[{"host": "localhost", "port": 9200}])
    for i in range(18):
        time.sleep(5)
        try:
            health = client.cluster.health()
            if health["status"] in ("green", "yellow"):
                print(f"    Cluster {health['status']} after {(i+1)*5}s")
                return client
        except Exception:
            pass

    print("    ERROR: Cluster did not start within 90s")
    subprocess.run("tail -20 /tmp/ns-test-run.log", shell=True)
    sys.exit(1)


def configure_ml(client):
    """Configure ML Commons for local testing."""
    print(">>> Configuring ML Commons settings...")
    client.cluster.put_settings(body={
        "persistent": {
            "plugins.ml_commons.only_run_on_ml_node": False,
            "plugins.ml_commons.native_memory_threshold": 100,
            "plugins.ml_commons.jvm_heap_memory_threshold": 100
        }
    })
    print("    Done.")


def wait_for_model(client, index_name, timeout=120):
    """Wait for the model associated with an index to be deployed."""
    mapping = client.indices.get_mapping(index=index_name)
    props = mapping[index_name]["mappings"]["properties"]
    # Find the semantic field
    for field_name, field_config in props.items():
        if field_config.get("type") == "semantic":
            model_id = field_config.get("model_id")
            if model_id:
                print(f"    Model ID: {model_id}, waiting for deploy...")
                start = time.time()
                while time.time() - start < timeout:
                    try:
                        resp = client.transport.perform_request("GET", f"/_plugins/_ml/models/{model_id}")
                        state = resp.get("model_state")
                        if state == "DEPLOYED":
                            print(f"    Model DEPLOYED ({int(time.time()-start)}s)")
                            return model_id
                        elif state == "DEPLOY_FAILED":
                            # Retry deploy
                            client.transport.perform_request("POST", f"/_plugins/_ml/models/{model_id}/_deploy")
                    except Exception:
                        pass
                    time.sleep(5)
                print(f"    WARNING: Model not deployed within {timeout}s")
                return model_id
    return None


def test_sparse(client):
    """Test sparse English semantic field."""
    print("\n" + "="*60)
    print("TEST: Sparse English Semantic Field")
    print("="*60)

    index_name = "test-sparse-en"

    # Create index
    print(f"\n>>> Creating index '{index_name}'...")
    try:
        client.indices.delete(index=index_name, ignore=[404])
    except Exception:
        pass

    resp = client.indices.create(index=index_name, body={
        "mappings": {
            "properties": {
                "passage": {
                    "type": "semantic",
                    "language": "ENGLISH",
                    "model_type": "SPARSE"
                }
            }
        }
    })
    print(f"    Created: {resp['acknowledged']}")

    # Verify mapping
    mapping = client.indices.get_mapping(index=index_name)
    body_field = mapping[index_name]["mappings"]["properties"]["passage"]
    print(f"    model_id: {body_field.get('model_id')}")
    print(f"    language: {body_field.get('language')}")
    print(f"    model_type: {body_field.get('model_type')}")
    has_semantic_info = "passage_semantic_info" in mapping[index_name]["mappings"]["properties"]
    print(f"    Has semantic_info: {has_semantic_info}")

    # Wait for model
    model_id = wait_for_model(client, index_name)

    # Ingest
    print("\n>>> Ingesting documents...")
    docs = [
        {"passage": "OpenSearch is a distributed search and analytics engine."},
        {"passage": "Machine learning powers modern semantic search."},
        {"passage": "Kubernetes orchestrates containerized applications."},
    ]
    for i, doc in enumerate(docs, 1):
        client.index(index=index_name, id=i, body=doc, refresh=True)
    print(f"    Ingested {len(docs)} documents")

    # Verify embeddings
    doc = client.get(index=index_name, id=1)
    si = doc["_source"].get("passage_semantic_info", {})
    emb = si.get("embedding", {})
    print(f"    Doc 1 embeddings: {len(emb)} tokens")
    if emb:
        sample = list(emb.items())[:3]
        print(f"    Sample: {sample}")

    # Search
    print("\n>>> Searching: 'what is a search engine?'")
    results = client.search(index=index_name, body={
        "query": {
            "neural_sparse": {
                "passage_semantic_info.embedding": {
                    "query_text": "what is a search engine?",
                    "model_id": model_id
                }
            }
        }
    })
    hits = results["hits"]["hits"]
    print(f"    Hits: {len(hits)}")
    for h in hits:
        print(f"      doc {h['_id']}: score={h['_score']:.4f} | {h['_source']['passage'][:60]}")

    return len(hits) > 0


def test_dense(client):
    """Test dense English semantic field."""
    print("\n" + "="*60)
    print("TEST: Dense English Semantic Field")
    print("="*60)

    index_name = "test-dense-en"

    # Create index
    print(f"\n>>> Creating index '{index_name}'...")
    try:
        client.indices.delete(index=index_name, ignore=[404])
    except Exception:
        pass

    resp = client.indices.create(index=index_name, body={
        "settings": {"index.knn": True},
        "mappings": {
            "properties": {
                "passage": {
                    "type": "semantic",
                    "model_type": "DENSE"
                }
            }
        }
    })
    print(f"    Created: {resp['acknowledged']}")

    # Verify mapping
    mapping = client.indices.get_mapping(index=index_name)
    body_field = mapping[index_name]["mappings"]["properties"]["passage"]
    print(f"    model_id: {body_field.get('model_id')}")
    print(f"    model_type: {body_field.get('model_type')}")
    emb_field = mapping[index_name]["mappings"]["properties"].get("passage_semantic_info", {}).get("properties", {}).get("embedding", {})
    print(f"    embedding.type: {emb_field.get('type')}")
    print(f"    embedding.dimension: {emb_field.get('dimension')}")
    print(f"    embedding.method: {emb_field.get('method')}")

    # Wait for model
    model_id = wait_for_model(client, index_name)

    # Ingest
    print("\n>>> Ingesting documents...")
    docs = [
        {"passage": "OpenSearch is a distributed search and analytics engine."},
        {"passage": "Machine learning powers modern semantic search."},
        {"passage": "Kubernetes orchestrates containerized applications."},
    ]
    for i, doc in enumerate(docs, 1):
        client.index(index=index_name, id=i, body=doc, refresh=True)
    print(f"    Ingested {len(docs)} documents")

    # Verify embeddings
    doc = client.get(index=index_name, id=1)
    si = doc["_source"].get("passage_semantic_info", {})
    emb = si.get("embedding", [])
    print(f"    Doc 1 embedding dimension: {len(emb)}")
    if emb:
        print(f"    First 3 values: {emb[:3]}")

    # Search
    print("\n>>> Searching: 'what is a search engine?'")
    results = client.search(index=index_name, body={
        "query": {
            "neural": {
                "passage_semantic_info.embedding": {
                    "query_text": "what is a search engine?",
                    "model_id": model_id,
                    "k": 5
                }
            }
        }
    })
    hits = results["hits"]["hits"]
    print(f"    Hits: {len(hits)}")
    for h in hits:
        print(f"      doc {h['_id']}: score={h['_score']:.4f} | {h['_source']['passage'][:60]}")

    return len(hits) > 0


def main():
    print("=" * 60)
    print("ASE End-to-End Test (dp-option-common-lib)")
    print("=" * 60)

    kill_cluster()
    build_plugin()
    client = start_cluster()
    configure_ml(client)

    sparse_ok = test_sparse(client)
    dense_ok = test_dense(client)

    print("\n" + "=" * 60)
    print("RESULTS")
    print("=" * 60)
    print(f"  Sparse English: {'✅ PASS' if sparse_ok else '❌ FAIL'}")
    print(f"  Dense English:  {'✅ PASS' if dense_ok else '❌ FAIL'}")
    print("=" * 60)

    if not (sparse_ok and dense_ok):
        sys.exit(1)


if __name__ == "__main__":
    main()
