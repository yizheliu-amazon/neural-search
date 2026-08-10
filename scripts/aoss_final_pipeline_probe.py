#!/usr/bin/env python3
"""
Probe: can ASE install its routing processors in index.final_pipeline on an
Amazon OpenSearch Serverless (AOSS) collection?

The load-bearing question for the ASE data-plane API is (B) below: ASE enables
semantic enrichment on an EXISTING index, so it must set the pipeline via
PUT /{index}/_settings after the index already exists.

Checks:
  A. Set index.final_pipeline at CREATE time            (baseline)
  B. Set index.final_pipeline via PUT _settings on an
     EXISTING index                                     <-- THE ONE WE NEED
  C. final_pipeline actually RUNS (marker field present)
  D. Same for index.default_pipeline, for comparison
  E. Request-level ?pipeline= overrides default_pipeline
     but NOT final_pipeline                             (the property that
                                                         motivated final)
  F. Unset via _none
  G. Custom document IDs: PUT /{index}/_doc/{id} and
     overwrite-in-place                                 (backfill viability)

Usage:
  python3 aoss_final_pipeline_probe.py <collection-endpoint> [region]

Cleans up every resource it creates.
"""
import json
import os
import sys
import time
import uuid

import botocore.session
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
import requests

SERVICE = "aoss"


class Aoss:
    def __init__(self, endpoint, region):
        self.endpoint = endpoint.rstrip("/")
        self.region = region
        self.creds = botocore.session.get_session().get_credentials()
        if self.creds is None:
            sys.exit("No AWS credentials found.")

    def call(self, method, path, body=None):
        url = self.endpoint + path
        data = json.dumps(body) if body is not None else None
        req = AWSRequest(
            method=method,
            url=url,
            data=data,
            headers={"Content-Type": "application/json"},
        )
        SigV4Auth(self.creds.get_frozen_credentials(), SERVICE, self.region).add_auth(req)
        resp = requests.request(
            method, url, headers=dict(req.headers), data=data, timeout=60
        )
        try:
            parsed = resp.json()
        except Exception:
            parsed = {"_raw": resp.text}
        return resp.status_code, parsed


results = []


def record(label, ok, detail=""):
    results.append((label, ok, detail))
    mark = "PASS" if ok is True else ("FAIL" if ok is False else "INFO")
    print(f"  [{mark}] {label}" + (f"  -- {detail}" if detail else ""))


def marker_pipeline(field):
    return {
        "description": f"ASE probe: stamps {field}",
        "processors": [{"set": {"field": field, "value": "ran"}}],
    }


