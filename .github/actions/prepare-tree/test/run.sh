#!/usr/bin/env bash
# Asks prepare-tree.sh every question it has to answer, without a runner, apt or a network.
#
# `sudo` and `apt-get` are stubs on PATH that write down what they were asked, and every prepare
# script here writes a marker when it runs. So a refusal is checked for what it must mean — exit 3,
# NOTHING installed and NOTHING run — and not only for a red exit code, which a typo would give too.
#
#   bash .github/actions/prepare-tree/test/run.sh
#
# Bash 3.2 on purpose: the action also runs on the macOS runner, and a Mac's /bin/bash is that old.
# Single quotes around '$' are the point throughout: these are inputs a shell must NOT expand.
# shellcheck disable=SC2016
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
# SUBJECT is overridable so that a deliberately broken copy can be shown to turn this red.
SUBJECT=${SUBJECT:-$HERE/../prepare-tree.sh}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Resolved, because a Mac's temporary directory is a symlink (/var -> /private/var) and the subject
# compares resolved paths.
work=$(cd "$work" && pwd -P)
tree=$work/tree
outside=$work/outside
bin=$work/bin
marker=$work/marker
log=$work/apt.log
mkdir -p "$tree/ci/sub" "$tree/a dir" "$outside" "$bin"

# What runs records itself and the directory it ran in; that is all a fixture needs to say.
cat > "$tree/ci/build.sh" <<'EOF'
echo "ran $(pwd -P)" >> "$MARKER"
EOF
cp "$tree/ci/build.sh" "$outside/evil.sh"
cp "$tree/ci/build.sh" "$tree/a dir/build.sh"
printf 'echo "ran" >> "$MARKER"\nexit 7\n' > "$tree/ci/fails.sh"
ln -s ../outside "$tree/escape"              # a directory that leaves the checkout
ln -s ../outside/evil.sh "$tree/evil.sh"     # a file that leaves it
ln -s ci/build.sh "$tree/inside-link.sh"     # a symlink that stays inside is fine
ln -s ../../../outside "$tree/ci/sub/up"     # deeper, through a nested directory

for stub in sudo apt-get; do
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "%s $*" >> "$APT_LOG"\n' "$stub" > "$bin/$stub"
  chmod +x "$bin/$stub"
done

failures=0
passes=0

# run <os> <apt-packages> <prepare> — the subject, in the tree, with the stubs first on PATH.
run() {
  rm -f "$marker" "$log"
  (
    cd "$tree" &&
      env PATH="$bin:$PATH" APT_LOG="$log" MARKER="$marker" RUNNER_OS="$1" \
        APT_PACKAGES="$2" PREPARE="$3" TREE="$tree" \
        bash "$SUBJECT"
  ) > "$work/out" 2>&1
}

fail() {
  echo "FAIL: $1"
  sed 's/^/    | /' "$work/out"
  failures=$((failures + 1))
}

# refused <why> <os> <apt-packages> <prepare>
refused() {
  local why=$1
  shift
  run "$@"
  local status=$?
  if [ "$status" -ne 3 ]; then
    fail "$why: exit $status, expected 3 (refused)"
  elif [ -s "$log" ]; then
    fail "$why: refused, yet apt was called: $(tr '\n' ';' < "$log")"
  elif [ -e "$marker" ]; then
    fail "$why: refused, yet a script ran"
  elif ! grep -q '::error::' "$work/out"; then
    fail "$why: refused without an ::error:: saying why"
  else
    passes=$((passes + 1))
  fi
}

# accepted <why> <expected apt install line or ''> <expected marker or ''> <os> <apt> <prepare>
accepted() {
  local why=$1 want_apt=$2 want_marker=$3
  shift 3
  run "$@"
  local status=$?
  local got_apt='' got_marker=''
  [ -f "$log" ] && got_apt=$(grep 'apt-get install' "$log")
  [ -f "$marker" ] && got_marker=$(cat "$marker")
  if [ "$status" -ne 0 ]; then
    fail "$why: exit $status, expected 0"
  elif [ "$got_apt" != "$want_apt" ]; then
    fail "$why: apt install was '$got_apt', expected '$want_apt'"
  elif [ "$got_marker" != "$want_marker" ]; then
    fail "$why: the marker said '$got_marker', expected '$want_marker'"
  else
    passes=$((passes + 1))
  fi
}

