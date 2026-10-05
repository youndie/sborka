# What check.yaml's `prepare-tree` job hands the action as `prepare`: it writes down that it ran, and
# from where, so the job can tell a script that ran from one that was refused.
echo "ran $(pwd -P)" >> "$RUNNER_TEMP/prepared"
