#!/usr/bin/env python3
"""Parse wrk / wrk2 output into JSON, and render a comparison report.

Two modes:

    parse.py parse  <raw.txt> <meta.json> > result.json
    parse.py report <results-dir> [--format md|tsv]

The parser is intentionally strict about *validity*. A benchmark run that
produced 40k req/s and 300k non-2xx responses is not a fast run, it is a
broken run, and the report says so rather than printing a headline number.
"""

import json
import os
import re
import sys

# ---------------------------------------------------------------- parsing

_UNIT_MS = {"us": 0.001, "ms": 1.0, "s": 1000.0, "m": 60000.0, "h": 3600000.0}


def to_ms(text):
    """'1.02ms' -> 1.02, '780.00us' -> 0.78, '1.20s' -> 1200.0"""
    m = re.match(r"^([0-9.]+)\s*(us|ms|s|m|h)$", text.strip())
    if not m:
        return None
    return float(m.group(1)) * _UNIT_MS[m.group(2)]


def to_count(text):
    """'123.45k' -> 123450.0"""
    m = re.match(r"^([0-9.]+)\s*([kKmMgG]?)$", text.strip())
    if not m:
        return None
    mult = {"": 1, "k": 1e3, "m": 1e6, "g": 1e9}[m.group(2).lower()]
    return float(m.group(1)) * mult


def parse(raw):
    r = {
        "rps": None,
        "requests": None,
        "duration_s": None,
        "transfer_per_sec": None,
        "latency_avg_ms": None,
        "latency_max_ms": None,
        "latency_stdev_ms": None,
        "p50_ms": None,
        "p75_ms": None,
        "p90_ms": None,
        "p99_ms": None,
        "p999_ms": None,
        "p9999_ms": None,
        "p100_ms": None,
        "non_2xx": 0,
        "timeouts": 0,
        "socket_connect": 0,
        "socket_read": 0,
        "socket_write": 0,
        "hdr_histogram": False,
        "errors": [],
    }

    m = re.search(r"Requests/sec:\s*([0-9.]+)", raw)
    if m:
        r["rps"] = float(m.group(1))

    m = re.search(r"([0-9]+) requests in ([0-9.]+)([a-z]+),\s*([0-9.]+\s*\w+) read", raw)
    if m:
        r["requests"] = int(m.group(1))
        r["duration_s"] = to_ms(m.group(2) + m.group(3)) / 1000.0

    m = re.search(r"Transfer/sec:\s*([0-9.]+\s*\w+)", raw)
    if m:
        r["transfer_per_sec"] = m.group(1).strip()

    # Thread Stats line: "Latency     1.10ms  520.00us  21.25ms   88.12%"
    m = re.search(
        r"Latency\s+([0-9.]+\s*(?:us|ms|s|m|h))\s+([0-9.]+\s*(?:us|ms|s|m|h))\s+([0-9.]+\s*(?:us|ms|s|m|h))",
        raw,
    )
    if m:
        r["latency_avg_ms"] = to_ms(m.group(1))
        r["latency_stdev_ms"] = to_ms(m.group(2))
        r["latency_max_ms"] = to_ms(m.group(3))

    # wrk2 emits an HdrHistogram block; wrk emits a coarse one. Prefer HdrHistogram.
    r["hdr_histogram"] = "HdrHistogram" in raw

    pct_map = {
        "50.000%": "p50_ms", "50%": "p50_ms",
        "75.000%": "p75_ms", "75%": "p75_ms",
        "90.000%": "p90_ms", "90%": "p90_ms",
        "99.000%": "p99_ms", "99%": "p99_ms",
        "99.900%": "p999_ms",
        "99.990%": "p9999_ms",
        "100.000%": "p100_ms",
    }
    for line in raw.splitlines():
        m = re.match(r"\s*([0-9.]+%)\s+([0-9.]+\s*(?:us|ms|s|m|h))\s*$", line)
        if m and m.group(1) in pct_map:
            key = pct_map[m.group(1)]
            if r[key] is None:
                r[key] = to_ms(m.group(2))

    m = re.search(r"Non-2xx or 3xx responses:\s*([0-9]+)", raw)
    if m:
        r["non_2xx"] = int(m.group(1))

    m = re.search(
        r"Socket errors:\s*connect ([0-9]+), read ([0-9]+), write ([0-9]+), timeout ([0-9]+)",
        raw,
    )
    if m:
        r["socket_connect"] = int(m.group(1))
        r["socket_read"] = int(m.group(2))
        r["socket_write"] = int(m.group(3))
        r["timeouts"] = int(m.group(4))

    # Validity gates
    if r["rps"] is None:
        r["errors"].append("no Requests/sec line: the load generator failed")
    if r["requests"] and r["non_2xx"]:
        pct = 100.0 * r["non_2xx"] / r["requests"]
        if pct > 0.1:
            r["errors"].append(
                "%.2f%% non-2xx/3xx responses - check for rate limiting, auth "
                "filters or an upstream that fell over" % pct
            )
    if r["socket_connect"] or r["socket_read"] or r["socket_write"]:
        r["errors"].append(
            "socket errors (connect=%d read=%d write=%d): keep-alive is not "
            "holding, so this measures reconnects"
            % (r["socket_connect"], r["socket_read"], r["socket_write"])
        )
    if r["timeouts"]:
        r["errors"].append("%d request timeouts" % r["timeouts"])

    return r