def get_doc_with_retry(aoss, index, doc_id, attempts=12, delay=5):
    """AOSS does not support refresh=true, so poll."""
    for _ in range(attempts):
        code, body = aoss.call("GET", f"/{index}/_doc/{doc_id}")
        if code == 200 and body.get("found"):
            return body.get("_source", {})
        time.sleep(delay)
    return None


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    endpoint = sys.argv[1]
    region = sys.argv[2] if len(sys.argv) > 2 else "us-east-1"

    aoss = Aoss(endpoint, region)
    tag = uuid.uuid4().hex[:8]

    # Preflight: confirm reads AND writes are permitted before drawing conclusions.
    # A read-only principal produces 403 on every write, which would otherwise look
    # like "final_pipeline is unsupported" when it is really an authz problem.
    print("== Preflight: identity and data plane WRITE capability ==")

    # Report exactly which principal is signing. The most common failure mode is
    # unknowingly using sandbox/agent credentials whose session policy permits AOSS
    # reads but denies writes -- which looks identical to "final_pipeline unsupported".
    caller_arn = "<unknown>"
    try:
        sts = botocore.session.get_session().create_client("sts", region_name=region)
        caller_arn = sts.get_caller_identity()["Arn"]
    except Exception as e:
        print(f"  [INFO] could not call sts:GetCallerIdentity: {e}")
    ak = aoss.creds.access_key
    print(f"  principal      : {caller_arn}")
    print(f"  access key id  : {ak[:8]}... ({'temporary' if ak.startswith('ASIA') else 'long-lived'})")
    if "IsengardAgenticAccess" in caller_arn or "sbox" in (
        os.environ.get("AWS_SHARED_CREDENTIALS_FILE") or ""
    ):
        print("  [WARN] This looks like sandbox/agent-vended credentials.")
        print("         Those permit AOSS reads but DENY writes, which will abort this probe.")
        print("         Export real credentials instead:")
        print("           export AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_SESSION_TOKEN=...")
        print("         and confirm the principal line above changes before trusting a result.")

    code, _ = aoss.call("GET", "/_cat/indices?format=json")
    if code != 200:
        print(f"  [FAIL] read check failed (HTTP {code}). Check network policy / "
              f"aoss:APIAccessAll / data access policy.")
        return
    print("  [PASS] reads OK")
    pf = f"ase-probe-preflight-{tag}"
    code, body = aoss.call("PUT", f"/_ingest/pipeline/{pf}", marker_pipeline("probe_preflight"))
    if code not in (200, 201):
        print(f"  [FAIL] write check failed (HTTP {code}): {json.dumps(body)[:300]}")
        print("\n  Reads succeed but writes are denied, so this principal cannot run the")
        print("  probe. This is an AUTHORIZATION result, NOT evidence about final_pipeline.")
        print("\n  Diagnostic: if GET INDEX/_settings returns 200 but POST INDEX/_search")
        print("  returns 403, granular aoss: actions are being filtered by an IAM session")
        print("  policy (a data access policy grants Describe and Read together, so a split")
        print("  like that cannot come from the DAP). That signature means credentials, not")
        print("  collection configuration.")
        print("  Needed: IAM aoss:APIAccessAll on the collection, plus a data access policy")
        print("  granting aoss:CreateCollectionItems / DeleteCollectionItems on collection/*")
        print("  and aoss:CreateIndex / UpdateIndex / DescribeIndex / WriteDocument /")
        print("  ReadDocument / DeleteIndex on index/*/* for this principal.")
        return
    aoss.call("DELETE", f"/_ingest/pipeline/{pf}")
    print("  [PASS] writes OK\n")

    pipe_final = f"ase-probe-final-{tag}"
    pipe_default = f"ase-probe-default-{tag}"
    pipe_request = f"ase-probe-request-{tag}"
    idx_existing = f"ase-probe-existing-{tag}"   # B, C, E, F, G
    idx_atcreate = f"ase-probe-atcreate-{tag}"   # A

    created_pipelines, created_indices = [], []

    try:
        # ---------- pipelines ----------
        print("\n== Setup: ingest pipelines ==")
        for name, field in (
            (pipe_final, "probe_final"),
            (pipe_default, "probe_default"),
            (pipe_request, "probe_request"),
        ):
            code, body = aoss.call("PUT", f"/_ingest/pipeline/{name}", marker_pipeline(field))
            ok = code in (200, 201)
            record(f"create ingest pipeline {name}", ok, f"HTTP {code}" + ("" if ok else f" {body}"))
            if ok:
                created_pipelines.append(name)
        if len(created_pipelines) < 3:
            record("ABORT: could not create probe pipelines", False)
            return

        # ---------- A: final_pipeline at create time ----------
        print("\n== A: index.final_pipeline at CREATE time ==")
        code, body = aoss.call(
            "PUT", f"/{idx_atcreate}",
            {"settings": {"index.final_pipeline": pipe_final}},
        )
        ok_a = code in (200, 201)
        record("create index with index.final_pipeline", ok_a, f"HTTP {code}" + ("" if ok_a else f" {body}"))
        if ok_a:
            created_indices.append(idx_atcreate)
            code, body = aoss.call("GET", f"/{idx_atcreate}/_settings")
            persisted = (
                body.get(idx_atcreate, {}).get("settings", {}).get("index", {}).get("final_pipeline")
            )
            record("final_pipeline persisted (create path)", persisted == pipe_final,
                   f"got {persisted!r}")

        # ---------- B: THE TEST — set on an existing index ----------
        print("\n== B: index.final_pipeline via PUT _settings on an EXISTING index ==")
        code, body = aoss.call("PUT", f"/{idx_existing}", {"settings": {}})
        ok = code in (200, 201)
        record(f"create plain index {idx_existing}", ok, f"HTTP {code}" + ("" if ok else f" {body}"))
        if not ok:
            record("ABORT: no index to test against", False)
            return
        created_indices.append(idx_existing)

        code, body = aoss.call(
            "PUT", f"/{idx_existing}/_settings", {"index.final_pipeline": pipe_final}
        )
        ok_b = code == 200
        record("PUT _settings index.final_pipeline on existing index", ok_b,
               f"HTTP {code}" + ("" if ok_b else f" {json.dumps(body)[:400]}"))

        code, body = aoss.call("GET", f"/{idx_existing}/_settings")
        idx_settings = body.get(idx_existing, {}).get("settings", {}).get("index", {})
        persisted_final = idx_settings.get("final_pipeline")
        record("final_pipeline persisted (existing-index path)", persisted_final == pipe_final,
               f"got {persisted_final!r}")

        # ---------- D: default_pipeline on existing index, for comparison ----------
        print("\n== D: index.default_pipeline via PUT _settings, for comparison ==")
        code, body = aoss.call(
            "PUT", f"/{idx_existing}/_settings", {"index.default_pipeline": pipe_default}
        )
        ok_d = code == 200
        record("PUT _settings index.default_pipeline on existing index", ok_d,
               f"HTTP {code}" + ("" if ok_d else f" {json.dumps(body)[:400]}"))
        code, body = aoss.call("GET", f"/{idx_existing}/_settings")
        idx_settings = body.get(idx_existing, {}).get("settings", {}).get("index", {})
        record("default_pipeline persisted", idx_settings.get("default_pipeline") == pipe_default,
               f"got {idx_settings.get('default_pipeline')!r}")
        print(f"  index settings now: {json.dumps(idx_settings)}")

        # ---------- G + C: custom doc ID, and do the pipelines run? ----------
        print("\n== G: custom document ID (backfill viability) + C: pipelines run ==")
        doc_id = "probe-doc-1"
        code, body = aoss.call("PUT", f"/{idx_existing}/_doc/{doc_id}", {"content": "original text"})
        ok_g = code in (200, 201)
        record("PUT /{index}/_doc/{id} with custom document ID", ok_g,
               f"HTTP {code}" + ("" if ok_g else f" {json.dumps(body)[:300]}"))

        if not ok_g:
            # fall back to auto-ID so we can still answer C
            code, body = aoss.call("POST", f"/{idx_existing}/_doc", {"content": "original text"})
            if code in (200, 201):
                doc_id = body.get("_id")
                record("fallback POST /_doc (auto ID)", True, f"_id={doc_id}")
            else:
                record("could not index any document", False, f"HTTP {code} {body}")
                return

        src = get_doc_with_retry(aoss, idx_existing, doc_id)
        if src is None:
            record("read document back", False, "document never became retrievable")
        else:
            print(f"  _source: {json.dumps(src)}")
            record("C: final_pipeline RAN (probe_final present)", src.get("probe_final") == "ran",
                   f"probe_final={src.get('probe_final')!r}")
            record("D: default_pipeline RAN (probe_default present)",
                   src.get("probe_default") == "ran",
                   f"probe_default={src.get('probe_default')!r}")

        # ---------- G2: overwrite same doc ID in place (the backfill motion) ----------
        if ok_g:
            print("\n== G2: overwrite the same document ID in place (backfill motion) ==")
            code, body = aoss.call(
                "PUT", f"/{idx_existing}/_doc/{doc_id}", {"content": "rewritten text"}
            )
            ok_g2 = code in (200, 201)
            record("overwrite same _id with full document", ok_g2,
                   f"HTTP {code} result={body.get('result')!r}")
            if ok_g2:
                s = {}
                for _ in range(12):
                    s = get_doc_with_retry(aoss, idx_existing, doc_id, attempts=1, delay=0) or {}
                    if s.get("content") == "rewritten text":
                        break
                    time.sleep(5)
                record("overwrite visible and pipelines re-ran",
                       s.get("content") == "rewritten text" and s.get("probe_final") == "ran",
                       f"content={s.get('content')!r} probe_final={s.get('probe_final')!r}")

        # ---------- E: request-level ?pipeline= override ----------
        print("\n== E: request-level ?pipeline= vs default vs final ==")
        code, body = aoss.call(
            "POST", f"/{idx_existing}/_doc?pipeline={pipe_request}", {"content": "override test"}
        )
        if code in (200, 201):
            oid = body.get("_id")
            s = get_doc_with_retry(aoss, idx_existing, oid) or {}
            print(f"  _source: {json.dumps(s)}")
            record("request pipeline ran", s.get("probe_request") == "ran",
                   f"probe_request={s.get('probe_request')!r}")
            record("E: final_pipeline STILL ran despite ?pipeline=",
                   s.get("probe_final") == "ran", f"probe_final={s.get('probe_final')!r}")
            record("E: default_pipeline was BYPASSED by ?pipeline= (expected)",
                   s.get("probe_default") is None, f"probe_default={s.get('probe_default')!r}")
        else:
            record("index with ?pipeline= override", False, f"HTTP {code} {json.dumps(body)[:300]}")

        # ---------- F: unset via _none ----------
        print("\n== F: unset final_pipeline via _none ==")
        code, body = aoss.call("PUT", f"/{idx_existing}/_settings", {"index.final_pipeline": "_none"})
        ok_f = code == 200
        record("PUT _settings index.final_pipeline=_none", ok_f,
               f"HTTP {code}" + ("" if ok_f else f" {json.dumps(body)[:300]}"))

    finally:
        print("\n== Cleanup ==")
        for idx in created_indices:
            code, _ = aoss.call("DELETE", f"/{idx}")
            print(f"  delete index {idx}: HTTP {code}")
        for p in created_pipelines:
            code, _ = aoss.call("DELETE", f"/_ingest/pipeline/{p}")
            print(f"  delete pipeline {p}: HTTP {code}")

        print("\n" + "=" * 72)
        print("SUMMARY")
        print("=" * 72)
        for label, ok, detail in results:
            mark = "PASS" if ok is True else ("FAIL" if ok is False else "INFO")
            print(f"[{mark}] {label}" + (f"  -- {detail}" if detail else ""))

        print("\n" + "=" * 72)
        print("VERDICT on the primary question")
        print("=" * 72)
        by_label = {l: ok for l, ok, _ in results}
        settable = by_label.get("PUT _settings index.final_pipeline on existing index")
        persisted = by_label.get("final_pipeline persisted (existing-index path)")
        ran = by_label.get("C: final_pipeline RAN (probe_final present)")
        if settable and persisted and ran:
            print("index.final_pipeline IS settable on an existing AOSS index AND it runs.")
            print("=> The ASE final_pipeline design is viable on AOSS.")
        elif settable and persisted and ran is False:
            print("final_pipeline is settable and persists, but did NOT run on ingest.")
            print("=> Settable but non-functional on AOSS. Do NOT rely on it.")
        elif settable is False:
            print("index.final_pipeline is NOT settable on an existing AOSS index.")
            print("=> The ASE final_pipeline design is NOT viable on AOSS as-is.")
        else:
            print("Inconclusive - see individual results above.")


if __name__ == "__main__":
    main()
