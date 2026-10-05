#!/usr/bin/env bash
# measure.sh <repo dir> <module path> <iterations> <out dir>  (sborka#121)
# Warm once, then N iterations alternating the arm order. Each arm is one Gradle invocation that
# reruns exactly two tasks - the test link and the test run - with the build cache off.
set -uo pipefail
DIR=$1 MOD=$2 N=$3 OUT=$4
INIT=$(cd "$(dirname "$0")" && pwd)/release-run.init.gradle
mkdir -p "$OUT"
cd "$DIR" || exit 3
G=(./gradlew --no-daemon --console=plain --init-script "$INIT" -Pm121.module="$MOD" --no-build-cache --continue)
echo "=== $(date -Is) warm-up $MOD on $(hostname), $(nproc) cpus"
M121_TIMES="$OUT/warmup.tsv" "${G[@]}" "$MOD:linkDebugTestLinuxX64" "$MOD:linkReleaseTestLinuxX64" > "$OUT/warmup.log" 2>&1
echo "warm-up exit $?"
arm() { # arm <debug|release> <iteration>
    local a=$1 i=$2 link test
    if [ "$a" = debug ]; then link=linkDebugTestLinuxX64; test=linuxX64Test; else link=linkReleaseTestLinuxX64; test=linuxX64ReleaseTest; fi
    M121_TIMES="$OUT/$a-$i.tsv" "${G[@]}" "$MOD:$link" --rerun "$MOD:$test" --rerun > "$OUT/$a-$i.log" 2>&1
    local rc=$?
    echo "$(date -Is) $a #$i exit $rc :: $(grep -E "$link|$test" "$OUT/$a-$i.tsv" | awk -F'\t' '{printf "%s %.1fs %s; ", $1, $2/1000, $3}')"
    grep -E 'tests completed|FAILED$' "$OUT/$a-$i.log" | head -5
}
for i in $(seq 1 "$N"); do
    if [ $((i % 2)) -eq 1 ]; then arm debug "$i"; arm release "$i"; else arm release "$i"; arm debug "$i"; fi
done
echo "=== $(date -Is) done"
