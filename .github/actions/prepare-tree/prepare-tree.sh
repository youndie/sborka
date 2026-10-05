#!/usr/bin/env bash
# Installs system packages and runs a script of the library's own, before Gradle — or refuses to.
#
# Read from the environment and never from the command line, so that no input is ever part of a
# shell body:
#
#   APT_PACKAGES  space-separated Debian package names; empty installs nothing
#   PREPARE       a path to a script inside TREE, run with bash from TREE; empty runs nothing
#   TREE          the library's checkout
#   RUNNER_OS     set by the runner; packages are refused anywhere but Linux
#
# BOTH INPUTS ARE CHECKED BEFORE EITHER IS ACTED ON. A bad path refuses before a good package is
# installed, and a bad name before a good script runs: half a preparation followed by a refusal
# leaves a runner nobody can reason about. A refusal exits 3, the code the publishing workflows use
# for "this was never going to work"; a failing script exits with its own status.
#
# Bash 3.2 on purpose: the macOS runner runs this too.
set -euo pipefail

refuse() {
  local line
  for line in "$@"; do echo "::error::$line" >&2; done
  exit 3
}

: "${TREE:?TREE names the checkout}"
root=$(cd "$TREE" && pwd -P)

# --- apt-packages ----------------------------------------------------------------------------------
# Split on whitespace into an array, which `read -a` does without expanding a glob.
packages=()
read -r -a packages <<< "${APT_PACKAGES:-}" || true

if [ "${#packages[@]}" -gt 0 ]; then
  # Not ignored on a host that has no apt: a library that needs a package and silently got none
  # fails later, in a cinterop, saying a header is missing — which reads as a library bug.
  if [ "${RUNNER_OS:-}" != Linux ]; then
    refuse "apt-packages was given on a ${RUNNER_OS:-non-Linux} runner, which has no apt." \
      "Install what this build needs another way, or publish from Linux."
  fi
  for name in "${packages[@]}"; do
    # Debian policy §5.6.7: lower-case letters, digits, '+', '-' and '.', at least two characters,
    # starting with a letter or a digit. Narrower than what apt-get accepts on purpose: `name=1.0`,
    # `name/release` and `name:arch` are not names, a leading '-' is an option, and a trailing '-'
    # tells apt-get to REMOVE the package.
    case "$name" in
      ? | [!a-z0-9]* | *[!a-z0-9+.-]* | *-)
        refuse "apt-packages: '$name' is not a Debian package name (a-z, 0-9, '+', '-', '.';" \
          "two characters or more; a letter or digit first; not ending in '-')." ;;
    esac
  done
fi

# --- prepare ---------------------------------------------------------------------------------------
script=''
if [ -n "${PREPARE:-}" ]; then
  # A PATH, not a command line: the characters a path in a repository plausibly has, and nothing a
  # shell would read as anything else.
  case "$PREPARE" in
    *[!A-Za-z0-9._/+@-]*)
      refuse "prepare: '$PREPARE' is not a plain path inside the repository." \
        "It takes a path to a script (letters, digits, '.', '_', '-', '+', '@', '/'), not a command." ;;
  esac
  case "$PREPARE" in
    /*) refuse "prepare: '$PREPARE' is absolute; give a path relative to the repository's root." ;;
  esac
  case "/$PREPARE/" in
    */../*) refuse "prepare: '$PREPARE' climbs out with '..'; it has to name a script in the checkout." ;;
  esac
  # Symlinks are followed before the comparison, so a link inside the tree pointing outside it is
  # caught here and not trusted for where it sits. realpath exists on both runners (GNU coreutils,
  # and macOS since 13).
  if ! resolved=$(cd "$root" && realpath "$PREPARE" 2>/dev/null); then
    refuse "prepare: '$PREPARE' does not exist in the checkout."
  fi
  case "$resolved" in
    "$root"/*) ;;
    *) refuse "prepare: '$PREPARE' resolves to '$resolved', outside the checkout ($root)." ;;
  esac
  [ -f "$resolved" ] || refuse "prepare: '$PREPARE' is not a file in the checkout."
  script=$resolved
fi

# --- act -------------------------------------------------------------------------------------------
if [ "${#packages[@]}" -gt 0 ]; then
  echo "installing: ${packages[*]}"
  sudo apt-get update -qq
  # `--no-install-recommends`: what the build asked for, not what a package suggests next to it.
  sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${packages[@]}"
fi

if [ -n "$script" ]; then
  echo "running ${script#"$root"/} from $root"
  cd "$root"
  bash "$script"
fi
