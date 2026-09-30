#!/usr/bin/env bash
# B-27: the corpus the load check will be scored on, and the verdict it will be scored against.
# LINUX ONLY, with Docker — `docker run` is the ground truth, and the whole point of the corpus is
# that nothing here was decided by the code under test.
#
#   KEEL_DIR=<a keel checkout> ./corpus.sh | tee results/<date>-corpus.txt
#   ./corpus.sh                        # CI: no keel, the probe stands in for it (below)
#
# Besides the report on stdout, every run writes build/corpus.tsv — row, exit code, first line — so
# that score.sh compares the check with THIS run of `docker run`, not with a table typed earlier.
#
# Every row is an image built from a base pinned by digest (bases.lock; written on the first run
# from the tags below, then read), and run. Each row prints: the base digest, the binary's sha256,
# the binary's NEEDED list, the exit code, and the first line the container wrote. The rows that
# are supposed to start also answer a request, because a container that has not exited yet has not
# started either.
#
# Needs: docker, readelf, sha256sum, curl, a JDK. Checked below — a missing readelf would print an
# empty NEEDED list, which for this corpus is the most misleading value there is.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"
GRADLE="${GRADLE:-$here/../../../gradlew}"
# OPTIONAL. Without it the rows that name keel run the --as-needed probe instead: its NEEDED list is
# keel's, entry for entry (both linked by sborka.kmp's rule), so every loader question those rows ask
# is asked the same way. What is lost is r1's /health answer — a probe does not serve.
KEEL_DIR="${KEEL_DIR:-}"

missing=""
for t in docker readelf sha256sum curl timeout; do command -v "$t" > /dev/null || missing="$missing $t"; done
if [ -n "$missing" ]; then
    echo "ABORT: missing tools:$missing" >&2
    exit 2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# ---------------------------------------------------------------- binaries

build_probe() { # build_probe <linkMode> <task>
    "$GRADLE" -p "$here" "$2" -PlinkMode="$1" --console=plain --no-daemon > "$work/build-$1${2//:/-}.log" 2>&1 ||
        { echo "ABORT: probe build $1 $2 failed, log:" >&2; tail -30 "$work/build-$1${2//:/-}.log" >&2; exit 1; }
}
build_probe default :linkReleaseExecutableLinuxX64
build_probe asneeded :linkReleaseExecutableLinuxX64
build_probe asneeded :curl:linkReleaseExecutableLinuxX64
build_probe runpath :linkReleaseExecutableLinuxX64

if [ -n "$KEEL_DIR" ]; then
    "$KEEL_DIR/gradlew" -p "$KEEL_DIR" :server:stageNativeImage --console=plain --no-daemon > "$work/build-keel.log" 2>&1 ||
        { echo "ABORT: keel build failed, log:" >&2; tail -30 "$work/build-keel.log" >&2; exit 1; }
fi

PROBE_DEFAULT="$here/build/bin/linuxX64/releaseExecutable/probe-default.kexe"
PROBE_ASNEEDED="$here/build/bin/linuxX64/releaseExecutable/probe-asneeded.kexe"
CURL_ASNEEDED="$here/curl/build/bin/linuxX64/releaseExecutable/curl-asneeded.kexe"
PROBE_RUNPATH="$here/build/bin/linuxX64/releaseExecutable/probe-runpath.kexe"
if [ -n "$KEEL_DIR" ]; then KEEL="$KEEL_DIR/server/build/native-image/keel"; else KEEL="$PROBE_ASNEEDED"; fi
mkdir -p "$here/build"
TSV="$here/build/corpus.tsv"
: > "$TSV"
for b in "$PROBE_DEFAULT" "$PROBE_ASNEEDED" "$CURL_ASNEEDED" "$PROBE_RUNPATH" "$KEEL"; do
    [ -f "$b" ] || { echo "ABORT: not built: $b" >&2; exit 1; }
done

# ---------------------------------------------------------------- bases, by digest

