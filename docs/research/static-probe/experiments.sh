#!/usr/bin/env bash
# Brief A's experiments, in the order they answer each other. LINUX ONLY — every question here is
# about the Linux linker, the Linux loader and Linux base images, and a Mac can answer none of them.
#
#   scp -r static-probe linuxbox:  &&  ssh linuxbox 'cd static-probe && ./experiments.sh'
#
# Needs: a JDK, docker, binutils and file (checked below; the report is nothing but ELF facts), and
# `musl-tools musl-dev` for the musl half (RQ3). The Kotlin/Native toolchain is fetched by Gradle on
# the first run and is ~1 GB.
#
# Writes nothing but a report on stdout; capture it into results/ with the date and the host.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"
# THE REPOSITORY'S WRAPPER, not one in this directory — there is none here, deliberately: a second
# copy of the wrapper in one repository is a second version to keep in step. Defaulting to `./gradlew`
# made every build in a clean clone "fail" with nothing to say, which is how this line came to exist.
GRADLE="${GRADLE:-$here/../../../gradlew}"

# EVERY ELF FACT IN THIS REPORT COMES OUT OF binutils, and every call to it is written `2>/dev/null`
# so that a binary legitimately without a section does not spew. On a box without binutils that same
# redirection swallows "command not found", and each variant then reports no NEEDED libraries, no
# interpreter and no imported symbols — which is not an error, it is the strongest possible result,
# printed for a binary the neighbouring `file` line calls dynamically linked. Missing tools must stop
# the run, not decorate it.
missing=""
for t in readelf nm file stat comm; do command -v "$t" > /dev/null || missing="$missing $t"; done
if [ -n "$missing" ]; then
    echo "ABORT: missing tools:$missing" >&2
    echo "  Debian/Ubuntu: apt-get install -y binutils file coreutils" >&2
    exit 2
fi

# THE DISTRIBUTION IS NOT THERE UNTIL SOMETHING BUILDS IT. These three paths used to be resolved here,
# in the script's first lines, which on a machine that has never built Kotlin/Native resolves all of
# them to nothing — and nothing reads as a clean answer rather than a missing one: an empty sysroot
# makes `suppliers` print a header with no rows under it. Resolve them again after the first build.
resolve_dist() {
    KON="$(ls -d "$HOME"/.konan/dependencies/x86_64-unknown-linux-gnu-gcc-*-glibc-*-kernel-* 2>/dev/null | head -1)"
    GCCDIR="$(find "$KON" -type d -path '*lib/gcc/x86_64-unknown-linux-gnu/*' 2>/dev/null | head -1)"
    # The NEWEST, not the first: a machine that has built more than one Kotlin version has more than
    # one distribution, and printing the older one's manifest as evidence for the newer one's
    # behaviour is a quotation from the wrong file.
    KONAN_DIST="$(ls -d "$HOME"/.konan/kotlin-native-prebuilt-* 2>/dev/null | sort -V | tail -1)"
}
resolve_dist

section() { printf '\n===== %s\n' "$1"; }

build() { # build <mode> [extra gradle args...]
    local mode="$1"; shift
    "$GRADLE" linkReleaseExecutableLinuxX64 -PlinkMode="$mode" "$@" --console=plain > "$mode.log" 2>&1
}

inspect() { # inspect <binary>
    local b="$1"
    echo "size=$(stat -c%s "$b")"
    echo "type=$(file -b "$b")"
    echo "NEEDED: $(readelf -d "$b" 2>/dev/null | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | tr '\n' ' ')"
    echo "interp: $(readelf -l "$b" 2>/dev/null | grep -c INTERP)"
    echo "glibc symbols above the 2.2.5 floor:"
    nm -D --undefined-only "$b" 2>/dev/null | grep -E 'GLIBC_2\.(3[^.]|[4-9]|1[0-9]|2[0-9]|3[0-9])' | sed 's/^ */  /' | sort -u
}

