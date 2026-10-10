#!/usr/bin/env python3
"""Parse wrk / wrk2 output into JSON, and render a comparison report.

    parse.py parse  <raw.txt> <meta.json> > result.json
    parse.py report <results-dir> [--format md|tsv]
    parse.py verdict <results-dir>     # reasons the results are not publishable, one per line
    parse.py max-rps <results-dir> <scenario> <journal> <workload>   # wrk median req/s, or nothing

Repeats of the same configuration are grouped and reported as a median plus a
spread, because a single run cannot tell you whether a 0.3ms difference is a
regression or the noise floor.

The parser is strict about *validity*. A run that produced 40k req/s and 300k
non-2xx responses is not a fast run, it is a broken run, and the report says so
rather than printing a headline number.
"""

import json
import os
import re
import statistics
import sys

# ---------------------------------------------------------------- parsing

_UNIT_MS = {"us": 0.001, "ms": 1.0, "s": 1000.0, "m": 60000.0, "h": 3600000.0}


def to_ms(text):
    """'1.02ms' -> 1.02, '780.00us' -> 0.78, '1.20s' -> 1200.0"""
    m = re.match(r"^([0-9.]+)\s*(us|ms|s|m|h)$", text.strip())
    if not m:
        return None
    return float(m.group(1)) * _UNIT_MS[m.group(2)]


