#!/usr/bin/env bash
# Build the debug APK and run JVM unit tests.
source "$(dirname "$0")/common.sh"
cd "$ROOT"
JAVA_HOME="$(java17)" ./gradlew :app:testDebugUnitTest :app:assembleDebug -q "$@"
ls -la app/build/outputs/apk/debug/app-debug.apk