install_line() {
  echo "sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $*"
}

# --- apt-packages: outside the Debian package-name set, or not a bare name at all ---------------
refused 'a shell metacharacter'       Linux 'libfoo;touch' ''
refused 'a command substitution'      Linux '$(id)' ''
refused 'a backtick'                  Linux '`id`' ''
refused 'an upper-case letter'        Linux 'LibFoo' ''
refused 'a leading dash, an option'   Linux '-oDebug::pkgProblemResolver=1' ''
refused 'a single character'          Linux 'a' ''
refused 'a pinned version'            Linux 'libfoo=1.0' ''
refused 'a release'                   Linux 'libfoo/noble' ''
refused 'an architecture'             Linux 'libfoo:amd64' ''
refused 'a trailing dash, a removal'  Linux 'libfoo-' ''
refused 'a glob'                      Linux 'libfoo*' ''
refused 'one bad name among good'     Linux 'libmongoc-dev lib;bson libbson-dev' ''
refused 'packages on the macOS runner' macOS 'libmongoc-dev' ''

# --- prepare: a path that leaves the checkout, or is not a script in it ---------------------------
refused 'a parent directory'          Linux '' '../outside/evil.sh'
refused 'a parent in the middle'      Linux '' 'ci/../../outside/evil.sh'
refused 'a bare ..'                   Linux '' '..'
# Refused even when it comes back in: '..' is not something a script's path needs, and a rule that
# depends on where the climb ends is one more thing to get right.
refused 'a .. that comes back in'     Linux '' 'ci/../ci/build.sh'
refused 'an absolute path outside'    Linux '' "$outside/evil.sh"
refused 'an absolute path inside'     Linux '' "$tree/ci/build.sh"
refused 'a directory symlink out'     Linux '' 'escape/evil.sh'
refused 'a file symlink out'          Linux '' 'evil.sh'
refused 'a nested symlink out'        Linux '' 'ci/sub/up/evil.sh'
refused 'a script that is not there'  Linux '' 'ci/missing.sh'
refused 'a directory'                 Linux '' 'ci'
refused 'a command line, not a path'  Linux '' 'ci/build.sh; touch x'
refused 'a space'                     Linux '' 'a dir/build.sh'
# The order the issue asks for: a bad path refuses before a good package is installed.
refused 'good packages, bad path'     Linux 'libmongoc-dev' '../outside/evil.sh'
refused 'good path, bad packages'     Linux 'lib;bson' 'ci/build.sh'

# --- what has to go through ------------------------------------------------------------------------
accepted 'nothing asked, nothing done' '' '' Linux '' ''
accepted 'two packages' "$(install_line libmongoc-dev libbson-dev)" '' \
  Linux 'libmongoc-dev libbson-dev' ''
accepted 'plus, dots and digits' "$(install_line g++ libstdc++6 python3.12)" '' \
  Linux 'g++ libstdc++6 python3.12' ''
accepted 'loose whitespace' "$(install_line libbson-dev libmongoc-dev)" '' \
  Linux '  libbson-dev	  libmongoc-dev ' ''
accepted 'a script, run from the root' '' "ran $tree" Linux '' 'ci/build.sh'
accepted 'a ./ in front' '' "ran $tree" Linux '' './ci/build.sh'
accepted 'a symlink that stays inside' '' "ran $tree" Linux '' 'inside-link.sh'
accepted 'a script on macOS' '' "ran $tree" macOS '' 'ci/build.sh'
accepted 'packages, then the script' "$(install_line libbson-dev)" "ran $tree" \
  Linux 'libbson-dev' 'ci/build.sh'

# A script that fails fails the step, with its own status, and is not mistaken for a refusal.
run Linux '' 'ci/fails.sh'
status=$?
if [ "$status" -ne 7 ] || [ ! -e "$marker" ]; then
  fail "a failing script: exit $status, expected its own 7, after it ran"
else
  passes=$((passes + 1))
fi

echo "$passes passed, $failures failed"
[ "$failures" -eq 0 ]