suppliers() { # suppliers <binary> — of the symbols it imports, how many does each NEEDED library export?
    # AGAINST WHICH GLIBC is half the answer, and the report's first draft got a row wrong by not
    # saying. Since 2.34, libpthread, librt and libdl are empty stubs and libc exports what they used
    # to; against the 2.19 sysroot the toolchain links with they are the real thing. So: both.
    local b="$1" undef def sysroot host
    undef=$(mktemp); def=$(mktemp)
    nm -D --undefined-only "$b" 2>/dev/null | awk '{print $2}' | sed 's/@.*//' | sort -u > "$undef"
    sysroot="$KON/x86_64-unknown-linux-gnu/sysroot"
    host=$(ldd --version 2>/dev/null | head -1 | awk '{print $NF}')
    echo "of the $(wc -l < "$undef") symbols it imports, each NEEDED library exports (link-time sysroot glibc 2.19 / host glibc $host):"
    local lib f n1 n2 names
    for lib in $(readelf -d "$b" 2>/dev/null | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'); do
        f=$(find "$sysroot/lib64" "$sysroot/lib" -maxdepth 1 -name "$lib" 2>/dev/null | head -1)
        if [ -n "$f" ]; then
            nm -D --defined-only "$f" 2>/dev/null | awk '$2 ~ /^[TWiDBV]$/ {print $3}' | sed 's/@.*//' | sort -u > "$def"
            n1=$(comm -12 "$undef" "$def" | wc -l)
            names=$(comm -12 "$undef" "$def" | head -3 | tr '\n' ' ')
        else
            n1="?"; names=""
        fi
        f="/lib/x86_64-linux-gnu/$lib"
        if [ -e "$f" ]; then
            nm -D --defined-only "$f" 2>/dev/null | awk '$2 ~ /^[TWiDBV]$/ {print $3}' | sed 's/@.*//' | sort -u > "$def"
            n2=$(comm -12 "$undef" "$def" | wc -l)
        else
            n2="?"
        fi
        printf '  %-22s %3s / %3s   %s\n' "$lib" "$n1" "$n2" "$names"
    done
    rm -f "$undef" "$def"
}

link_failure() { # link_failure <mode>
    local mode="$1"
    echo "LINK FAILED"
    echo "missing archives: $(grep -o 'unable to find library .*' "$mode.log" | sort -u | tr '\n' ' ')"
    local n
    n=$(grep -o 'undefined symbol: .*' "$mode.log" | sed 's/undefined symbol: //' | sort -u | wc -l)
    echo "distinct undefined symbols: $n"
    grep -o 'undefined symbol: .*' "$mode.log" | sed 's/undefined symbol: //' | sort -u | sed 's/^/  /'
    grep -oE 'cannot open [^ ]*' "$mode.log" | sort -u | sed 's/^/  /' | head -5
    # NOT EVERY FAILURE IS A LINK FAILURE, and a report that greps for one shape and prints nothing
    # when it does not match is a report that hides the other shapes. A clean clone failed every
    # build for a missing wrapper and this function said "LINK FAILED, 0 undefined symbols".
    if ! grep -q 'undefined symbol:\|unable to find library' "$mode.log"; then
        echo "  not a link failure — the last of $mode.log:"
        tail -12 "$mode.log" | sed 's/^/    /'
    fi
}

# WHERE THE LINK FLAGS COME FROM, read out of the distribution rather than inferred from failures.
# This is finding 1 of the upstream report: `platform.posix`'s klib manifest names libc's libraries
# for every Linux program, and no property overrides a klib manifest.
section "where the flags come from"
# ON A FIRST RUN THIS SECTION USED TO SKIP ITSELF. It reads the distribution, the distribution arrives
# with the first build, and it stands before the first build — so the evidence for finding 1 was
# produced by every host that had already built Kotlin/Native and by no host that had not. Fetch it
# here instead of reporting its absence; the ~1 GB download happens once per machine either way, and
# the build below is then up to date.
if [ -z "$KONAN_DIST" ]; then
    echo "no distribution under ~/.konan yet — fetching it with the first build (~1 GB, once)"
    build default || true
    resolve_dist
fi
if [ -n "$KONAN_DIST" ]; then
    echo "distribution: $(basename "$KONAN_DIST")"
    echo "platform.posix manifest:"
    grep -h '^linkerOpts' "$KONAN_DIST"/klib/platform/linux_x64/*posix*/default/manifest 2>/dev/null | sed 's/^/  /'
    echo "konan.properties:"
    grep -hE '^(linkerKonanFlags\.linux_x64|linkerGccFlags|libGcc\.linux_x64|targetSysRoot\.linux_x64|linker\.linux_x64|dynamicLinker\.linux_x64) ' \
        "$KONAN_DIST"/konan/konan.properties | sed 's/^/  /'
else
    echo "FAILED: still no distribution after a build — the last of default.log:"
    tail -12 default.log 2>/dev/null | sed 's/^/  /'
    exit 1
fi

# RQ0/RQ1 — what the toolchain produces unasked, and what --as-needed takes off it.
for mode in default asneeded; do
    section "$mode"
    if build "$mode"; then
        b="build/bin/linuxX64/releaseExecutable/probe-$mode.kexe"
        inspect "$b"
        [ "$mode" = default ] && suppliers "$b"
        echo "run on the host: $(./"$b" 2>&1 | tr '\n' ' ')"
    else
        link_failure "$mode"
    fi
done

# RQ2 — glibc, statically, against the toolchain's own sysroot.
section "static (glibc, toolchain sysroot)"
if build static; then
    inspect build/bin/linuxX64/releaseExecutable/probe-static.kexe
else
    link_failure static
fi

# RQ3 — musl, by setting the properties rather than working around them.
#
# The sysroot comes out of an Alpine image, where `g++` builds `libstdc++.a` against musl. That is the
# one archive the first attempt had to fake with a glibc-built copy, and faking it is what the segfault
# turned out NOT to be about — see below — but it could never have been right either.
#
# `libGcc` is its own property and is documented as sysroot-relative, so the sysroot sits beside a
# `gcc/` directory rather than two levels under one. The first attempt assembled a directory tree to
# satisfy a path it could have set.
section "musl (properties, with an Alpine-built C++ runtime)"
ROOT="${MUSL_ROOT:-$HOME/alpine-out}"
if command -v docker > /dev/null; then
    # Everything from one image: musl's libc.a and crt files, AND a musl-built libstdc++.a. Alpine
    # also ships libcrypt.a, libresolv.a, libutil.a and librt.a, which matters more than it looks:
    # those four are named by the `platform.posix` klib's manifest, which no property overrides, so a
    # sysroot without them cannot be rescued from a build file.
    rm -rf "$ROOT"
    mkdir -p "$ROOT/sysroot/usr/lib" "$ROOT/gcc"
    # CHOWNED BACK ON THE WAY OUT, and that is not tidiness. `apk` needs root inside the container,
    # so the files land owned by root — and then this script's own `rm -rf` above fails with
    # "Permission denied" on the SECOND run. A reproduction that works once is not a reproduction;
    # this was found by running it from a clean clone, which is the only way it could have been.
    docker run --rm -v "$ROOT":/out -e OWNER="$(id -u):$(id -g)" alpine:3.21 sh -c '
        apk add --no-cache g++ musl-dev > /dev/null 2>&1
        G=$(dirname "$(find / -name libgcc.a 2>/dev/null | head -1)")
        cp /usr/lib/*.a /usr/lib/*.o /out/sysroot/usr/lib/ 2>/dev/null
        cp /usr/lib/libstdc++.a /usr/lib/libsupc++.a /out/sysroot/usr/lib/ 2>/dev/null
        cp "$G"/libgcc.a "$G"/libgcc_eh.a /out/sysroot/usr/lib/ 2>/dev/null
        cp "$G"/crtbegin.o "$G"/crtend.o "$G"/libgcc.a "$G"/libgcc_eh.a /out/gcc/ 2>/dev/null
        chown -R "$OWNER" /out
    ' > /dev/null 2>&1
    # THE LINKER'S REAL ARGV, finding 2 of the report. A shim in place of `linker.linux_x64` records
    # what ld.lld was actually given and execs the real one — the property mechanism used to look
    # rather than to change. `-dynamic-linker <glibc loader>` appears BEFORE `-static` and is in none
    # of the properties, which is why `-linker-option -static` cannot by itself produce a static
    # binary.
    LLD="$(ls "$HOME"/.konan/dependencies/llvm-*-x86_64-linux-essentials-*/bin/ld.lld 2>/dev/null | head -1)"
    if [ -n "$LLD" ]; then
        printf '#!/bin/sh\nprintf "%%s\\n" "$@" > %s/lld-argv.txt\nexec %s "$@"\n' "$ROOT" "$LLD" > "$ROOT/lld-spy"
        chmod +x "$ROOT/lld-spy"
        build musl -PmuslSysRoot="$ROOT/sysroot" -PmuslLinker="$ROOT/lld-spy" > /dev/null 2>&1 || true
        if [ -f "$ROOT/lld-argv.txt" ]; then
            echo "the flags ld.lld was actually given, in order:"
            grep -nE 'dynamic-linker|^-static$|crt.*\.o$|^-l|^-B|sysroot' "$ROOT/lld-argv.txt" | sed 's/^/  /'
        else
            echo "the linker shim did not run"
        fi
    fi

    if build musl -PmuslSysRoot="$ROOT/sysroot"; then
        b="build/bin/linuxX64/releaseExecutable/probe-musl.kexe"
        inspect "$b"
        # TIMED, and not out of tidiness: with the interpreter removed the binary stops segfaulting
        # and starts HANGING before its first line of output, and an untimed run here leaves a stuck
        # process behind on every invocation.
        out=$(timeout 20 ./"$b" 2>&1); rc=$?
        echo "run on the host: rc=$rc (124 = hung) out=$(echo "$out" | tr "\n" " ")"
        # WHERE IT STOPS, not only that it stops: a hang that spins and a hang that sleeps are two
        # different bugs, and `rc=124` cannot tell them apart. Every thread parked in FUTEX_WAIT with
        # no write(2) ever issued is a deadlock in the runtime's own start-up. Optional — strace is
        # not on every host — and only the counts are printed, so two passes stay comparable.
        if command -v strace > /dev/null; then
            st="$ROOT/musl.strace"
            timeout 5 strace -f -o "$st" ./"$b" > /dev/null 2>&1
            # The exact syscall count drifts by one between runs, so only its order of magnitude is
            # printed. The blocked count is per THREAD: the last call each thread made, kept only if
            # the kill interrupted it — a wait that was later woken is not a thread that is stuck.
            n=$(wc -l < "$st")
            echo "under strace for 5s: $([ "$n" -lt 100 ] && echo "under 100" || echo "$n") syscalls, $(grep -c ' write(' "$st") write(2)," \
                 "threads named: $(grep -o 'PR_SET_NAME, "[^"]*"' "$st" | sed 's/PR_SET_NAME, //' | tr '\n' ' ')"
            blocked=$(awk '/\+\+\+ |--- SIG/ { next }
                           /<\.\.\. .* resumed>/ { if ($0 !~ /= \?/) delete last[$1]; next }
                           { last[$1] = $0 }
                           END { for (p in last) print last[p] }' "$st" |
                      grep -cE 'FUTEX_WAIT|resuming interrupted futex')
            echo "  at the kill: $(grep -c 'killed by SIGTERM' "$st") threads," \
                 "$blocked of them blocked in FUTEX_WAIT — asleep, not spinning"
        fi
    else
        link_failure musl
    fi
else
    echo "SKIPPED: needs docker for the Alpine sysroot"
fi

# The old route, kept because its failure is the reason the one above sets properties.
section "musl (route 1 as first attempted: targetSysRoot only, against the distribution's musl)"
if [ -d /usr/lib/x86_64-linux-musl ] && [ -n "$GCCDIR" ]; then
    rm -rf "$ROOT"
    mkdir -p "$ROOT/tgt/sysroot/usr/lib" "$ROOT/lib/gcc/x86_64-unknown-linux-gnu/$(basename "$GCCDIR")"
    cp /usr/lib/x86_64-linux-musl/*.o /usr/lib/x86_64-linux-musl/*.a "$ROOT/tgt/sysroot/usr/lib/"
    cp "$GCCDIR"/crtbegin.o "$GCCDIR"/crtend.o "$GCCDIR"/libgcc.a "$GCCDIR"/libgcc_eh.a \
        "$ROOT/lib/gcc/x86_64-unknown-linux-gnu/$(basename "$GCCDIR")/"

    # Three shims, and each is a claim about the gap between what the toolchain ASKS for and what the
    # program NEEDS. `linkerKonanFlags` name -lcrypt, -lstdc++ and -lgcc_s unconditionally; musl has
    # none of the three under those names.
    #   libcrypt  — empty: musl has crypt(3) in libc, and RQ0 says nothing imports it anyway;
    #   libgcc_s  — the shared unwinder, replaced by libgcc_eh.a, which is the same code;
    #   libstdc++ — the real archive from the toolchain's GLIBC sysroot, which is the shim most
    #               likely to poison the result and therefore the one to suspect first.
    : > /tmp/empty.c && gcc -c /tmp/empty.c -o /tmp/empty.o 2>/dev/null &&
        ar rcs "$ROOT/tgt/sysroot/usr/lib/libcrypt.a" /tmp/empty.o
    find "$KON" -name libstdc++.a -exec cp {} "$ROOT/tgt/sysroot/usr/lib/" \; 2>/dev/null
    cp "$ROOT/lib/gcc/x86_64-unknown-linux-gnu/$(basename "$GCCDIR")/libgcc_eh.a" \
        "$ROOT/tgt/sysroot/usr/lib/libgcc_s.a"

    echo "SKIPPED: superseded by the section above; kept for the record, not re-run"
else
    echo "SKIPPED: needs musl-tools/musl-dev and a konan gcc dependency"
fi

# RQ1/RQ5 — which base image each variant actually starts in, and what the image costs.
section "base images"
command -v docker > /dev/null || { echo "SKIPPED: no docker"; exit 0; }
ctx=$(mktemp -d)
for mode in default asneeded musl; do
    b="build/bin/linuxX64/releaseExecutable/probe-$mode.kexe"
    [ -f "$b" ] && cp "$b" "$ctx/probe-$mode"
done
printf '%-32s %-9s %10s  %s\n' base variant image result
for base in gcr.io/distroless/cc-debian13 gcr.io/distroless/base-debian13 \
            gcr.io/distroless/static-debian13 scratch; do
    for mode in default asneeded musl; do
        [ -f "$ctx/probe-$mode" ] || continue
        tag="sp-$(echo "$base-$mode" | tr '/:.' '---')"
        printf 'FROM %s\nCOPY probe-%s /probe\nENTRYPOINT ["/probe"]\n' "$base" "$mode" > "$ctx/Dockerfile.$tag"
        docker build -q -f "$ctx/Dockerfile.$tag" -t "$tag" "$ctx" < /dev/null > /dev/null 2>&1 || continue
        # DETACHED, THEN KILLED — and `timeout docker run` is NOT a substitute, which cost two runs
        # to learn. One variant in this matrix is the musl binary of finding 3, which hangs. An
        # untimed `docker run` waits forever; and `timeout 20 docker run` waits forever too, because
        # `timeout` sends SIGTERM to the CLIENT, the client forwards it to a process that does not
        # handle signals, and the container outlives both — observed at nine hours, with the
        # container still "Up".
        #
        # So: start it detached, give it a moment, read what it printed, and remove it by force. The
        # only thing that bounds a container is killing the container.
        cid=$(docker run -d "$tag" 2>/dev/null)
        sleep 5
        out=$(docker logs "$cid" 2>&1 | tr '\n' ' ' | cut -c1-88)
        docker rm -f "$cid" > /dev/null 2>&1
        [ -z "$out" ] && out="<no output in 5s: hung, or silent>"
        size=$(docker image inspect "$tag" --format '{{.Size}}' < /dev/null)
        printf '%-32s %-9s %10s  %s\n' "$(basename "$base")" "$mode" "$size" "$out"
    done
done
echo "--- base images on their own"
for base in gcr.io/distroless/cc-debian13 gcr.io/distroless/base-debian13 \
            gcr.io/distroless/static-debian13 debian:13-slim; do
    printf '%-38s %s\n' "$base" "$(docker image inspect "$base" --format '{{.Size}}' < /dev/null 2>/dev/null)"
done
rm -rf "$ctx"
