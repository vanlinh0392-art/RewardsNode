$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$AAPT2 = "C:\Users\UM880 Pro\AppData\Local\Android\Sdk\build-tools\36.1.0\aapt2.exe"
$ANDROID_JAR = "C:\Users\UM880 Pro\AppData\Local\Android\Sdk\platforms\android-36\android.jar"
$KOTLINC = "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat"
$D8 = "C:\Users\UM880 Pro\AppData\Local\Android\Sdk\build-tools\36.1.0\d8.bat"
$APKSIGNER = "C:\Users\UM880 Pro\AppData\Local\Android\Sdk\build-tools\36.1.0\apksigner.bat"
$KOTLIN_DIR = "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\lib"
$STDLIB_JAR = "$KOTLIN_DIR\kotlin-stdlib.jar"
$COROUTINES_JAR = "$KOTLIN_DIR\kotlinx-coroutines-core-jvm.jar"

Write-Host "=== Step 1: AAPT2 Compile & Link ==="
if (Test-Path "compiled_res.zip") { Remove-Item "compiled_res.zip" -Force }
if (Test-Path "base.apk") { Remove-Item "base.apk" -Force }
if (!(Test-Path "gen")) { New-Item -ItemType Directory -Path "gen" | Out-Null }

& $AAPT2 compile --dir app/src/main/res -o compiled_res.zip
if ($LASTEXITCODE -ne 0) { throw "AAPT2 compile failed with exit code $LASTEXITCODE" }

& $AAPT2 link -I $ANDROID_JAR --manifest app/src/main/AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 34 --java gen -o base.apk compiled_res.zip
if ($LASTEXITCODE -ne 0) { throw "AAPT2 link failed with exit code $LASTEXITCODE" }
Write-Host "AAPT2 successfully generated base.apk and gen/ R.java"

Write-Host "=== Step 2: Kotlinc Compilation ==="
if (Test-Path "classes_out") { Remove-Item "classes_out" -Recurse -Force }
New-Item -ItemType Directory -Path "classes_out" | Out-Null

$sourceFiles = [string[]](Get-ChildItem -Path "app\src\main\java", "gen" -Recurse -Include "*.kt", "*.java" | Select-Object -ExpandProperty FullName)
$sourcesFile = "$PSScriptRoot\sources.txt"
[System.IO.File]::WriteAllLines($sourcesFile, $sourceFiles, (New-Object System.Text.UTF8Encoding($false)))

$classpath = "$ANDROID_JAR;$STDLIB_JAR;$COROUTINES_JAR"
& $KOTLINC -cp $classpath -d classes_out "@$sourcesFile"
if ($LASTEXITCODE -ne 0) { throw "Kotlinc compilation failed with exit code $LASTEXITCODE" }
Write-Host "Kotlinc successfully compiled classes to classes_out"

Write-Host "=== Step 3: D8 Dexing ==="
if (Test-Path "dex_out") { Remove-Item "dex_out" -Recurse -Force }
New-Item -ItemType Directory -Path "dex_out" | Out-Null

$classFiles = [string[]](Get-ChildItem -Path "classes_out" -Recurse -Filter "*.class" | Select-Object -ExpandProperty FullName)
$d8SourcesFile = "$PSScriptRoot\d8_sources.txt"
[System.IO.File]::WriteAllLines($d8SourcesFile, $classFiles, (New-Object System.Text.UTF8Encoding($false)))

& $D8 --min-api 26 --lib $ANDROID_JAR --output dex_out "@$d8SourcesFile" $STDLIB_JAR $COROUTINES_JAR
if ($LASTEXITCODE -ne 0) { throw "D8 dexing failed with exit code $LASTEXITCODE" }
Write-Host "D8 successfully generated classes.dex in dex_out"

Write-Host "=== Step 4: Packaging and Signing APK ==="
Copy-Item "base.apk" -Destination "unaligned.apk" -Force

# Insert classes.dex into unaligned.apk
if (Get-Command 7z -ErrorAction SilentlyContinue) {
    & 7z a -tzip unaligned.apk ".\dex_out\classes.dex" | Out-Null
} elseif (Get-Command jar -ErrorAction SilentlyContinue) {
    & jar -uf unaligned.apk -C dex_out classes.dex
} else {
    throw "Neither 7z nor jar found to update unaligned.apk"
}

# Keystore check
$keystore = "$PSScriptRoot\debug.keystore"
if (!(Test-Path $keystore)) {
    $userKeystore = "$env:USERPROFILE\.android\debug.keystore"
    if (Test-Path $userKeystore) {
        $keystore = $userKeystore
    } else {
        Write-Host "Generating debug.keystore..."
        & keytool -genkey -v -keystore $keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
    }
}

if (Test-Path "RewardsNode.apk") { Remove-Item "RewardsNode.apk" -Force }
& $APKSIGNER sign --ks $keystore --ks-pass pass:android --key-pass pass:android --out RewardsNode.apk unaligned.apk
if ($LASTEXITCODE -ne 0) { throw "Apksigner sign failed with exit code $LASTEXITCODE" }

& $APKSIGNER verify -v RewardsNode.apk
if ($LASTEXITCODE -ne 0) { throw "Apksigner verify failed with exit code $LASTEXITCODE" }

Write-Host "=========================================="
Write-Host " BUILD SUCCESSFUL! " -ForegroundColor Green
Write-Host " APK Output: $PSScriptRoot\RewardsNode.apk"
$apkInfo = Get-Item "RewardsNode.apk"
Write-Host " Size: $([math]::Round($apkInfo.Length / 1MB, 2)) MB ($($apkInfo.Length) bytes)"
Write-Host "=========================================="
