#!/usr/bin/env bash
# Brief A's experiments, in the order they answer each other. LINUX ONLY — every question here is
# about the Linux linker, the Linux loader and Linux base images, and a Mac can answer none of them.
#
#   scp -r static-probe linuxbox:  &&  ssh linuxbox 'cd static-probe && ./experiments.sh'
#
# Needs: a JDK, docker, and `musl-tools musl-dev` for the musl half (RQ3). The Kotlin/Native
# toolchain is fetched by Gradle on the first run and is ~1 GB.
#
# Writes nothing but a report on stdout; capture it into results/ with the date and the host.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"
GRADLE="${GRADLE:-./gradlew}"
KON="$(ls -d "$HOME"/.konan/dependencies/x86_64-unknown-linux-gnu-gcc-*-glibc-*-kernel-* 2>/dev/null | head -1)"
GCCDIR="$(find "$KON" -type d -path '*lib/gcc/x86_64-unknown-linux-gnu/*' 2>/dev/null | head -1)"

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

link_failure() { # link_failure <mode>
    local mode="$1"
    echo "LINK FAILED"
    echo "missing archives: $(grep -o 'unable to find library .*' "$mode.log" | sort -u | tr '\n' ' ')"
    local n
    n=$(grep -o 'undefined symbol: .*' "$mode.log" | sed 's/undefined symbol: //' | sort -u | wc -l)
    echo "distinct undefined symbols: $n"
    grep -o 'undefined symbol: .*' "$mode.log" | sed 's/undefined symbol: //' | sort -u | sed 's/^/  /'
    grep -oE 'cannot open [^ ]*' "$mode.log" | sort -u | sed 's/^/  /' | head -5
}

# RQ0/RQ1 — what the toolchain produces unasked, and what --as-needed takes off it.
for mode in default asneeded; do
    section "$mode"
    if build "$mode"; then
        b="build/bin/linuxX64/releaseExecutable/probe-$mode.kexe"
        inspect "$b"
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

# RQ3 route 1 — the same override pointed at musl.
#
# The sysroot has to be ASSEMBLED. The toolchain looks for `crt1.o` under `<sysroot>/usr/lib` and for
# `crtbegin.o` under `<sysroot>/../../lib/gcc/<triple>/<gcc version>`, so the sysroot must sit exactly
# two levels below the directory holding the gcc auxiliaries — pointing the property at a
# distribution's musl directory fails before it reaches a single symbol.
section "musl (route 1: -Xoverride-konan-properties targetSysRoot)"
ROOT="${MUSL_ROOT:-$HOME/muslroot}"
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

    if build musl -PmuslSysRoot="$ROOT/tgt/sysroot"; then
        b="build/bin/linuxX64/releaseExecutable/probe-musl.kexe"
        inspect "$b"
        out=$(./"$b" 2>&1); rc=$?
        echo "run on the host: rc=$rc out=$(echo "$out" | tr '\n' ' ')"
    else
        link_failure musl
    fi
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
        out=$(docker run --rm "$tag" 2>&1 < /dev/null | tr '\n' ' ' | cut -c1-88)
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