# The tags are where the digests came from, not what the rows use. A row that named a tag would be a
# different row the day the tag moved.
TAGS="gcr.io/distroless/cc-debian13 gcr.io/distroless/cc-debian12 gcr.io/distroless/base-debian13 ubuntu:26.04 ubuntu:24.04 debian:13"
# A tag already in the lock keeps its digest; a tag added later is resolved once and appended, so the
# rows that existed before it are not moved by adding it (B-36 added debian:13).
touch bases.lock
for t in $TAGS; do
    awk -v t="$t" '$1 == t { found = 1 } END { exit !found }' bases.lock && continue
    docker pull -q "$t" > /dev/null || { echo "ABORT: cannot pull $t" >&2; exit 1; }
    printf '%s %s\n' "$t" "$(docker image inspect --format '{{index .RepoDigests 0}}' "$t")" >> bases.lock
done
base() { awk -v t="$1" '$1 == t { print $2 }' bases.lock; }
CC13="$(base gcr.io/distroless/cc-debian13)"
CC12="$(base gcr.io/distroless/cc-debian12)"
BASE13="$(base gcr.io/distroless/base-debian13)"
U2604="$(base ubuntu:26.04)"
U2404="$(base ubuntu:24.04)"
DEB13="$(base debian:13)"
for d in "$CC13" "$CC12" "$BASE13" "$U2604" "$U2404" "$DEB13"; do
    [ -n "$d" ] || { echo "ABORT: bases.lock is missing a base" >&2; exit 1; }
done

# ---------------------------------------------------------------- rows

needed() { readelf -d "$1" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | tr '\n' ' '; }

# row <id> <expected> <binary> <run args...> — the Dockerfile arrives on stdin, with the binary
# already in the context as `bin`.
row() {
    local id="$1" expect="$2" bin="$3"; shift 3
    local ctx="$work/$id"
    mkdir -p "$ctx"
    cp "$bin" "$ctx/bin"
    cat > "$ctx/Dockerfile"
    printf '\n===== %s (expected: %s)\n' "$id" "$expect"
    printf 'from:    %s\n' "$(sed -n 's/^FROM \([^ ]*\).*/\1/p' "$ctx/Dockerfile" | tr '\n' ' ')"
    printf 'binary:  %s sha256=%s\n' "$(basename "$bin")" "$(sha256sum "$bin" | cut -c1-64)"
    printf 'NEEDED:  %s\n' "$(needed "$bin")"
    if ! docker build -q -t "image-probe/$id" "$ctx" > "$work/$id.build" 2>&1; then
        printf 'image:   BUILD FAILED — %s\n' "$(tail -1 "$work/$id.build")"
        return
    fi
    local out rc
    out="$(timeout 30 docker run --rm "image-probe/$id" "$@" 2>&1)"
    rc=$?
    printf 'exit:    %s\n' "$rc"
    printf 'first:   %s\n' "$(printf '%s\n' "$out" | head -1)"
    printf '%s\t%s\t%s\n' "$id" "$rc" "$(printf '%s\n' "$out" | head -1 | tr '\t' ' ')" >> "$TSV"
}

# serve <id> — the rows that have to start: run detached, ask /health/ready, then SIGTERM and read the
# exit code. `docker stop` sends SIGTERM and waits; exit 0 means the ordered shutdown ran.
serve() {
    local id="$1" cid code rc i
    cid="$(docker run -d -p 127.0.0.1::8080 "image-probe/$id")" || { echo "serve:   RUN FAILED"; return; }
    local port
    port="$(docker port "$cid" 8080 | head -1 | sed 's/.*://')"
    code=000
    for i in $(seq 1 60); do
        code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/health/ready" || true)"
        [ "$code" = 200 ] && break
        sleep 0.5
    done
    docker stop -t 20 "$cid" > /dev/null
    rc="$(docker inspect --format '{{.State.ExitCode}}' "$cid")"
    printf 'serve:   /health/ready=%s, exit after SIGTERM=%s\n' "$code" "$rc"
    printf '%s\tserve\t%s %s\n' "$id" "$code" "$rc" >> "$TSV"
    printf 'log:     %s\n' "$(docker logs "$cid" 2>&1 | tail -1)"
    docker rm "$cid" > /dev/null
}

