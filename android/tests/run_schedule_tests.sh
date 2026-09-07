#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${FOCUS_JSON_JAR:?Set FOCUS_JSON_JAR to a JVM org.json jar}"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes"
javac -encoding UTF-8 --release 11 -cp "$FOCUS_JSON_JAR" -d "$OUT/classes" \
  android/app/src/main/java/dev/linjian/peek/ScheduleCore.java android/tests/ScheduleBehaviorTest.java
java -ea -cp "$OUT/classes:$FOCUS_JSON_JAR" dev.linjian.peek.ScheduleBehaviorTest
python3 android/tests/focus_mode_test_stubs.py "$OUT/stubs"
python3 android/tests/schedule_state_test_stubs.py "$OUT/stubs"
find "$OUT/stubs" -name '*.java' -print > "$OUT/sources.txt"
for name in ScheduleCore ScheduleState FocusMode FocusSessionCore TodoState TodoStateCore AppPrefs; do
  echo "android/app/src/main/java/dev/linjian/peek/$name.java" >> "$OUT/sources.txt"
done
echo android/tests/ScheduleStatePersistenceTest.java >> "$OUT/sources.txt"
javac -encoding UTF-8 --release 17 -cp "$FOCUS_JSON_JAR" -d "$OUT/classes" @"$OUT/sources.txt"
java -ea -cp "$OUT/classes:$FOCUS_JSON_JAR" dev.linjian.peek.ScheduleStatePersistenceTest
