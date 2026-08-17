#!/usr/bin/env bash
set -uo pipefail

report_dir="build/reports"
log_file="$report_dir/android-uat.log"
mkdir -p "$report_dir"

set +e
./gradlew :app:createDebugAndroidTestCoverageReport --stacktrace 2>&1 | tee "$log_file"
status=${PIPESTATUS[0]}
set -e

if (( status != 0 )); then
  message="$(tail -n 60 "$log_file")"
  message="${message//'%'/\%25}"
  message="${message//$'\r'/\%0D}"
  message="${message//$'\n'/\%0A}"
  printf '::error title=Android emulator UAT failed::%s\n' "$message"
fi

exit "$status"