row r1-keel-cc13 "starts" "$KEEL" <<EOF
FROM $CC13
COPY bin /app/keel
ENV MALLOC_ARENA_MAX=2
ENTRYPOINT ["/app/keel"]
EOF
[ -n "$KEEL_DIR" ] && serve r1-keel-cc13

row r2-keel-base13 "fails: libgcc_s.so.1" "$KEEL" <<EOF
FROM $BASE13
COPY bin /app/keel
ENTRYPOINT ["/app/keel"]
EOF

row r3-curl-cc12 "fails: libz.so.1" "$CURL_ASNEEDED" <<EOF
FROM $CC12
COPY bin /app/curl
ENTRYPOINT ["/app/curl"]
EOF

row r4-default-cc13 "fails: libcrypt.so.1" "$PROBE_DEFAULT" <<EOF
FROM $CC13
COPY bin /app/probe
ENTRYPOINT ["/app/probe"]
EOF

# The copied library, twice: from the builder the incident came from (26.04) and from the one keel's
# Dockerfile uses today (24.04). Which of them reproduces is a finding, not an assumption.
for pair in "r5a-copied-libcrypt-2604:$U2604" "r5b-copied-libcrypt-2404:$U2404"; do
    id="${pair%%:*}"; donor="${pair#*:}"
    row "$id" "fails: GLIBC_2.3x required by libcrypt.so.1 (cc-debian12 has 2.36)" "$PROBE_DEFAULT" <<EOF
FROM $donor AS donor
RUN mkdir /out && cp -L /usr/lib/x86_64-linux-gnu/libcrypt.so.1 /out/
FROM $CC12
COPY --from=donor /out/libcrypt.so.1 /usr/lib/x86_64-linux-gnu/libcrypt.so.1
COPY bin /app/probe
ENTRYPOINT ["/app/probe"]
EOF
done

row r6-keel-scratch "fails: no loader" "$KEEL" <<EOF
FROM scratch
COPY bin /app/keel
ENTRYPOINT ["/app/keel"]
EOF

# THE BLIND SPOT. Everything the binary names is copied from cc-debian13 — the loader and every NEEDED
# entry — so a check that resolves NEEDED must pass this image. gconv is not copied. The image loads
# (r7a), and fails the first time glibc needs a conversion module (r7b); r7c is the same call on the
# base that has gconv, so r7b's failure is about the missing files and not about the call.
libs=""
for l in $(needed "$PROBE_ASNEEDED"); do
    libs="${libs}COPY --from=donor /lib/x86_64-linux-gnu/$l /lib/x86_64-linux-gnu/$l
