#!/usr/bin/env bash
# Builds launcher/build/firehome.apk with the bare SDK tools (no Gradle).
# Needs JDK 17 and the Android SDK packages "build-tools;34.0.0" and "platforms;android-30".
# Set ANDROID_HOME / JAVA_HOME, or put them in launcher/local.env (ignored by git).
set -euo pipefail
cd "$(dirname "$0")"
[ -f local.env ] && . ./local.env

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
for s in "$SDK" "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
  [ -n "$s" ] && [ -d "$s/build-tools/34.0.0" ] && { SDK=$s; break; }
done
[ -d "$SDK/build-tools/34.0.0" ] || { echo "SDK Android (build-tools 34.0.0) introuvable : définis ANDROID_HOME." >&2; exit 1; }
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(ls -d "/c/Program Files/Microsoft/jdk-17"* 2>/dev/null | head -1 || true)
fi
# d8 and apksigner are Java programs too, and look for JAVA_HOME themselves.
[ -n "$JAVA_HOME" ] && export JAVA_HOME
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-30/android.jar"
# Windows ships these tools as .exe / .bat.
tool() { for t in "$BT/$1" "$BT/$1.exe" "$BT/$1.bat"; do [ -f "$t" ] && { echo "$t"; return; }; done; echo "$BT/$1"; }
B=build

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$(tool aapt2)" compile --dir res -o "$B/res.zip"
"$(tool aapt2)" link -o "$B/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  --min-sdk-version 30 --target-sdk-version 30 "$B/res.zip"
"$JAVAC" -encoding UTF-8 -nowarn -Xlint:-options --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$(tool d8)" --min-api 30 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
# classes.dex and the wallpapers go in with aapt: no zip binary in Git Bash, and aapt2 -A on Windows
# stores asset paths with backslashes, which Android can't find.
(cd "$B/dex" && "$(tool aapt)" add -k ../unsigned.apk classes.dex >/dev/null)
"$(tool aapt)" add -0 jpg "$B/unsigned.apk" $(find assets -type f) >/dev/null
"$(tool zipalign)" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
# Your own signing key, made once. Keep it: updates must be signed with the same key.
[ -f debug.keystore ] || "$KEYTOOL" -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias firehome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=firehome" >/dev/null 2>&1
"$(tool apksigner)" sign --ks debug.keystore --ks-pass pass:android --out "$B/firehome.apk" "$B/aligned.apk"
rm -f "$B/unsigned.apk" "$B/aligned.apk"
echo "APK : $B/firehome.apk ($(wc -c < "$B/firehome.apk") octets)"
