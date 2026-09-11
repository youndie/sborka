#!/usr/bin/env python3
"""The A/B of a perf-lint finding: what the fix did to allocated bytes per request.

    ab-report.py <directory of ab-images.sh output> [--rate 200] [--measure 120] [--prefix io.konekt.]

Reads the `<variant>.<rep>.alloc.collapsed` files that konekt's `scripts/measure/ab-images.sh`
leaves behind and prints, per run and then as medians: sampled bytes, bytes per request, the share
user code owns, and the share owned by the methods the rule named.

BYTES PER REQUEST, NOT THROUGHPUT, because the generator holds a constant arrival rate: requests
are an input and what varies is what each one costs. The denominator is therefore the rate times
the profiling window, and the k6 log beside each profile is what says the rate was actually held —
a run with dropped iterations is not comparable and is named rather than averaged in.

Sampled bytes are comparable between variants and are not an absolute; async-profiler's `--total`
is an estimate from samples, and the same caveat sits on every allocation figure in this research.
"""
import argparse
import collections
import re
import statistics
import sys
import os

JDK = ("java.", "jdk.", "sun.", "javax.", "com.sun.")
# The methods the chain rule named on this service, from research-perf-lint §1.4.
NAMED = ("MoneyFormat.group", "UsageUnits.grouped", "UsageCounterCards.captionFor")


def owners(path, prefix):
    total = 0
    own = collections.Counter()
    for line in open(path):
        line = line.rstrip("\n")
        if not line:
            continue
        stack, _, count = line.rpartition(" ")
        n = int(count)
        total += n
        frames = [f.split("_[")[0].replace("/", ".") for f in stack.split(";")]
        for f in reversed(frames):
            if f.startswith(JDK):
                continue
            if "." not in f or not f[0].isalpha() or not f[0].islower():
                continue
            if f.startswith(prefix):
                own[f] += n
            break
    return total, own


def dropped(log):
    """Whether the generator held its rate. A run that did not is not a measurement of the server."""
    try:
        text = open(log).read()
    except OSError:
        return None
    m = re.search(r"dropped_iterations[.\s]*:\s*(\d+)", text)
    return int(m.group(1)) if m else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("directory")
    ap.add_argument("--rate", type=int, default=200)
    ap.add_argument("--measure", type=int, default=120)
    ap.add_argument("--prefix", default="io.konekt.")
    a = ap.parse_args()

    requests = a.rate * a.measure
    rows = collections.defaultdict(list)
    print(f"| run | sampled bytes | bytes/req | user-owned | named by the rule | dropped |")
    print(f"|---|---|---|---|---|---|")
    for name in sorted(os.listdir(a.directory)):
        if not name.endswith(".alloc.collapsed"):
            continue
        variant, rep, _, _ = name.split(".")
        path = os.path.join(a.directory, name)
        total, own = owners(path, a.prefix)
        user = sum(own.values())
        named = sum(v for k, v in own.items()
                    if any(n in k or n in k.split("$")[0] for n in NAMED))
        per_req = total / requests
        d = dropped(os.path.join(a.directory, f"{variant}.{rep}.k6.log"))
        rows[variant].append((per_req, 100 * user / total, 100 * named / total))
        print(f"| {variant} rep{rep} | {total:,} | {per_req/1024:.2f} KiB | "
              f"{100*user/total:.2f} % | {100*named/total:.3f} % | {d} |")

    print()
    print("| variant | bytes/req median | user-owned median | named median |")
    print("|---|---|---|---|")
    medians = {}
    for variant in sorted(rows):
        per_req = statistics.median(r[0] for r in rows[variant])
        user = statistics.median(r[1] for r in rows[variant])
        named = statistics.median(r[2] for r in rows[variant])
        medians[variant] = (per_req, user, named)
        runs = " ".join(f"{r[0]/1024:.2f}" for r in sorted(rows[variant]))
        print(f"| {variant} | {per_req/1024:.2f} KiB ({runs}) | {user:.2f} % | {named:.3f} % |")

    if "A" in medians and "B" in medians:
        a_req, a_user, a_named = medians["A"]
        b_req, b_user, b_named = medians["B"]
        spread_a = [r[0] for r in rows["A"]]
        # THE RULER: how much the same variant moves between its own repetitions. A difference
        # between variants smaller than this is not a difference this stand can see.
        noise = 100 * (max(spread_a) - min(spread_a)) / statistics.median(spread_a)
        print()
        print(f"B/A bytes per request: {b_req / a_req:.4f}  "
              f"({100 * (b_req - a_req) / a_req:+.2f} %), spread within A alone: {noise:.2f} %")
        print(f"user-owned share: {a_user:.2f} % -> {b_user:.2f} %")
        print(f"named by the rule: {a_named:.3f} % -> {b_named:.3f} %")


if __name__ == "__main__":
    main()
