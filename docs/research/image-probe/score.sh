#!/usr/bin/env bash
# B-30: score the load check against B-27's corpus. LINUX, with Docker — the images are the ones
# corpus.sh built, and `docker run`'s verdicts are in results/2026-09-29-corpus.txt.
#
#   ./corpus.sh first (it builds the images), then:  ./score.sh | tee results/<date>-score.txt
#
# Every row is checked twice where it can be:
#   image  the whole image as built (`docker save`), base and added layers together
#   base   the binary alone, put on the base pulled from its registry by digest — the way a build
#          would ask before it pushes (only the rows whose image IS base + one binary)
# A row agrees when the check says "loads" exactly where Docker started the container, and names
# the file Docker named where it did not. r7b is the declared blind spot: the check must say
# "loads", and Docker fails — at run time, on a dlopen no ELF entry names.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"
GRADLE="${GRADLE:-$here/../../../gradlew}"
for t in docker; do command -v "$t" > /dev/null || { echo "ABORT: $t missing" >&2; exit 2; }; done

"$GRADLE" -p "$here" :check:installDist --console=plain -q > /dev/null 2>&1 || { echo "ABORT: check did not build" >&2; exit 1; }
LC="$here/check/build/install/load-check/bin/load-check"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
base() { awk -v t="$1" '$1 == t { print $2 }' bases.lock; }

# row | what Docker did (from the corpus) | what the check must say to agree | image-mode path | base for base-mode
rows="
r1-keel-cc13|serves|loads|/app/keel|$(base gcr.io/distroless/cc-debian13)
r2-keel-base13|127 libgcc_s.so.1|missing-library libgcc_s.so.1|/app/keel|$(base gcr.io/distroless/base-debian13)
r3-curl-cc12|127 libz.so.1|missing-library libz.so.1|/app/curl|$(base gcr.io/distroless/cc-debian12)
r4-default-cc13|127 libcrypt.so.1|missing-library libcrypt.so.1|/app/probe|$(base gcr.io/distroless/cc-debian13)
r5a-copied-libcrypt-2604|1 GLIBC_2.38 by libcrypt.so.1|missing-version GLIBC_2.38 from libc.so.6 needed by /usr/lib/x86_64-linux-gnu/libcrypt.so.1|/app/probe|-
r5b-copied-libcrypt-2404|1 GLIBC_2.38 by libcrypt.so.1|missing-version GLIBC_2.38 from libc.so.6 needed by /usr/lib/x86_64-linux-gnu/libcrypt.so.1|/app/probe|-
r6-keel-scratch|255 exec /app/keel: no such file|missing-interpreter /lib64/ld-linux-x86-64.so.2|/app/keel|scratch
r7a-blind-scratch-loads|0 loaded|loads|/app/probe|-
r7b-blind-scratch-iconv|3 iconv refused (dlopen)|loads|/app/probe|-
r7c-control-cc13-iconv|0 iconv ok|loads|/app/probe|-
"
agree=0; total=0
printf '%-26s %-6s %-34s %s\n' row mode docker check
while IFS='|' read -r id docker want at base; do
    [ -n "$id" ] || continue
    docker image inspect "image-probe/$id" > /dev/null 2>&1 || { echo "ABORT: image-probe/$id missing — run corpus.sh" >&2; exit 1; }
    docker save "image-probe/$id" -o "$work/$id.tar"
    for mode in image base; do
        if [ "$mode" = image ]; then
            out="$("$LC" --image-tar "$work/$id.tar" --at "$at" 2>&1)"; rc=$?
        else
            [ "$base" = - ] && continue
            cid="$(docker create "image-probe/$id")"; docker cp -q "$cid:$at" "$work/bin" 2>/dev/null || docker cp "$cid:$at" "$work/bin" > /dev/null; docker rm "$cid" > /dev/null
            out="$("$LC" --binary "$work/bin" --at "$at" --base "$base" 2>&1)"; rc=$?
        fi
        got="$(printf '%s\n' "$out" | sed -n 's/^VERDICT //p')"
        [ -n "$got" ] || got="(no verdict, exit $rc: $(printf '%s\n' "$out" | tail -1))"
        total=$((total + 1))
        if [ "$got" = "$want" ]; then mark=agree; agree=$((agree + 1)); else mark="DISAGREE (wanted: $want)"; fi
        printf '%-26s %-6s %-34s %s — %s\n' "$id" "$mode" "$docker" "$got" "$mark"
        printf '%s\n' "$out" | sed 's/^/        | /' >> "$work/detail.txt"
    done
done <<< "$rows"
printf '\n%d of %d verdicts as wanted. r7b "agrees" by saying loads while Docker fails: that is the limit, measured.\n' "$agree" "$total"
printf '\n===== detail\n'; cat "$work/detail.txt"
[ "$agree" = "$total" ]
