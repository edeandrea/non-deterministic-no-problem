#!/usr/bin/env python3
"""Scores the #221 spike results: spike/results/<label>-<backend>.jsonl against spike/dataset/emails.json.

python3 spike/analyze.py [label ...]   (default: every label found)  -> prints Markdown, writes spike/results/summary.json
"""
import json
import math
import pathlib
import statistics
import sys
from collections import Counter, defaultdict

ROOT = pathlib.Path(__file__).parent
DATASET = {i["id"]: i for i in json.loads((ROOT / "dataset/emails.json").read_text())}
RESULTS = ROOT / "results"
TYPES = ["NEW_CLAIM", "CLAIM_FOLLOW_UP", "NOT_A_CLAIM"]
ITEMS = [("described", "described"), ("dated", "dated"), ("located", "located")]

# USD per million tokens (input, output). gpt-5-mini is the production claim-intake model (OpenAI list price);
# Jev from docs.typesafe.ai/models; the OpenAI Decisions API (gpt-6-luna) from third-party price trackers.
PRICES = {"gpt-5-mini": (0.25, 2.00), "jev-1.14.0": (0.042, 0.0), "gpt-6-luna decisions": (0.10, 0.0)}


def load(path):
	lines = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
	warmup = next((l["warmup_ms"] for l in lines if l["id"] == "_warmup"), None)
	return warmup, {l["id"]: l for l in lines if l["id"] != "_warmup"}


def pct(values, p):
	if not values:
		return None
	values = sorted(values)
	k = (len(values) - 1) * p / 100
	f, c = math.floor(k), math.ceil(k)
	return values[f] if f == c else values[f] + (values[c] - values[f]) * (k - f)


def ece(pairs, bins=10):
	"""Expected calibration error over (confidence, correct) pairs."""
	if not pairs:
		return None
	total = 0.0
	for b in range(bins):
		lo, hi = b / bins, (b + 1) / bins
		chunk = [(c, ok) for c, ok in pairs if (lo < c <= hi) or (b == 0 and c == 0)]
		if chunk:
			total += len(chunk) / len(pairs) * abs(statistics.mean(c for c, _ in chunk) - statistics.mean(ok for _, ok in chunk))
	return total


def auroc(pairs):
	"""How well the confidence separates right from wrong answers (0.5 = no better than chance)."""
	pos = [c for c, ok in pairs if ok]
	neg = [c for c, ok in pairs if not ok]
	if not pos or not neg:
		return None
	wins = sum(1.0 if p > n else 0.5 if p == n else 0.0 for p in pos for n in neg)
	return wins / (len(pos) * len(neg))


def selective(pairs, thresholds):
	"""Accuracy and coverage of the answers whose confidence reaches each threshold (the rest go to a human)."""
	out = {}
	for t in thresholds:
		kept = [ok for c, ok in pairs if c >= t]
		out[t] = {"coverage": len(kept) / len(pairs) if pairs else None, "accuracy": statistics.mean(kept) if kept else None, "to_human": len(pairs) - len(kept)}
	return out


