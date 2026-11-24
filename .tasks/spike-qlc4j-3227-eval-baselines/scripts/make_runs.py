"""Create two Langfuse v4 experiments (A = recorded outputs, B = degraded) over the same dataset items, as OTLP spans
carrying the langfuse.experiment.* attributes, plus a score per item.
usage: python3 -I make_runs.py <items.json> <LF base url> <user:secret> <dataset id>"""
import base64
import json
import os
import sys
import time
import urllib.request
import uuid

items_file, base, auth, dataset_id = sys.argv[1:5]
hdr = {"Authorization": "Basic " + base64.b64encode(auth.encode()).decode(), "Content-Type": "application/json"}


def call(method, path, body):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(), headers=hdr, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode()[:300]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


def attr(k, v):
    return {"key": k, "value": {"stringValue": v if isinstance(v, str) else json.dumps(v)}}


items = [i for i in json.load(open(items_file))["data"] if i["status"] == "ACTIVE"]
degraded = {0: "I don't know.", 2: "Please contact support."}
runs = (("run-A-recorded", "exp-a-" + uuid.uuid4().hex[:8], lambda n, i: i["expectedOutput"]),
        ("run-B-degraded", "exp-b-" + uuid.uuid4().hex[:8], lambda n, i: degraded.get(n, i["expectedOutput"])))

for run_name, exp_id, outputs in runs:
    spans = []
    scores = []
    for n, item in enumerate(items):
        trace_id = os.urandom(16).hex()
        span_id = os.urandom(8).hex()
        output = outputs(n, item)
        start = time.time_ns()
        spans.append({
            "traceId": trace_id, "spanId": span_id, "name": "experiment-item", "kind": 1,
            "startTimeUnixNano": str(start), "endTimeUnixNano": str(start + 5_000_000),
            "attributes": [
                attr("langfuse.observation.input", str(item["input"])),
                attr("langfuse.observation.output", output),
                attr("langfuse.environment", "sdk-experiment"),
                attr("langfuse.experiment.id", exp_id),
                attr("langfuse.experiment.name", run_name),
                attr("langfuse.experiment.dataset.id", dataset_id),
                attr("langfuse.experiment.item.id", item["id"]),
                attr("langfuse.experiment.item.version", item.get("version") or ""),
                attr("langfuse.experiment.item.expected_output", str(item["expectedOutput"])),
                attr("langfuse.experiment.item.root_observation_id", span_id),
                attr("langfuse.experiment.metadata", {"spike": True, "dataset_version": item.get("version")}),
            ]})
        scores.append((trace_id, span_id, 1 if output == item["expectedOutput"] else 0))
    body = {"resourceSpans": [{"resource": {"attributes": [attr("service.name", "eval-spike")]},
                               "scopeSpans": [{"scope": {"name": "langfuse-sdk", "version": "4.0.0"}, "spans": spans}]}]}
    print(run_name, exp_id, "otel", call("POST", "/api/public/otel/v1/traces", body))
    for trace_id, span_id, value in scores:
        print(run_name, "score", call("POST", "/api/public/scores",
                                      {"traceId": trace_id, "observationId": span_id, "name": "correct", "value": value,
                                       "dataType": "NUMERIC", "environment": "sdk-experiment"})[0])
