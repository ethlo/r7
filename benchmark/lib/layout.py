#!/usr/bin/env python3
"""Split this host's cores between housekeeping, backend, gateway and load generator.

    layout.py            prints shell assignments: OS_CPUS=... BACKEND_CPUS=... GATEWAY_CPUS=... LOAD_CPUS=...

One logical CPU per physical core is used for the benchmark, so the gateway never shares a
core with the load generator through an SMT sibling. On a hybrid Intel CPU the efficiency
cores take the housekeeping (every other process on the host) and the performance cores
are left for the benchmark, which is where their different speeds would otherwise show up
as noise. Elsewhere the first core does housekeeping.

Of the benchmark cores, nginx gets one, the load generator about half the rest, and the
gateway the remainder; the gateway gets the extra core when the count is odd.
"""

import subprocess
import sys


def parse_cpulist(text):
    out = set()
    for part in text.strip().split(","):
        if not part:
            continue
        lo, _, hi = part.partition("-")
        out.update(range(int(lo), int(hi or lo) + 1))
    return out


def cpulist(cpus):
    """[0, 1, 2, 5] -> '0-2,5'"""
    cpus = sorted(cpus)
    parts, start = [], None
    for i, c in enumerate(cpus):
        if start is None:
            start = c
        if i + 1 == len(cpus) or cpus[i + 1] != c + 1:
            parts.append(str(start) if start == c else "%d-%d" % (start, c))
            start = None
    return ",".join(parts)


def online_topology():
    """{cpu: (socket, core)} for every online logical CPU."""
    out = subprocess.run(["lscpu", "-p=CPU,CORE,SOCKET,ONLINE"],
                         capture_output=True, text=True, check=True).stdout
    topo = {}
    for line in out.splitlines():
        if line.startswith("#"):
            continue
        cpu, core, socket, online = line.split(",")[:4]
        if online.strip().upper() == "Y":
            topo[int(cpu)] = (int(socket), int(core))
    return topo


def efficiency_cpus():
    try:
        with open("/sys/devices/cpu_atom/cpus") as fh:
            return parse_cpulist(fh.read())
    except OSError:
        return set()


def layout(topo, ecpus):
    cores = {}
    for cpu, key in sorted(topo.items()):
        cores.setdefault(key, []).append(cpu)
    # One representative logical CPU per physical core, in CPU order.
    reps = sorted(cpus[0] for cpus in cores.values())
    siblings = {cpus[0]: cpus for cpus in cores.values()}

    if ecpus and any(r not in ecpus for r in reps) and any(r in ecpus for r in reps):
        os_cpus = sorted(c for r in reps if r in ecpus for c in siblings[r])
        bench = [r for r in reps if r not in ecpus]
    else:
        os_cpus = siblings[reps[0]]
        bench = reps[1:]

    if len(bench) < 3:
        raise SystemExit("need at least 3 cores besides housekeeping, found %d" % len(bench))

    n_load = max(1, (len(bench) - 1) // 2)
    return {
        "OS_CPUS": cpulist(os_cpus),
        "BACKEND_CPUS": cpulist(bench[:1]),
        "LOAD_CPUS": cpulist(bench[1:1 + n_load]),
        "GATEWAY_CPUS": cpulist(bench[1 + n_load:]),
    }


if __name__ == "__main__":
    for k, v in layout(online_topology(), efficiency_cpus()).items():
        print("%s=%s" % (k, v))
    sys.exit(0)
