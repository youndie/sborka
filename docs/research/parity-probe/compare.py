#!/usr/bin/env python3
"""Diffs two probe transcripts and prints the rows that differ.

Neither side is the reference. The output names both values and nothing else: deciding which one is
wrong is the reader's job, and a tool that called the JVM "expected" would have decided it silently.

    ./compare.py results/2026-09-11-jvm.tsv results/2026-09-11-macosArm64.tsv
    ./compare.py --same ...   # the rows that agree, which is the half worth trusting
"""
import argparse
import sys


def read(path):
    rows = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            # `#` lines are the header: which runtime, which host, which versions. They are recorded
            # precisely because the two sides differ there, so comparing them would report the setup
            # as a finding.
            if not line or line.startswith("#"):
                continue
            cls, name, value = line.split("\t", 2)
            rows[(cls, name)] = value
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("left")
    ap.add_argument("right")
    ap.add_argument("--same", action="store_true", help="print the agreeing rows instead")
    args = ap.parse_args()

    left, right = read(args.left), read(args.right)
    only_left = sorted(left.keys() - right.keys())
    only_right = sorted(right.keys() - left.keys())
    shared = sorted(left.keys() & right.keys())

    # A probe missing from one side is a finding in its own right and a louder one than a differing
    # value: it means the source did not even compile the same way on both targets.
    for key in only_left:
        print(f"MISSING-RIGHT\t{key[0]}\t{key[1]}")
    for key in only_right:
        print(f"MISSING-LEFT\t{key[0]}\t{key[1]}")

    differing = [k for k in shared if left[k] != right[k]]
    if args.same:
        for k in shared:
            if left[k] == right[k]:
                print(f"{k[0]}\t{k[1]}\t{left[k]}")
    else:
        for k in differing:
            print(f"{k[0]}\t{k[1]}")
            print(f"  left  {left[k]}")
            print(f"  right {right[k]}")

    print(
        f"\n{len(shared)} rows compared, {len(differing)} differ, "
        f"{len(only_left) + len(only_right)} present on one side only",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