def parse(raw):
    r = {
        "rps": None, "requests": None, "duration_s": None, "transfer_per_sec": None,
        "latency_avg_ms": None, "latency_max_ms": None, "latency_stdev_ms": None,
        "p50_ms": None, "p75_ms": None, "p90_ms": None, "p99_ms": None,
        "p999_ms": None, "p9999_ms": None, "p100_ms": None,
        "non_2xx": 0, "timeouts": 0,
        "socket_connect": 0, "socket_read": 0, "socket_write": 0,
        "hdr_histogram": False, "errors": [],
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

    m = re.search(
        r"Latency\s+([0-9.]+\s*(?:us|ms|s|m|h))\s+([0-9.]+\s*(?:us|ms|s|m|h))\s+([0-9.]+\s*(?:us|ms|s|m|h))",
        raw,
    )
    if m:
        r["latency_avg_ms"] = to_ms(m.group(1))
        r["latency_stdev_ms"] = to_ms(m.group(2))
        r["latency_max_ms"] = to_ms(m.group(3))

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


# ---------------------------------------------------------------- aggregation


def load_results(d):
    out = []
    for name in sorted(os.listdir(d)):
        if name.endswith(".json") and name not in ("summary.json",):
            with open(os.path.join(d, name)) as fh:
                try:
                    out.append(json.load(fh))
                except json.JSONDecodeError:
                    pass
    return out


def key_of(meta):
    return (meta.get("scenario"), meta.get("workload"), meta.get("tool"),
            meta.get("journal") or "-", meta.get("rate"))


def med(values):
    vals = [v for v in values if v is not None]
    return statistics.median(vals) if vals else None


def spread_pct(values):
    """Peak-to-peak as a percentage of the median. The noise floor, made visible."""
    vals = [v for v in values if v is not None]
    if len(vals) < 2:
        return None
    m = statistics.median(vals)
    if not m:
        return None
    return 100.0 * (max(vals) - min(vals)) / m


def group(rows):
    """Collapse repeats into one aggregate per configuration."""
    buckets = {}
    for r in rows:
        buckets.setdefault(key_of(r["meta"]), []).append(r)

    out = {}
    for k, rs in buckets.items():
        stats = [r["stats"] for r in rs]
        errors = []
        for s in stats:
            for e in s["errors"]:
                if e not in errors:
                    errors.append(e)
        out[k] = {
            "meta": rs[0]["meta"],
            "n": len(rs),
            "rps": med([s["rps"] for s in stats]),
            "rps_spread": spread_pct([s["rps"] for s in stats]),
            "p50_ms": med([s["p50_ms"] for s in stats]),
            "p50_spread": spread_pct([s["p50_ms"] for s in stats]),
            "p99_ms": med([s["p99_ms"] for s in stats]),
            "p99_spread": spread_pct([s["p99_ms"] for s in stats]),
            "p999_ms": med([s["p999_ms"] for s in stats]),
            "max_ms": med([s["latency_max_ms"] for s in stats]),
            "errors": errors,
        }
    return out


def repeats_floor(agg):
    """The fewest repeats among the configurations whose spread is a noise floor.

    Those are the wrk runs against the gateway: the throughput every cost is quoted from. The
    baseline is only the floor and the backend headroom check, and wrk2 runs hold a fixed rate,
    so the profile may run those once. With no such configuration, every one counts.
    """
    compared = [a["n"] for a in agg.values()
                if a["meta"].get("tool") == "wrk"
                and a["meta"].get("scenario") not in ("baseline", "sweep")]
    return min(compared or [a["n"] for a in agg.values()])


# ---------------------------------------------------------------- reporting


def fmt(v, prec=2):
    if v is None:
        return "-"
    if isinstance(v, float):
        return ("%%.%df" % prec) % v
    return str(v)


def pct(v):
    return "-" if v is None else "%.1f%%" % v


def delta(cur, ref):
    if not cur or not ref:
        return "-"
    return "%+.1f%%" % (100.0 * (cur / ref - 1.0))


def table(header, lines):
    widths = [max(len(header[i]), max((len(x[i]) for x in lines), default=0))
              for i in range(len(header))]

    def row(cells):
        return "| " + " | ".join(c.ljust(widths[i]) for i, c in enumerate(cells)) + " |"

    return [row(header), "|" + "|".join("-" * (w + 2) for w in widths) + "|"] \
        + [row(x) for x in lines]


def report(d, fmt_kind="md"):
    agg = group(load_results(d))
    if not agg:
        return "No results in %s\n" % d

    # Reference rows. baseline = no gateway. passthrough = r7 in path, no
    # filters, journal off - the honest denominator for filter and journal cost,
    # because it shares the topology and so cancels co-location noise.
    base_ref, r7_ref = {}, {}
    for k, a in agg.items():
        m = a["meta"]
        ref_key = (m.get("workload"), m.get("tool"), m.get("rate"))
        if m.get("scenario") == "baseline":
            base_ref[ref_key] = a
        elif m.get("scenario") == "passthrough":
            r7_ref[ref_key] = a

    main_hdr = ["scenario", "workload", "tool", "journal", "n",
                "req/s", "±", "vs base", "vs r7", "p50", "p99", "p99.9", "max", "bad"]
    sweep_hdr = ["journal", "workload", "rate", "n",
                 "req/s", "p50", "p99", "p99.9", "max", "bad"]
    main, sweep = [], []

    def sort_key(k):
        scenario, workload, tool, journal, rate = k
        # rate sorts numerically, not lexically: 20000 before 160000.
        return (scenario or "", journal or "", workload or "", tool or "",
                rate if rate is not None else -1)

    for k in sorted(agg, key=sort_key):
        a = agg[k]
        m = a["meta"]
        ref_key = (m.get("workload"), m.get("tool"), m.get("rate"))
        bad = "ok" if not a["errors"] else "INVALID"

        if m.get("scenario") == "sweep":
            sweep.append([
                m.get("journal") or "-", m.get("workload"), str(m.get("rate")),
                str(a["n"]), fmt(a["rps"], 0), fmt(a["p50_ms"], 3),
                fmt(a["p99_ms"], 3), fmt(a["p999_ms"], 3), fmt(a["max_ms"], 2), bad,
            ])
            continue

        b = base_ref.get(ref_key)
        r = r7_ref.get(ref_key)
        # baseline has no gateway in it, so "cost relative to the gateway" is
        # not a thing it has; leave that cell empty rather than printing a
        # meaningless positive delta.
        is_baseline = m.get("scenario") == "baseline"
        main.append([
            m.get("scenario"), m.get("workload"), m.get("tool"),
            m.get("journal") or "-", str(a["n"]),
            fmt(a["rps"], 0), pct(a["rps_spread"]),
            delta(a["rps"], b["rps"]) if b and b is not a else "-",
            delta(a["rps"], r["rps"]) if r and r is not a and not is_baseline else "-",
            fmt(a["p50_ms"], 3), fmt(a["p99_ms"], 3),
            fmt(a["p999_ms"], 3), fmt(a["max_ms"], 2), bad,
        ])

    if fmt_kind == "tsv":
        out = []
        if main:
            out += ["\t".join(main_hdr)] + ["\t".join(x) for x in main]
        if sweep:
            out += [""] + ["\t".join(sweep_hdr)] + ["\t".join(x) for x in sweep]
        return "\n".join(out) + "\n"

    out = []
    if main:
        out += table(main_hdr, main)
    if sweep:
        out += ["", "### Rate sweep (wrk2)", ""] + table(sweep_hdr, sweep)

    problems = []
    for k, a in agg.items():
        for e in a["errors"]:
            m = a["meta"]
            problems.append("  - [%s/%s/%s/%s] %s" % (
                m.get("scenario"), m.get("journal") or "-",
                m.get("workload"), m.get("tool"), e))
    if problems:
        out += ["", "**Validity warnings** (these runs should not be quoted):"] + problems

    # The least-repeated throughput configuration decides this, not the best one: a report where
    # one row has three runs and another has one is exactly the report that needs the
    # warning, and keying off the maximum is what silences it.
    n_min = repeats_floor(agg)
    out += [
        "",
        "Latencies in milliseconds, medians across `n` repeats. `±` is the",
        "peak-to-peak spread of throughput as a percentage of the median: your",
        "noise floor. A difference smaller than `±` is not a finding.",
        "",
        "`vs base` is throughput against the no-gateway baseline. It is inflated",
        "whenever the load generator shares a host with the gateway, so treat it",
        "as an upper bound on cost, not a measurement.",
        "",
        "`vs r7` is throughput against the `passthrough` run - same topology, same",
        "co-location, so the co-location noise cancels. **This is the number to",
        "quote for filter and journal cost.**",
        "",
        "wrk2 rows are rate-limited and correct for coordinated omission, so their",
        "latency figures are the ones to quote. wrk rows show saturation",
        "throughput; their latency figures understate the tail.",
    ]
    if n_min < 3:
        out += [
            "",
            "> Only %d repeat(s) for at least one throughput configuration. Run with `--repeat 3` or" % n_min,
            "> more before trusting any difference under a few percent.",
        ]
    return "\n".join(out) + "\n"


def verdict_problems(d, min_repeat=3):
    """Why the results in d should not be quoted, as a list of sentences; empty if none.

    The bar for a published number: every planned run present (plan.tsv, written by run.sh)
    and valid, and every gateway throughput configuration repeated. How far the repeats agree
    is not a bar: the report prints each configuration's spread next to its value, so the
    reader judges the noise rather than a threshold someone picked.
    """
    # Unlike the report, a broken result file is a failed run, not one to skip: dropping it
    # would hide exactly the run that went wrong.
    problems = []
    for name in sorted(os.listdir(d)):
        if name.endswith(".json") and name != "summary.json":
            try:
                with open(os.path.join(d, name)) as fh:
                    json.load(fh)
            except (OSError, json.JSONDecodeError):
                problems.append("unreadable result file %s" % name)
    rows = load_results(d)
    # Repeat counts and headroom can only be judged on results that exist; the plan run.sh
    # wrote before its first run is what says which ones should.
    try:
        with open(os.path.join(d, "plan.tsv")) as fh:
            planned = set()
            for line in fh:
                if line.strip():
                    s, w, t, j, rate, rep = line.rstrip("\n").split("\t")
                    # "auto": a sweep rate run.sh never derived, so that sweep never ran.
                    r = None if rate == "-" else rate if rate == "auto" else int(rate)
                    planned.add(((s, w, t, j, r), int(rep)))
    except (OSError, ValueError):
        planned = None
        problems.append("no readable run plan (plan.tsv), so missing runs cannot be detected")
    if planned is not None:
        if not planned:
            problems.append("the run plan is empty")
        done = {(key_of(r["meta"]), r["meta"].get("repeat")) for r in rows}
        missing = sorted(planned - done, key=lambda p: (str(p[0]), p[1]))
        if missing:
            (s, w, t, j, rate), rep = missing[0]
            problems.append("%d planned run(s) have no result, e.g. %s/%s/%s/%s%s repeat %d"
                            % (len(missing), s, j, w, t, "" if rate is None else "@%s" % rate, rep))
    # A load generator that failed is a failed run, whatever its output looks like. A result
    # without the field predates it and is judged by its output alone.
    failed = [r for r in rows if (r["meta"].get("exit_status") or 0) != 0]
    if failed:
        problems.append("%d run(s) where the load generator exited with an error" % len(failed))
    # A run that completed nothing reports no errors and no spread, so it needs its own check.
    empty = [r for r in rows
             if not (r["stats"].get("rps") or 0) > 0 or not (r["stats"].get("requests") or 0) > 0]
    if empty:
        problems.append("%d run(s) completed no requests" % len(empty))
    # wrk2's percentiles are the latency numbers to quote (README); a repeat without them
    # would otherwise vanish into the median and leave a "-" in a publishable report.
    no_tail = [r for r in rows if r["meta"].get("tool") == "wrk2"
               and (r["stats"].get("p99_ms") is None or r["stats"].get("p999_ms") is None)]
    if no_tail:
        problems.append("%d wrk2 run(s) report no p99 or p99.9 latency" % len(no_tail))
    # A fixed-rate latency comparison only means something at the rate it asked for; only
    # the sweep is meant to fall short, since finding where it does is its purpose.
    short = [r for r in rows if r["meta"].get("tool") == "wrk2"
             and r["meta"].get("scenario") != "sweep" and r["meta"].get("rate")
             and (r["stats"].get("rps") or 0) < 0.95 * r["meta"]["rate"]]
    if short:
        problems.append("%d non-sweep wrk2 run(s) delivered under 95%% of their target rate"
                        % len(short))
    agg = group(rows)
    if not agg:
        return problems + ["no results"]
    invalid = [k for k, a in agg.items() if a["errors"]]
    if invalid:
        problems.append("%d configuration(s) have invalid runs" % len(invalid))
    n_min = repeats_floor(agg)
    if n_min < min_repeat:
        problems.append("only %d repeat(s) for some throughput configuration; need %d" % (n_min, min_repeat))
    # The backend must not be the bottleneck (README, "The backend"): per workload, the
    # unthrottled baseline has to clear every gateway row by a margin.
    for workload in sorted({a["meta"].get("workload") for a in agg.values()}):
        wrk = [a for a in agg.values()
               if a["meta"].get("workload") == workload and a["meta"].get("tool") == "wrk"]
        base = [a["rps"] for a in wrk if a["meta"].get("scenario") == "baseline" and a["rps"]]
        gw = [a["rps"] for a in wrk
              if a["meta"].get("scenario") not in ("baseline", "sweep") and a["rps"]]
        if not base:
            problems.append("no wrk baseline for %s, so backend headroom is unknown" % workload)
        elif gw and base[0] < 1.1 * max(gw):
            problems.append("backend headroom under 10%% for %s: baseline %.0f req/s, gateway up to %.0f"
                            % (workload, base[0], max(gw)))
    return problems


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
    elif cmd == "max-rps":
        rps = [a["rps"] for a in group(load_results(sys.argv[2])).values()
               if a["meta"].get("scenario") == sys.argv[3] and a["meta"].get("tool") == "wrk"
               and (a["meta"].get("journal") or "-") == sys.argv[4]
               and a["meta"].get("workload") == sys.argv[5] and a["rps"]]
        if rps:
            print(round(max(rps)))
    elif cmd == "verdict":
        for p in verdict_problems(sys.argv[2]):
            print(p)
    else:
        sys.exit(__doc__)
