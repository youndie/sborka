#!/usr/bin/env bash
# B-29, one Jib arm on a keel checkout, on a host with NO docker on PATH — the arm's claim is that no
# daemon is needed, so the PATH is built to make a hidden `docker` call fail rather than succeed.
#
#   arm.sh <keel checkout> server|image
#
#   server       Jib applied to keel's KMP `:server`, as the brief first asked
#   server-main  the same, plus `sourceSets.maybeCreate("main")` — the one thing Jib asks for
#   image        Jib applied to a new empty `:image` module with `java` applied only for Jib
#
# Jib 3.5.4 needs `--no-configuration-cache` (pass it after the arm); keel turns the cache on.
#
# Leaves the tarball at <module>/build/jib-image.tar and prints what the build said. Mutates the
# checkout it is given: run it on a throwaway clone.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
keel="$1"; arm="$2"
block="$(sed 's#NATIVE_IMAGE_DIR#rootProject.file("server/build/native-image")#' "$here/jib-block.gradle.kts")"

case "$arm" in
server|server-main)
    # the plugin goes into the existing plugins block, the configuration at the end
    # perl, not sed: BSD sed does not read \n in a replacement, and this runs on a Mac.
    perl -pi -e 's#^    alias\(libs\.plugins\.razves\)$#    alias(libs.plugins.razves)\n    id("com.google.cloud.tools.jib") version "3.5.4"#' "$keel/server/build.gradle.kts"
    printf '\n%s\ntasks.matching { it.name.startsWith("jib") }.configureEach { dependsOn("stageNativeImage") }\n' "$block" >> "$keel/server/build.gradle.kts"
    [ "$arm" = server-main ] && printf '\nsourceSets.maybeCreate("main")\n' >> "$keel/server/build.gradle.kts"
    module=server
    ;;
image)
    mkdir -p "$keel/image"
    printf 'plugins {\n    java\n    id("com.google.cloud.tools.jib") version "3.5.4"\n}\n\n%s\ntasks.matching { it.name.startsWith("jib") }.configureEach { dependsOn(":server:stageNativeImage") }\n' "$block" > "$keel/image/build.gradle.kts"
    grep -q 'include(":image")' "$keel/settings.gradle.kts" || printf '\ninclude(":image")\n' >> "$keel/settings.gradle.kts"
    module=image
    ;;
*) echo "arm: server, server-main or image" >&2; exit 2 ;;
esac

# No docker anywhere on PATH: keep only the directories that do not hold it.
clean_path=""
IFS=: read -ra dirs <<< "$PATH"
for d in "${dirs[@]}"; do [ -x "$d/docker" ] || clean_path="${clean_path:+$clean_path:}$d"; done
if PATH="$clean_path" command -v docker > /dev/null; then echo "ABORT: docker still on PATH" >&2; exit 2; fi

cd "$keel"
start=$(date +%s)
PATH="$clean_path" ./gradlew ":$module:jibBuildTar" --console=plain "${@:3}" > "$keel/jib-$arm.log" 2>&1
rc=$?
echo "arm=$arm rc=$rc seconds=$(( $(date +%s) - start ))"
grep -E '^(> Task|FAILURE|\* What went wrong|BUILD|Built image tarball|Container entrypoint)|went wrong|Exception|Cannot|Configuration cache' -A2 "$keel/jib-$arm.log" | head -40
