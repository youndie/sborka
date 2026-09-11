#!/usr/bin/env python3
"""The A/B of a perf-lint finding: what the fix did to allocated bytes, by owner.

    ab-report.py <directory of ab-images.sh output> [--rate 200] [--measure 120]
                 [--prefix io.konekt.] [--movers 12]

Reads the `<variant>.<rep>.alloc.collapsed` files that konekt's `scripts/measure/ab-images.sh`
leaves behind and prints, per run and then as medians: sampled bytes, bytes per request and the
share user code owns — and then **which owners the bytes moved between**.

BY OWNER AND NOT BY A LIST OF NAMES, and this version exists because the first one was keyed on
names. It counted the share owned by the three methods the rule had listed, variant B renamed the
method it fixed, and the column read 1.30 % → 0.00 % — a total win. The work had moved into a new
owner charged 0.38 %: the real reduction was 71 %, not all of it. A metric keyed on the subject's
name goes to zero when the subject is renamed, and a rewrite is exactly the moment a name changes.

So the report asks the profile who owns the bytes in each variant and shows the difference, without
being told what to look for. An owner that appears in only one variant is called out by name: that
is what a rename looks like from here, and it is also what a genuinely new allocation site looks
like — the two are indistinguishable in a profile and the reader is told so rather than guessed at.

BYTES PER REQUEST, NOT THROUGHPUT, because the generator holds a constant arrival rate: requests
are an input and what varies is what each one costs. The denominator is therefore the rate times
the profiling window, and the k6 log beside each profile is what says the rate was actually held —
a run with dropped iterations is not comparable and is named rather than averaged in.

Sampled bytes are comparable between variants and are not an absolute; async-profiler's `--total`
is an estimate from samples, and the same caveat sits on every allocation figure in this research.
"""
import argparse
import collections
import os
import re
import statistics

JDK = ("java.", "jdk.", "sun.", "javax.", "com.sun.")

# How much of the lost bytes may reappear elsewhere before the report says "look for a rename".
# Half, because a genuine saving moves some traffic around too — the fix that prompted this replaced
# four intermediates with one StringBuilder, and that StringBuilder is an owner of its own.
RENAME_SHARE = 0.5


def owners(path, prefix):
    """Total bytes in this profile, and the share each owner under `prefix` holds.

    *Owner* is the first frame from the leaf that is neither JDK nor JVM — whose code asked for the
    allocation. It is the only view that bounds what a change to that code could do; the leaf says
    what was allocated, which is the same `byte[]` whoever asked for it.
    """
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


def median_shares(runs):
    """Each owner's median share across a variant's runs.

    The median of the shares rather than the share of the totals: one run of three carrying twice
    the traffic would otherwise weigh twice, and these runs are meant to be repetitions of one
    measurement. An owner missing from a run counts as zero there, because it is zero — the code
    ran and allocated nothing under that name.
    """
    everyone = set().union(*(set(shares) for shares in runs)) if runs else set()
    return {owner: statistics.median(shares.get(owner, 0.0) for shares in runs) for owner in everyone}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("directory")
    ap.add_argument("--rate", type=int, default=200)
    ap.add_argument("--measure", type=int, default=120)
    ap.add_argument("--prefix", default="io.konekt.")
    ap.add_argument("--movers", type=int, default=12, help="how many owners to print")
    a = ap.parse_args()

    requests = a.rate * a.measure
    per_run = collections.defaultdict(list)
    shares_by_variant = collections.defaultdict(list)

    print("| run | sampled bytes | bytes/req | user-owned | dropped |")
    print("|---|---|---|---|---|")
    for name in sorted(os.listdir(a.directory)):
        if not name.endswith(".alloc.collapsed"):
            continue
        variant, rep, _, _ = name.split(".")
        total, own = owners(os.path.join(a.directory, name), a.prefix)
        user = sum(own.values())
        per_run[variant].append((total / requests, 100 * user / total))
        shares_by_variant[variant].append({k: 100 * v / total for k, v in own.items()})
        d = dropped(os.path.join(a.directory, f"{variant}.{rep}.k6.log"))
        print(f"| {variant} rep{rep} | {total:,} | {total / requests / 1024:.2f} KiB | "
              f"{100 * user / total:.2f} % | {d} |")

    print()
    print("| variant | bytes/req median | user-owned median |")
    print("|---|---|---|")
    medians = {}
    for variant in sorted(per_run):
        per_req = statistics.median(r[0] for r in per_run[variant])
        user = statistics.median(r[1] for r in per_run[variant])
        medians[variant] = per_req
        runs = " ".join(f"{r[0] / 1024:.2f}" for r in sorted(per_run[variant]))
        print(f"| {variant} | {per_req / 1024:.2f} KiB ({runs}) | {user:.2f} % |")

    if "A" not in per_run or "B" not in per_run:
        return

    spread_a = [r[0] for r in per_run["A"]]
    # THE RULER: how much the same variant moves between its own repetitions. A difference between
    # variants smaller than this is not a difference this stand can see.
    noise = 100 * (max(spread_a) - min(spread_a)) / statistics.median(spread_a)
    print()
    print(f"B/A bytes per request: {medians['B'] / medians['A']:.4f}  "
          f"({100 * (medians['B'] - medians['A']) / medians['A']:+.2f} %), "
          f"spread within A alone: {noise:.2f} %")

    before = median_shares(shares_by_variant["A"])
    after = median_shares(shares_by_variant["B"])
    movers = sorted(
        (set(before) | set(after)),
        key=lambda owner: abs(after.get(owner, 0.0) - before.get(owner, 0.0)),
        reverse=True,
    )[: a.movers]

    print()
    print("Where the bytes moved — median share of ALL allocated bytes, by owner:")
    print()
    print("| owner | A | B | change | |")
    print("|---|---|---|---|---|")
    for owner in movers:
        was, now = before.get(owner, 0.0), after.get(owner, 0.0)
        # AN OWNER ON ONE SIDE ONLY is the shape a rename makes — and the shape a new allocation
        # site makes. A profile cannot tell those apart, so the report says which it is looking at
        # and leaves the reading to somebody who knows what the change did.
        only = "only in B" if was == 0 else ("only in A" if now == 0 else "")
        print(f"| `{owner}` | {was:.3f} % | {now:.3f} % | {now - was:+.3f} pp | {only} |")

    gone = sum(max(before.get(o, 0.0) - after.get(o, 0.0), 0.0) for o in set(before) | set(after))
    arrived = sum(max(after.get(o, 0.0) - before.get(o, 0.0), 0.0) for o in set(before) | set(after))
    print()
    print(f"owners that lost bytes: {gone:.3f} pp; owners that gained: {arrived:.3f} pp; "
          f"net {arrived - gone:+.3f} pp of all allocated bytes")
    # THE WARNING THIS TOOL EXISTS TO PRINT. When most of what one owner lost turns up under
    # another, the likeliest explanation is that the code was renamed or moved rather than made
    # cheaper — which is exactly how the first reading of this A/B went wrong.
    if gone > 0 and arrived > RENAME_SHARE * gone:
        print(f"WARNING: {100 * arrived / gone:.0f} % of what was lost turned up under other owners. "
              "Read the movers above as a pair before reading the net as a saving: a rewrite renames "
              "things, and a metric keyed on the old name reads as a total win.")


if __name__ == "__main__":
    main()
