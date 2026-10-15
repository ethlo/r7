#!/usr/bin/env python3
"""Summaries of the profiles benchmark/profile.sh collects.

    profile_summary.py cpu      <cpu.collapsed>              # where CPU goes, kernel included
    profile_summary.py alloc    <alloc.collapsed>            # what is allocated, and where
    profile_summary.py syscalls <strace -c output> <requests>
    profile_summary.py jit      <PrintInlining log> <Compiler.codelist output>

The collapsed files are async-profiler's: one stack per line, frames root first and joined
by ';', then a space and a count. Kernel frames end in '_[k]'.
"""

import collections
import re
import sys


def read_collapsed(path):
    for line in open(path):
        line = line.rstrip()
        if not line:
            continue
        stack, _, count = line.rpartition(" ")
        yield stack.split(";"), int(count)


def pct(n, total):
    return "%5.1f%%" % (100.0 * n / total if total else 0)


# Owners, deepest match wins: who to blame for a sample in Java code.
OWNERS = [
    ("r7", ("com/ethlo/",)),
    ("Helidon", ("io/helidon/",)),
    ("virtual-thread scheduler and poller", (
        "java/lang/VirtualThread", "java/util/concurrent/ForkJoin", "jdk/internal/vm/Continuation",
        "java/util/concurrent/DelayScheduler", "sun/nio/ch/Poller", "sun/nio/ch/EPoll")),
]


def kernel_bucket(java_frames):
    """A kernel sample, named by the system call the Java side was in."""
    caller = java_frames[-1] if java_frames else ""
    if "write" in caller:
        return "kernel: socket writes (includes loopback delivery and waking the reader)"
    if "read" in caller and "pthread" not in caller:
        return "kernel: socket reads"
    if "epoll_wait" in caller:
        return "kernel: epoll_wait and scheduling"
    if "epoll_ctl" in caller:
        return "kernel: epoll_ctl"
    if any(x in caller for x in ("futex", "pthread", "Parker", "Unpark")):
        return "kernel: futex park and unpark (thread handoffs)"
    return "kernel: other"


def cpu(path):
    buckets = collections.Counter()
    leaf = collections.Counter()
    inclusive = collections.Counter()
    total = 0
    for frames, n in read_collapsed(path):
        total += n
        java = [f for f in frames if not f.endswith("_[k]")]
        if frames[-1].endswith("_[k]"):
            buckets[kernel_bucket(java)] += n
        else:
            owner = next((name for f in reversed(frames) for name, prefixes in OWNERS
                          if f.startswith(prefixes)), "JVM and native")
            buckets["java: " + owner] += n
            leaf[frames[-1]] += n
        for f in set(java):
            if f.startswith(("com/ethlo/", "io/helidon/", "java/util/concurrent/DelayScheduler")):
                inclusive[f] += n
    print("CPU samples: %d" % total)
    print("\nBy owner")
    for k, n in buckets.most_common():
        print("  %s  %s" % (pct(n, total), k))
    print("\nHottest user-space frames (self)")
    for k, n in leaf.most_common(25):
        print("  %s  %s" % (pct(n, total), k[:140]))
    print("\nr7 and Helidon frames, inclusive")
    for k, n in inclusive.most_common(30):
        print("  %s  %s" % (pct(n, total), k[:140]))


def alloc(path):
    types = collections.Counter()
    sites = collections.Counter()
    total = 0
    for frames, n in read_collapsed(path):
        total += n
        types[frames[-1]] += n
        owner = next((f for f in reversed(frames[:-1]) if f.startswith(("com/ethlo/", "io/helidon/"))), "?")
        sites["%s -> %s" % (owner, frames[-1].replace("_[i]", "").replace("_[k]", ""))] += n
    print("Allocation samples: %d (bytes per request: compare jstat -gc before and after a run)" % total)
    print("\nBy type")
    for k, n in types.most_common(15):
        print("  %s  %s" % (pct(n, total), k))
    print("\nBy the nearest r7 or Helidon frame")
    for k, n in sites.most_common(25):
        print("  %s  %s" % (pct(n, total), k[:160]))


def syscalls(path, requests):
    requests = int(requests)
    print("Requests during the trace: %d" % requests)
    print("  %10s %8s  %s" % ("calls", "per req", "syscall"))
    for line in open(path):
        m = re.match(r"\s*[\d.]+\s+[\d.]+\s+\d+\s+(\d+)\s+(?:\d+\s+)?(\w+)\s*$", line)
        if m and m.group(2) != "total":
            calls = int(m.group(1))
            if calls * 100 >= requests:   # one in a hundred requests or more
                print("  %10d %8.2f  %s" % (calls, calls / requests, m.group(2)))


def jit(log, codelist):
    failures = collections.Counter()
    for line in open(log):
        if "failed to inline" in line or "too big" in line or "too large" in line:
            if "ethlo" in line or "helidon" in line:
                failures[re.sub(r"^[^@]*@ \d+\s+", "", line.strip())[:200]] += 1
    print("Inlining failures on r7 and Helidon methods (count of compilations reporting them)")
    print("  'hot method too big' is the one that matters on a hot path; 'callee is too large'")
    print("  is a cold call site, 'klass not linked yet' an exception path never taken.")
    hot = [(k, n) for k, n in failures.items() if "hot method too big" in k]
    for k, n in sorted(hot, key=lambda x: -x[1]) + [x for x in failures.most_common(30) if x not in hot]:
        print("  %4d  %s" % (n, k))
    sizes = []
    for line in open(codelist):
        m = re.match(r"\d+\s+(\d+)\s+\d+\s+(\S+)\s+\[(0x[0-9a-f]+), 0x[0-9a-f]+ - (0x[0-9a-f]+)\]", line.strip())
        if m and m.group(1) == "4":
            sizes.append((int(m.group(4), 16) - int(m.group(3), 16), m.group(2)))
    print("\nC2 methods: %d, %d KB of code" % (len(sizes), sum(s for s, _ in sizes) // 1024))
    print("Largest r7 C2 methods (bytes of machine code, inlined callees included)")
    for size, name in sorted((s for s in sizes if "ethlo" in s[1]), reverse=True)[:20]:
        print("  %7d  %s" % (size, name[:140]))


if __name__ == "__main__":
    commands = {"cpu": cpu, "alloc": alloc, "syscalls": syscalls, "jit": jit}
    if len(sys.argv) < 3 or sys.argv[1] not in commands:
        sys.exit(__doc__)
    commands[sys.argv[1]](*sys.argv[2:])