def score(label, backend, warmup, rows):
	r = {"label": label, "backend": backend, "warmup_ms": warmup}
	# ---------------- classification
	cls = [(i, rows[i]["classification"]) for i in rows if DATASET[i]["expected"]["email_type"]]
	answered = [(i, c) for i, c in cls if c.get("value")]
	r["cls_n"] = len(cls)
	r["cls_errors"] = len(cls) - len(answered)
	ok = lambda i, c: c.get("value") == DATASET[i]["expected"]["email_type"]
	r["cls_accuracy"] = sum(ok(i, c) for i, c in cls) / len(cls)
	inj = [(i, c) for i, c in cls if "injection" in DATASET[i]["tags"]]
	r["cls_injection_accuracy"] = sum(ok(i, c) for i, c in inj) / len(inj) if inj else None
	r["cls_confusion"] = {t: dict(Counter(c.get("value") or "ERROR" for i, c in cls if DATASET[i]["expected"]["email_type"] == t)) for t in TYPES}
	r["cls_wrong"] = sorted(f"{i}->{c.get('value') or 'ERROR'}" for i, c in cls if not ok(i, c))
	r["cls_ms_p50"] = pct([c["ms"] for _, c in answered], 50)
	r["cls_ms_p95"] = pct([c["ms"] for _, c in answered], 95)
	if any("margin" in c for _, c in answered):
		margin = [(c["margin"], ok(i, c)) for i, c in answered]
		top = [(max(c["probabilities"].values()), ok(i, c)) for i, c in answered]
		r["cls_ece_top_probability"] = ece(top)
		r["cls_auroc_margin"] = auroc(margin)
		r["cls_selective_margin"] = selective(margin, [0.5, 0.8, 0.9, 0.95])
		r["cls_wrong_margins"] = sorted(round(c["margin"], 3) for i, c in answered if not ok(i, c))

	# ---------------- assessment (completeness + category)
	asm = [(i, rows[i].get("assessment")) for i in rows if rows[i].get("assessment") is not None]
	r["asm_n"] = len(asm)
	answered = [(i, a) for i, a in asm if not a.get("error")]
	r["asm_errors"] = sorted(i for i, a in asm if a.get("error"))
	r["asm_fallbacks"] = sum(1 for _, a in asm if a.get("fallback"))
	exp = lambda i: DATASET[i]["expected"]
	per_item = {}
	for key, label_key in ITEMS:
		per_item[key] = sum(a.get(key) == exp(i)[label_key] for i, a in asm) / len(asm)
	per_item["category_present"] = sum((a.get("category") is not None) == (exp(i)["category"] is not None) for i, a in asm if not a.get("error")) / len(asm)
	r["asm_item_accuracy"] = per_item
	missing = lambda d, cat: frozenset(k for k, v in (("INCIDENT_DESCRIPTION", d["described"]), ("INCIDENT_DATE", d["dated"]), ("LOCATION", d["located"]), ("CATEGORY", cat is not None)) if not v)
	r["asm_missing_set_exact"] = sum(
		(not a.get("error")) and missing(a, a.get("category")) == missing(exp(i), exp(i)["category"]) for i, a in asm) / len(asm)
	r["asm_category_accuracy"] = sum(a.get("category") == exp(i)["category"] for i, a in asm if not a.get("error")) / len(asm)
	described = [(i, a) for i, a in asm if exp(i)["category"]]
	r["asm_category_accuracy_when_described"] = sum(a.get("category") == exp(i)["category"] for i, a in described) / len(described) if described else None
	inj = [(i, a) for i, a in asm if "injection" in DATASET[i]["tags"]]
	r["asm_injection_missing_set_exact"] = sum((not a.get("error")) and missing(a, a.get("category")) == missing(exp(i), exp(i)["category"]) for i, a in inj) / len(inj) if inj else None
	r["asm_wrong"] = sorted(
		f"{i}:" + ",".join(k for k, lk in ITEMS if a.get(k) != exp(i)[lk]) + (f",cat={a.get('category')}" if a.get("category") != exp(i)["category"] else "")
		for i, a in answered if any(a.get(k) != exp(i)[lk] for k, lk in ITEMS) or a.get("category") != exp(i)["category"])
	r["asm_ms_p50"] = pct([a["ms"] for _, a in answered], 50)
	r["asm_ms_p95"] = pct([a["ms"] for _, a in answered], 95)
	if any("p_described" in a for _, a in answered):
		yes_no = []
		for i, a in answered:
			for key, label_key in ITEMS:
				p = a[f"p_{key}"]
				yes_no.append((p, exp(i)[label_key]))
		r["yesno_brier"] = statistics.mean((p - (1.0 if y else 0.0)) ** 2 for p, y in yes_no)
		confidence = [(max(p, 1 - p), (p >= 0.5) == y) for p, y in yes_no]
		r["yesno_ece"] = ece(confidence)
		r["yesno_auroc"] = auroc(confidence)
		r["yesno_selective"] = selective(confidence, [0.6, 0.8, 0.9, 0.95])
		cat = [(a["category_margin"], a.get("category") == exp(i)["category"]) for i, a in answered]
		r["cat_auroc_margin"] = auroc(cat)
		r["cat_selective_margin"] = selective(cat, [0.5, 0.8, 0.9])

	# ---------------- tokens, per email (classification + assessment when scored)
	per_email_in, per_email_out, totals_ms = [], [], []
	for i, row in rows.items():
		c, a = row["classification"], row.get("assessment") or {}
		per_email_in.append((c.get("input_tokens") or 0) + (a.get("input_tokens") or 0))
		per_email_out.append((c.get("output_tokens") or 0) + (a.get("output_tokens") or 0))
		totals_ms.append(c["ms"] + a.get("ms", 0))
	r["tokens_in_mean"] = statistics.mean(per_email_in)
	r["tokens_out_mean"] = statistics.mean(per_email_out)
	r["email_ms_p50"] = pct(totals_ms, 50)
	r["email_ms_p95"] = pct(totals_ms, 95)
	return r


