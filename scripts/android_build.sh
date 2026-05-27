#!/bin/bash
# CinePhantom build script — sources Android SDK + JDK paths and runs assembleDebug
# Usage: bash scripts/android_build.sh [gradle args...]

set -e
export JAVA_HOME=/home/node/jdk17
export ANDROID_HOME=/home/node/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

cd "$(dirname "$0")/../CinePhantom"
./gradlew assembleDebug "$@"