"
done
blind="FROM $CC13 AS donor
FROM scratch
COPY --from=donor /lib64/ld-linux-x86-64.so.2 /lib64/ld-linux-x86-64.so.2
${libs}COPY bin /app/probe
ENTRYPOINT [\"/app/probe\"]"
row r7a-blind-scratch-loads "starts: probe: loaded" "$PROBE_ASNEEDED" <<< "$blind"
row r7b-blind-scratch-iconv "fails at run time: iconv refused" "$PROBE_ASNEEDED" iconv <<< "$blind"
row r7c-control-cc13-iconv "starts: iconv ok" "$PROBE_ASNEEDED" iconv <<EOF
FROM $CC13
COPY bin /app/probe
ENTRYPOINT ["/app/probe"]
EOF

# ---------------------------------------------------------------- B-36: the paths B-27 never reached
#
# Each path the load check implements and no row above exercises, as a pair: the image where the path
# is what makes it load, and its twin where the same path is missing. libcrypt.so.1 is the library
# moved around, because the default-linked probe needs it and nothing else in these images does.

# ld.so.cache: Ubuntu's loader reads a real cache. The library is moved out of every default
# directory, so only the cache can place it (r8a); the twin moves it and does not re-run ldconfig, so
# the cache still names the old path (r8b). Moving it also leaves a whiteout in the layer.
row r8a-cache-places-it "starts: probe: loaded" "$PROBE_DEFAULT" <<EOF
FROM $U2404
RUN mkdir -p /opt/crypt && mv /usr/lib/x86_64-linux-gnu/libcrypt.so.1* /opt/crypt/ \
    && echo /opt/crypt > /etc/ld.so.conf.d/crypt.conf && ldconfig
COPY bin /app/probe
ENTRYPOINT ["/app/probe"]
EOF
row r8b-cache-is-stale "fails: libcrypt.so.1" "$PROBE_DEFAULT" <<EOF
FROM $U2404
RUN mkdir -p /opt/crypt && mv /usr/lib/x86_64-linux-gnu/libcrypt.so.1* /opt/crypt/
COPY bin /app/probe
ENTRYPOINT ["/app/probe"]
EOF

# A whiteout: a library the base layer carries, deleted by a later layer (r9a); the twin is the base
# untouched (r9b).
row r9a-whiteout-removes-it "fails: libgcc_s.so.1" "$PROBE_ASNEEDED" <<EOF
FROM $U2404
RUN rm /usr/lib/x86_64-linux-gnu/libgcc_s.so.1
COPY bin /app/probe
ENTRYPOINT ["/app/probe"]
EOF
row r9b-no-whiteout "starts: probe: loaded" "$PROBE_ASNEEDED" <<EOF
FROM $U2404
COPY bin /app/probe
ENTRYPOINT ["/app/probe"]
EOF

# RUNPATH with $ORIGIN: libcrypt.so.1 from Debian 13 (the glibc cc-debian13 carries) beside the binary
# in /app/lib. The binary that names $ORIGIN/lib finds it (r10a); the same image with the binary that
# does not, does not (r10b).
beside="FROM $DEB13 AS donor
RUN mkdir /out && cp -L /usr/lib/x86_64-linux-gnu/libcrypt.so.1 /out/
FROM $CC13
COPY --from=donor /out/libcrypt.so.1 /app/lib/libcrypt.so.1
COPY bin /app/probe
ENTRYPOINT [\"/app/probe\"]"
row r10a-runpath-finds-it "starts: probe: loaded" "$PROBE_RUNPATH" <<< "$beside"
row r10b-no-runpath "fails: libcrypt.so.1" "$PROBE_DEFAULT" <<< "$beside"

# LD_LIBRARY_PATH from the image's config: the same library in /opt/crypt, found through ENV (r11a),
# and not without it (r11b).
row r11a-ld-library-path "starts: probe: loaded" "$PROBE_DEFAULT" <<EOF
FROM $DEB13 AS donor
RUN mkdir /out && cp -L /usr/lib/x86_64-linux-gnu/libcrypt.so.1 /out/
FROM $CC13
COPY --from=donor /out/libcrypt.so.1 /opt/crypt/libcrypt.so.1
ENV LD_LIBRARY_PATH=/opt/crypt
COPY bin /app/probe
ENTRYPOINT ["/app/probe"]
EOF
row r11b-no-ld-library-path "fails: libcrypt.so.1" "$PROBE_DEFAULT" <<EOF
FROM $DEB13 AS donor
RUN mkdir /out && cp -L /usr/lib/x86_64-linux-gnu/libcrypt.so.1 /out/
FROM $CC13
COPY --from=donor /out/libcrypt.so.1 /opt/crypt/libcrypt.so.1
COPY bin /app/probe
ENTRYPOINT ["/app/probe"]
EOF

printf '\n===== host\n'
printf 'docker:  %s\n' "$(docker version --format '{{.Server.Version}}')"
printf 'kernel:  %s\n' "$(uname -r | cut -d- -f1)"
printf 'keel:    %s\n' "$([ -n "$KEEL_DIR" ] && { git -C "$KEEL_DIR" rev-parse HEAD 2>/dev/null || echo 'not a git checkout'; } || echo 'none: the --as-needed probe stood in')"
printf 'sborka:  %s\n' "$(git -C "$here" rev-parse HEAD 2>/dev/null)"
