#!/usr/bin/env bash
# Runs the probe on one target and writes a clean transcript.
#
#   ./run.sh jvm            -> results/<date>-jvm.tsv
#   ./run.sh macosArm64     -> results/<date>-macosArm64.tsv
#   ./run.sh linuxX64       -> only on Linux; a native test binary is not cross-run
#
# Invoked through sborka's wrapper (`-p`) rather than a wrapper of its own: a second copy of the
# wrapper in the same repository is a second version to keep in step.
set -euo pipefail
target="${1:?target: jvm | macosArm64 | linuxX64}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$here/../../.."
date="${DATE:-$(date +%F)}"
mkdir -p "$here/results"
out="$here/results/$date-$target${SUFFIX:-}.tsv"
log="$(mktemp)"
"$root/gradlew" -p "$here" "${target}Test" --console=plain ${GRADLE_ARGS:-} > "$log"

# The header records what the rows cannot: which runtime and which host produced them. For the JVM
# that means the JDK, and for one row — `\b` before a non-ASCII letter, changed in JDK 19 — the JDK
# is the variable rather than Kotlin. `compare.py` skips these lines.
{
    echo "# probe    $date"
    echo "# target   $target"
    echo "# kotlin   2.4.10; kotlinx-serialization-json 1.11.0; kotlinx-datetime 0.8.0"
    sed -n 's/^[[:space:]]*PARITYENV\t/# /p' "$log" | LC_ALL=C sort -u
    sed -n 's/^[[:space:]]*PARITY\t//p' "$log" | LC_ALL=C sort
} > "$out"
rm -f "$log"
echo "$(grep -vc '^#' "$out" | tr -d ' ') rows -> $out"
