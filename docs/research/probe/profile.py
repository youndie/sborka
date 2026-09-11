#!/usr/bin/env python3
"""What a profile charges user code, and whether the rules name the same methods.

    profile.py shares  <collapsed>                  what the three rule shapes own, by definition
    profile.py owners  <collapsed> <prefix>         allocation by owner, largest first
    profile.py join    <collapsed> <prefix> <repo>  the owners each rule names, and the ones it misses

The collapsed files are async-profiler output; the ones cited by the research live in
`youndie/zavarnik` under `bench/profile/results/`. `prefix` is the user package (`bench.`,
`io.konekt.`), `repo` a directory of compiled classes for `perfprobe.py` to read.

TWO VIEWS OF ONE SAMPLE, and the second is the one that decides anything. *Self* is the leaf — for
an allocation profile, the type allocated. *Owner* is the first frame from the leaf that is neither
JDK nor JVM: whose code asked for it. A rule rewrites somebody's code, so what bounds it is the
owner column, never the leaf.
"""
import collections
import importlib.util
import os
import sys

JDK = ("java.", "jdk.", "sun.", "javax.", "com.sun.")


def norm(frame):
    # async-profiler writes Java frames with slashes and allocation leaves with dots; the `_[i]`,
    # `_[j]`, `_[k]` suffixes say how the sample was taken and are not part of the name.
    return frame.split("_[")[0].replace("/", ".")


def stacks(path):
    for line in open(path):
        line = line.rstrip("\n")
        if not line:
            continue
        stack, _, count = line.rpartition(" ")
        yield [norm(f) for f in stack.split(";")], int(count)


def owner_of(frames, prefix):
    for f in reversed(frames):
        if f.startswith(JDK):
            continue
        if "." not in f or not f[0].isalpha() or not f[0].islower():
            continue
        return f
    return None


def shares(path, prefix="bench."):
    total = 0
    b = collections.Counter()
    for frames, n in stacks(path):
        total += n
        user = any(f.startswith(prefix) for f in frames)
        if not user:
            continue
        b["user anywhere"] += n
        if any(f.startswith("java.util.regex.") for f in frames):
            b["a pattern is on the stack"] += n
        if any(f.startswith("kotlin.collections.CollectionsKt") for f in frames):
            b["a collection operator is on the stack"] += n
    print(f"{path}: {total} bytes sampled")
    for k, v in b.most_common():
        print(f"  {k:38s} {100 * v / total:6.2f} %")


def owners(path, prefix):
    total = 0
    own = collections.Counter()
    for frames, n in stacks(path):
        total += n
        o = owner_of(frames, prefix)
        if o and o.startswith(prefix):
            own[o] += n
    user = sum(own.values())
    print(f"{path}: user code owns {100 * user / total:.2f} % of {total} bytes sampled")
    for k, v in own.most_common(15):
        print(f"  {100 * v / total:6.3f} %  {k}")
    return total, own


def join(path, prefix, repo):
    spec = importlib.util.spec_from_file_location(
        "perfprobe", os.path.join(os.path.dirname(os.path.abspath(__file__)), "perfprobe.py"))
    probe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(probe)
    found, _ = probe.walk(probe.collect(repo))

    def name(cls, method):
        return f"{cls}.{method.split('(')[0].split()[-1]}"

    named = {kind: {name(c, m) for c, m, *_ in found[kind]} for kind in ("pattern", "chain", "size")}
    total, own = owners(path, prefix)
    user = sum(own.values())
    for kind, names in named.items():
        # A lambda allocates under the method that declared it, and the profile names the lambda
        # class; the rule names the method. `$`-stripping is what joins the two.
        b = sum(v for k, v in own.items() if k in names or k.split("$")[0] in names)
        print(f"  the {kind:7s} rule names methods owning {100 * b / total:5.2f} % of all bytes, "
              f"{100 * b / user:5.1f} % of what user code owns")
    unnamed = [(v, k) for k, v in own.items()
               if k not in set().union(*named.values())
               and k.split("$")[0] not in set().union(*named.values())]
    print("  the largest owners no rule names:")
    for v, k in sorted(unnamed, reverse=True)[:5]:
        print(f"      {100 * v / total:6.3f} %  {k}")


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "shares":
        shares(*args)
    elif cmd == "owners":
        owners(*args)
    elif cmd == "join":
        join(*args)
    else:
        sys.exit(__doc__)