def main():
	labels = sys.argv[1:]
	scores = []
	for path in sorted(RESULTS.glob("*.jsonl")):
		label, backend = path.stem.split("-", 1)
		if labels and label not in labels:
			continue
		warmup, rows = load(path)
		if rows:
			scores.append(score(label, backend, warmup, rows))
	(RESULTS / "summary.json").write_text(json.dumps(scores, indent=1, default=str))

	def f(v, d=3):
		return "–" if v is None else (f"{v:.{d}f}" if isinstance(v, float) else str(v))

	print("| run | backend | class. acc | injection acc | errors | cls p50/p95 ms | missing-set exact | category acc | described | dated | located | asm errors (fallbacks) | asm p50/p95 ms | email p95 ms | tokens in/out per email |")
	print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
	for s in scores:
		ia = s["asm_item_accuracy"]
		print(f"| {s['label']} | {s['backend']} | {f(s['cls_accuracy'])} | {f(s['cls_injection_accuracy'])} | {s['cls_errors']} | {f(s['cls_ms_p50'],0)}/{f(s['cls_ms_p95'],0)} | "
			f"{f(s['asm_missing_set_exact'])} | {f(s['asm_category_accuracy'])} | {f(ia['described'])} | {f(ia['dated'])} | {f(ia['located'])} | {len(s['asm_errors'])} ({s['asm_fallbacks']}) | "
			f"{f(s['asm_ms_p50'],0)}/{f(s['asm_ms_p95'],0)} | {f(s['email_ms_p95'],0)} | {f(s['tokens_in_mean'],0)}/{f(s['tokens_out_mean'],0)} |")
	print()
	for s in scores:
		if "cls_auroc_margin" in s:
			print(f"- **{s['label']}/{s['backend']} calibration**: classification ECE {f(s['cls_ece_top_probability'])}, AUROC(margin) {f(s['cls_auroc_margin'])}, "
				f"wrong-answer margins {s['cls_wrong_margins']}; yes/no Brier {f(s.get('yesno_brier'))}, ECE {f(s.get('yesno_ece'))}, AUROC {f(s.get('yesno_auroc'))}; "
				f"category AUROC(margin) {f(s.get('cat_auroc_margin'))}")
			print(f"  - classification selective (margin ≥ t): " + ", ".join(f"t={t}: cov {f(v['coverage'],2)} acc {f(v['accuracy'],3)}" for t, v in s["cls_selective_margin"].items()))
			if "yesno_selective" in s:
				print(f"  - yes/no selective (max(p,1-p) ≥ t): " + ", ".join(f"t={t}: cov {f(v['coverage'],2)} acc {f(v['accuracy'],3)}" for t, v in s["yesno_selective"].items()))
	print()
	for s in scores:
		print(f"- **{s['label']}/{s['backend']}** wrong class.: {s['cls_wrong']}")
		print(f"  - wrong assessment: {s['asm_wrong']}; errors: {s['asm_errors']}")
	print()
	for s in scores:
		cost = {name: (s["tokens_in_mean"] * pin + s["tokens_out_mean"] * pout) / 1e6 for name, (pin, pout) in PRICES.items()}
		print(f"- {s['label']}/{s['backend']}: warm-up {s['warmup_ms']} ms; cost per email at list prices: " + ", ".join(f"{k} ${v:.6f}" for k, v in cost.items()))


if __name__ == "__main__":
	main()
