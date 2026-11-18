#!/bin/sh
# Build + install without Gradle: aapt2 -> javac -> d8 -> sign.
set -e
cd "$(dirname "$0")"
SDK=~/Library/Android/sdk; BT=$SDK/build-tools/35.0.0; JAR=$SDK/platforms/android-34/android.jar
KS=~/.android/debug.keystore
[ -f $KS ] || keytool -genkeypair -keystore $KS -storepass android -keypass android -alias androiddebugkey \
  -dname "CN=Android Debug" -keyalg RSA -validity 10000 >/dev/null
rm -rf out && mkdir -p out/classes
$BT/aapt2 compile --dir res -o out/res.zip
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml -R out/res.zip --java out/gen --auto-add-overlay -o out/base.apk
javac --release 8 -cp $JAR -d out/classes $(find src out/gen -name '*.java')
$BT/d8 --lib $JAR --min-api 26 --output out $(find out/classes -name '*.class')
(cd out && zip -q base.apk classes.dex)
$BT/zipalign -f 4 out/base.apk out/aligned.apk
$BT/apksigner sign --ks $KS --ks-pass pass:android --out purifier.apk out/aligned.apk
ls -lh purifier.apk
[ "$1" = install ] && adb install -r purifier.apk && adb shell am start -n lite.purifier/.MainActivity
