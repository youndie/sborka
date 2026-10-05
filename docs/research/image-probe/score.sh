#!/usr/bin/env bash
# The load check against the corpus, both halves measured in the same run. LINUX, with Docker — the
# images are the ones corpus.sh just built, and its build/corpus.tsv is what `docker run` said.
#
#   ./corpus.sh > /dev/null && ./score.sh | tee results/<date>-score.txt
#
# The check is `:image` from sborka's build-logic (B-37) — the code a build runs, not a copy of it.
#
# Every row is checked twice where it can be:
#   image  the whole image as built (`docker save`), base and added layers together
#   base   the binary alone, put on the base pulled from its registry by digest — the way a build
#          asks before it pushes (only the rows whose image IS base + one binary)
#
# A row passes when BOTH hold:
#   docker  this run's `docker run` did what the row says: `starts` (exit 0, or r1's server answering
#           /health and exiting 0 on SIGTERM), or `fails:<text>` (non-zero, <text> in its first line)
#   check   the check's verdict is the wanted one
# r7b is the declared blind spot: Docker fails at run time on a dlopen, the check says `loads`.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"
GRADLE="${GRADLE:-$here/../../../gradlew}"
command -v docker > /dev/null || { echo "ABORT: docker missing" >&2; exit 2; }
TSV="$here/build/corpus.tsv"
[ -s "$TSV" ] || { echo "ABORT: $TSV is empty — run corpus.sh first" >&2; exit 2; }

"$GRADLE" -p "$here/../../../build-logic" :image:installDist --console=plain -q > /dev/null 2>&1 ||
    { echo "ABORT: :image did not build" >&2; exit 1; }
LC="$here/../../../build-logic/image/build/install/load-check/bin/load-check"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
base() { awk -v t="$1" '$1 == t { print $2 }' bases.lock; }

# What `docker run` did in this run, against what the row says it does.
docker_agrees() { # docker_agrees <id> <starts|fails:text>
    local id="$1" want="$2" rc first served
    rc="$(awk -F'\t' -v i="$id" '$1 == i && $2 != "serve" { print $2 }' "$TSV")"
    first="$(awk -F'\t' -v i="$id" '$1 == i && $2 != "serve" { print $3 }' "$TSV")"
    served="$(awk -F'\t' -v i="$id" '$1 == i && $2 == "serve" { print $3 }' "$TSV")"
    [ -n "$rc" ] || { echo "not run"; return 1; }
    case "$want" in
        starts)
            if [ "$rc" = 0 ] || { [ "$rc" = 124 ] && [ "$served" = "200 0" ]; }; then echo "starts"; return 0; fi
            echo "exit $rc: $first"; return 1 ;;
        fails:*)
            if [ "$rc" != 0 ] && [ "$rc" != 124 ] && [[ "$first" == *"${want#fails:}"* ]]; then echo "fails: ${want#fails:}"; return 0; fi
            echo "exit $rc: $first"; return 1 ;;
    esac
}

# row | what Docker must do | what the check must say | path in the image | base for base-mode
rows="
r1-keel-cc13|starts|loads|/app/keel|$(base gcr.io/distroless/cc-debian13)
r2-keel-base13|fails:libgcc_s.so.1|missing-library libgcc_s.so.1 needed by /app/keel|/app/keel|$(base gcr.io/distroless/base-debian13)
r3-curl-cc12|fails:libz.so.1|missing-library libz.so.1 needed by /app/curl|/app/curl|$(base gcr.io/distroless/cc-debian12)
r4-default-cc13|fails:libcrypt.so.1|missing-library libcrypt.so.1 needed by /app/probe|/app/probe|$(base gcr.io/distroless/cc-debian13)
r5a-copied-libcrypt-2604|fails:GLIBC_2.38|missing-version GLIBC_2.38 from libc.so.6 needed by /usr/lib/x86_64-linux-gnu/libcrypt.so.1|/app/probe|-
r5b-copied-libcrypt-2404|fails:GLIBC_2.38|missing-version GLIBC_2.38 from libc.so.6 needed by /usr/lib/x86_64-linux-gnu/libcrypt.so.1|/app/probe|-
r6-keel-scratch|fails:no such file|missing-interpreter /lib64/ld-linux-x86-64.so.2|/app/keel|scratch
r7a-blind-scratch-loads|starts|loads|/app/probe|-
r7b-blind-scratch-iconv|fails:iconv|loads|/app/probe|-
r7c-control-cc13-iconv|starts|loads|/app/probe|-
r8a-cache-places-it|starts|loads|/app/probe|-
r8b-cache-is-stale|fails:libcrypt.so.1|missing-library libcrypt.so.1 needed by /app/probe|/app/probe|-
r9a-whiteout-removes-it|fails:libgcc_s.so.1|missing-library libgcc_s.so.1 needed by /app/probe|/app/probe|-
r9b-no-whiteout|starts|loads|/app/probe|-
r10a-runpath-finds-it|starts|loads|/app/probe|-
r10b-no-runpath|fails:libcrypt.so.1|missing-library libcrypt.so.1 needed by /app/probe|/app/probe|-
r11a-ld-library-path|starts|loads|/app/probe|-
r11b-no-ld-library-path|fails:libcrypt.so.1|missing-library libcrypt.so.1 needed by /app/probe|/app/probe|-
"
agree=0; total=0
printf '%-26s %-6s %-28s %s\n' row mode docker check
while IFS='|' read -r id dockerWant want at base; do
    [ -n "$id" ] || continue
    docker image inspect "image-probe/$id" > /dev/null 2>&1 || { echo "ABORT: image-probe/$id missing — run corpus.sh" >&2; exit 1; }
    dockerSaid="$(docker_agrees "$id" "$dockerWant")"; dockerOk=$?
    docker save "image-probe/$id" -o "$work/$id.tar"
    for mode in image base; do
        if [ "$mode" = image ]; then
            out="$("$LC" --image-tar "$work/$id.tar" --at "$at" 2>&1)"; rc=$?
        else
            [ "$base" = - ] && continue
            cid="$(docker create "image-probe/$id")"; docker cp "$cid:$at" "$work/bin" > /dev/null; docker rm "$cid" > /dev/null
            out="$("$LC" --binary "$work/bin" --at "$at" --base "$base" 2>&1)"; rc=$?
        fi
        got="$(printf '%s\n' "$out" | sed -n 's/^VERDICT //p')"
        [ -n "$got" ] || got="(no verdict, exit $rc: $(printf '%s\n' "$out" | tail -1))"
        total=$((total + 1))
        if [ "$dockerOk" = 0 ] && [ "$got" = "$want" ]; then
            mark=agree; agree=$((agree + 1))
        elif [ "$dockerOk" != 0 ]; then
            mark="DISAGREE: docker did not do what the row says (wanted $dockerWant)"
        else
            mark="DISAGREE (wanted: $want)"
        fi
        printf '%-26s %-6s %-28s %s — %s\n' "$id" "$mode" "$dockerSaid" "$got" "$mark"
        printf '%s\n' "$out" | sed 's/^/        | /' >> "$work/detail.txt"
    done
done <<< "$rows"
printf '\n%d of %d verdicts as wanted. r7b "agrees" by saying loads while Docker fails: that is the limit, measured.\n' "$agree" "$total"
printf '\n===== detail\n'; cat "$work/detail.txt"
[ "$agree" = "$total" ]
