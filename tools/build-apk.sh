#!/bin/sh
# Builds native libraries and the test APK: out/FactorioNative-<version>.apk
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
"$ROOT/tools/build-loader.sh"
"$ROOT/tools/build-fnsdl.sh"
ninja -C "$ROOT/work/sdl3-build" >/dev/null
J="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$J"
cp "$ROOT/out/libfnative.so" "$ROOT/out/libfnsdl.so" "$ROOT/work/sdl3-build/libSDL3.so" "$J/"
cd "$ROOT/android"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ANDROID_HOME=/root/android/sdk
./gradlew --no-daemon --offline -Pandroid.aapt2FromMavenOverride=/root/builds/aapt2-qemu/aapt2 :app:assembleDebug
V=$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle)
cp app/build/outputs/apk/debug/app-debug.apk "$ROOT/out/FactorioNative-$V.apk"
echo "APK: $ROOT/out/FactorioNative-$V.apk"