# ---------------------------------------------------------------- reporting


def load_results(d):
    out = []
    for name in sorted(os.listdir(d)):
        if name.endswith(".json") and name != "summary.json":
            with open(os.path.join(d, name)) as fh:
                try:
                    out.append(json.load(fh))
                except json.JSONDecodeError:
                    pass
    return out


def fmt(v, prec=2):
    if v is None:
        return "-"
    if isinstance(v, float):
        return ("%%.%df" % prec) % v
    return str(v)


def report(d, fmt_kind="md"):
    rows = load_results(d)
    if not rows:
        return "No results in %s\n" % d

    baselines = {}
    for r in rows:
        if r["meta"]["scenario"] == "baseline":
            baselines[(r["meta"]["tool"], r["meta"]["workload"])] = r

    header = [
        "scenario", "workload", "tool", "journal",
        "req/s", "vs base", "p50", "p99", "p99.9", "max", "bad",
    ]
    lines = []
    for r in rows:
        m, s = r["meta"], r["stats"]
        base = baselines.get((m["tool"], m["workload"]))
        if base and base is not r and base["stats"]["rps"] and s["rps"]:
            delta = "%+.1f%%" % (100.0 * (s["rps"] / base["stats"]["rps"] - 1.0))
        else:
            delta = "-"
        bad = "ok" if not s["errors"] else "INVALID"
        lines.append([
            m["scenario"], m["workload"], m["tool"], m.get("journal", "-"),
            fmt(s["rps"], 0), delta,
            fmt(s["p50_ms"], 3), fmt(s["p99_ms"], 3),
            fmt(s["p999_ms"], 3), fmt(s["latency_max_ms"], 2), bad,
        ])

    if fmt_kind == "tsv":
        out = ["\t".join(header)] + ["\t".join(x) for x in lines]
        return "\n".join(out) + "\n"

    widths = [max(len(header[i]), max(len(x[i]) for x in lines)) for i in range(len(header))]
    def row(cells):
        return "| " + " | ".join(c.ljust(widths[i]) for i, c in enumerate(cells)) + " |"
    out = [
        row(header),
        "|" + "|".join("-" * (w + 2) for w in widths) + "|",
    ] + [row(x) for x in lines]

    problems = []
    for r in rows:
        for e in r["stats"]["errors"]:
            problems.append("  - [%s/%s/%s] %s" % (
                r["meta"]["scenario"], r["meta"]["workload"], r["meta"]["tool"], e))
    if problems:
        out += ["", "**Validity warnings** (these runs should not be quoted):"] + problems

    out += [
        "",
        "Latencies in milliseconds. `vs base` compares throughput against the",
        "`baseline` run (load generator straight at the backend) for the same",
        "workload and tool; it is the cost of putting r7 in the path.",
        "",
        "wrk2 rows are rate-limited and correct for coordinated omission, so",
        "their latency figures are the ones to quote. wrk rows show saturation",
        "throughput; their latency figures understate the tail.",
    ]
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- main

if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd == "parse":
        with open(sys.argv[2]) as fh:
            raw = fh.read()
        meta = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
        json.dump({"meta": meta, "stats": parse(raw)}, sys.stdout, indent=2)
        sys.stdout.write("\n")
    elif cmd == "report":
        kind = "md"
        if "--format" in sys.argv:
            kind = sys.argv[sys.argv.index("--format") + 1]
        sys.stdout.write(report(sys.argv[2], kind))
    else:
        sys.exit(__doc__)
