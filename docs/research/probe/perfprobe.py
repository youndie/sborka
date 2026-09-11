#!/usr/bin/env python3
"""A probe, not the lint: how often would each of the three measured rules fire?

Reads `javap -c -p` over compiled class files and counts, per method body:
  * a pattern built outside `<clinit>`  — `new kotlin/text/Regex` or `Pattern.compile`
  * >= 2 eager collection operators     — an intermediate collection per link of the chain
  * a body over C2's FreqInlineSize     — the last instruction offset is used as the size,
                                          which understates a body by the length of its last
                                          instruction (1-5 bytes) and never overstates it.
Known misses: it cannot see a hot path, and `javap` prints what the compiler emitted, so
inline functions are counted in the caller (which is the point) and lambdas as separate classes.
"""
import re, subprocess, sys, os
from collections import Counter

# A CHAIN IS NOT VISIBLE AS `CollectionsKt.filter` — `filter`, `map`, `groupBy` and friends are
# `inline`, so what the compiler emits is the materialisation itself: a fresh ArrayList (or
# LinkedHashMap/Set) plus a loop. Non-inline eager operators (`sortedWith`, `toList`, `distinct`,
# `take`, `plus`) stay as calls. Counting materialisations per body therefore counts links of a
# chain — and, being flow-blind, counts two unrelated lists in one method as well: an upper bound.
MATERIALISE_NEW = re.compile(r"// class java/util/(ArrayList|LinkedHashMap|LinkedHashSet|HashMap|HashSet|TreeMap)\b")
# STRINGS COUNT, and that is not a widening for symmetry: on konekt the largest user-code
# allocation owner in the whole profile (1.08 % of bytes) is `MoneyFormat.group`, whose body is
# `reversed().chunked(3).joinToString(sep).reversed()` - four intermediates, none of them a
# collection operator. A definition watching only `CollectionsKt` named none of the methods the
# profile charges.
EAGER_CALL = re.compile(
    r"// Method kotlin/(?:collections/(?:CollectionsKt|ArraysKt|MapsKt|SetsKt)|text/StringsKt)"
    r"\.(sortedWith|sorted|sortedArray|toList|toMutableList|toSet|toMutableSet|distinct|reversed"
    r"|take|takeLast|drop|dropLast|chunked|windowed|zip|flatten|plus|minus|toTypedArray"
    r"|joinToString|split|lines|toCharArray|padStart|padEnd|repeat|replace|substringAfter"
    r"|substringBefore|substringAfterLast|substringBeforeLast|trim|removePrefix|removeSuffix)"
    r"(?:\$default)?:")
LAZY_CALL = re.compile(r"// Method kotlin/(?:sequences/SequencesKt|collections/CollectionsKt\.asSequence)")
REGEX_RE = re.compile(r"// class kotlin/text/Regex|// Method java/util/regex/Pattern\.compile:")
SIG_RE = re.compile(r"^  [^ ].*[;{]\s*$")
# AN OFFSET LINE HAS AN OPCODE AFTER THE COLON. The keys of a `tableswitch`/`lookupswitch` table
# are printed in the same shape — `        1234567: 79` — and reading them as offsets gave bodies of
# two billion bytes on the first run: a `when` over strings switches on `hashCode`.
OFF_RE = re.compile(r"^\s+(\d+): [a-z]")
THRESHOLD = 325

def walk(classfiles):
    findings = {"pattern": [], "chain": [], "size": []}
    stats = Counter()
    for batch_start in range(0, len(classfiles), 200):
        batch = classfiles[batch_start:batch_start + 200]
        out = subprocess.run(["javap", "-c", "-p"] + batch, capture_output=True, text=True).stdout
        cls = method = None
        size = 0; eager = 0; pattern = 0; lazy = 0
        def flush():
            if method is None: return
            stats["methods"] += 1
            if size > THRESHOLD: findings["size"].append((cls, method, size))
            if pattern and "static {}" not in method: findings["pattern"].append((cls, method, pattern))
            if eager >= 2: findings["chain"].append((cls, method, eager, f"lazy={lazy}"))
        for line in out.splitlines():
            if line.startswith("Compiled from") or not line.strip():
                continue
            if not line.startswith(" "):
                flush(); method = None
                m = re.search(r"(?:class|interface) ([\w.$]+)", line)
                if m: cls = m.group(1); stats["classes"] += 1
                continue
            if SIG_RE.match(line):
                flush()
                method = line.strip().rstrip("{").strip()
                size = 0; eager = 0; pattern = 0; lazy = 0
                continue
            m = OFF_RE.match(line)
            if m: size = max(size, int(m.group(1)))
            if REGEX_RE.search(line): pattern += 1
            if MATERIALISE_NEW.search(line) and " new " in line: eager += 1
            if EAGER_CALL.search(line): eager += 1
            if LAZY_CALL.search(line): lazy += 1
        flush()
    return findings, stats

def collect(root):
    """Class files under `root`, tests excluded, one per class name.

    A KMP build writes the same class into more than one output directory (`classes/kotlin/jvmMain`
    beside `classes/kotlin/main`), and counting both reports every finding twice.
    """
    out = []
    for dirpath, _, files in os.walk(root):
        if "/test/" in dirpath or "/build/classes/java/test" in dirpath: continue
        out += [os.path.join(dirpath, f) for f in files if f.endswith(".class")]
    seen, unique = set(), []
    for path in sorted(out):
        key = path.split("/classes/", 1)[-1].split("/", 2)[-1]
        if key in seen: continue
        seen.add(key); unique.append(path)
    return unique

if __name__ == "__main__":
    verbose = "-v" in sys.argv
    for root in [a for a in sys.argv[1:] if a != "-v"]:
        files = collect(root)
        if not files:
            print(f"{root}: no class files"); continue
        f, s = walk(files)
        print(f"{root}: {s['classes']} classes, {s['methods']} methods -> "
              f"pattern {len(f['pattern'])}, chain>=2 {len(f['chain'])}, >{THRESHOLD}b {len(f['size'])}")
        if verbose:
            for c, m, n in sorted(f["pattern"])[:12]: print(f"    pattern  {c}.{m}  x{n}")
            for c, m, n, k in sorted(f["chain"], key=lambda x: -x[2])[:12]: print(f"    chain    {c}.{m}  {n} ({k})")
            for c, m, n in sorted(f["size"], key=lambda x: -x[2])[:12]: print(f"    size     {c}.{m}  {n}b")

def classify(cls, method):
    if "$$serializer" in cls or "$serializer" in cls: return "generated serializer"
    if "invokeSuspend" in method: return "suspend state machine"
    if "ComposableSingletons" in cls or "$Composable" in cls: return "compose"
    if "$" in cls.split(".")[-1]: return "lambda / inner"
    if method.strip().startswith("static {}") or "<clinit>" in method: return "static init"
    return "written by hand"
