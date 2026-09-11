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
out="$here/results/$date-$target.tsv"
"$root/gradlew" -p "$here" "${target}Test" --console=plain \
    | sed -n 's/^[[:space:]]*PARITY\t//p' \
    | LC_ALL=C sort > "$out"
echo "$(wc -l < "$out" | tr -d ' ') rows -> $out"
