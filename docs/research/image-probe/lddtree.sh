#!/usr/bin/env bash
# B-31: the nearest prior art, run on the same corpus. `lddtree -R <root>` (pax-utils) resolves a
# binary's NEEDED entries against a root filesystem instead of the host's — which is the static
# question, given an unpacked image. LINUX, with Docker and B-27's images present.
#
#   ./lddtree.sh | tee results/<date>-lddtree.txt
#
# `lddtree` is its own Alpine package, not part of `pax-utils` there — the first run of this script
# asked for pax-utils alone and every row answered "lddtree: not found", exit 127.
#
# pax-utils runs inside an Alpine container (pinned by digest below) so that nothing is installed
# on the host. Each image is unpacked with `docker export`, i.e. with its layers already flattened
# by the daemon — the part of the job lddtree does not do.
set -uo pipefail
cd "$(dirname "$0")"
ALPINE="${ALPINE:-alpine:3.22}"
docker pull -q "$ALPINE" > /dev/null
ALPINE_DIGEST="$(docker image inspect --format '{{index .RepoDigests 0}}' "$ALPINE")"
echo "lddtree from pax-utils in $ALPINE_DIGEST"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
for row in r1-keel-cc13:/app/keel r2-keel-base13:/app/keel r3-curl-cc12:/app/curl r4-default-cc13:/app/probe \
           r5a-copied-libcrypt-2604:/app/probe r5b-copied-libcrypt-2404:/app/probe r6-keel-scratch:/app/keel \
           r7a-blind-scratch-loads:/app/probe; do
    id="${row%%:*}"; at="${row#*:}"
    root="$work/$id"; mkdir -p "$root"
    cid="$(docker create "image-probe/$id")"; docker export "$cid" | tar -x -C "$root" 2> /dev/null; docker rm "$cid" > /dev/null
    printf '\n===== %s\n' "$id"
    docker run --rm -v "$root:/r:ro" "$ALPINE_DIGEST" sh -c \
        "apk add -q pax-utils lddtree > /dev/null 2>&1 && lddtree -R /r/ $at; echo \"lddtree exit=\$?\"" 2>&1
done
