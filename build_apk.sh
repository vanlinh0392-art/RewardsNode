#!/bin/bash
set -e

export PATH="/data/user/0/com.agy/files/usr/bin:$PATH"
export HOME="/data/user/0/com.agy/files"

echo "=== Step 1: AAPT2 Compile & Link ==="
aapt2 compile --dir app/src/main/res -o compiled_res.zip
aapt2 link -I /data/user/0/com.agy/files/android-sdk/platforms/android-36/android.jar --manifest app/src/main/AndroidManifest.xml --java gen/ -o base.apk compiled_res.zip

echo "=== Step 2: Kotlinc Compilation ==="
rm -rf classes_out && mkdir -p classes_out
kotlinc -cp "/data/user/0/com.agy/files/android-sdk/platforms/android-36/android.jar:/data/user/0/com.agy/files/usr/opt/kotlin/lib/kotlin-stdlib.jar:/data/user/0/com.agy/files/usr/opt/kotlin/lib/kotlinx-coroutines-core-jvm.jar" -d classes_out/ $(find app/src/main/java gen -name "*.kt" -o -name "*.java")

echo "=== Step 3: D8 Dexing ==="
rm -rf dex_out && mkdir -p dex_out
d8 --min-api 26 --lib /data/user/0/com.agy/files/android-sdk/platforms/android-36/android.jar --output dex_out/ $(find classes_out -name "*.class") /data/user/0/com.agy/files/usr/opt/kotlin/lib/kotlin-stdlib.jar /data/user/0/com.agy/files/usr/opt/kotlin/lib/kotlinx-coroutines-core-jvm.jar

echo "=== Step 4: Packaging and Signing APK ==="
cp base.apk unaligned.apk
zip -j unaligned.apk dex_out/classes.dex
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out RewardsNode.apk unaligned.apk
apksigner verify -v RewardsNode.apk
echo "BUILD SUCCESSFUL!"
ls -lh RewardsNode.apk
