# The documentation gate.
#
# One named target, so that what a person runs and what CI would run cannot drift apart. Everything
# not in `check` is not a gate.
#
# This is about `docs/` only: the build is `./gradlew check`, which is a different tree of checks
# with a different failure mode, and joining them would mean a documentation typo failing a publish.

DOCS ?= docs
BACKLOG ?= backlog.md
REPOS ?= ..
PY ?= python3

.PHONY: check gate report fix help

help:
	@echo "make check   - the gate: blocking checks over docs/"
	@echo "make report  - non-blocking: BDD coverage, code anchors (needs REPOS=..)"
	@echo "make fix     - regenerate the backlog index, fill in missing coverage-map lines"

check: gate report

# Blocking. Any of these failing means the documentation contradicts itself, which is a defect and
# not a matter of opinion.
gate:
	$(PY) scripts/backlog_index.py --check --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/docs_check.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --check --docs $(DOCS)

# Non-blocking on purpose. An anchor rots because of a refactor in another repository rather than
# because of an edit here, and a machine cannot tell a live path from one quoted as obsolete.
report:
	$(PY) scripts/bdd_report.py --docs $(DOCS) --repos $(REPOS)
	$(PY) scripts/code_anchors.py --docs $(DOCS) --repos $(REPOS)

fix:
	$(PY) scripts/backlog_index.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --fix --docs $(DOCS)
