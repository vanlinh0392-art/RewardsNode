@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

set "AAPT2=C:\Users\UM880 Pro\AppData\Local\Android\Sdk\build-tools\36.1.0\aapt2.exe"
set "ANDROID_JAR=C:\Users\UM880 Pro\AppData\Local\Android\Sdk\platforms\android-36\android.jar"
set "KOTLINC=C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat"
set "D8=C:\Users\UM880 Pro\AppData\Local\Android\Sdk\build-tools\36.1.0\d8.bat"
set "APKSIGNER=C:\Users\UM880 Pro\AppData\Local\Android\Sdk\build-tools\36.1.0\apksigner.bat"
set "KOTLIN_DIR=C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\lib"
set "STDLIB_JAR=%KOTLIN_DIR%\kotlin-stdlib.jar"
set "COROUTINES_JAR=%KOTLIN_DIR%\kotlinx-coroutines-core-jvm.jar"

echo === Step 1: AAPT2 Compile ^& Link ===
if exist compiled_res.zip del /f /q compiled_res.zip
if exist base.apk del /f /q base.apk
if not exist gen mkdir gen

"%AAPT2%" compile --dir app/src/main/res -o compiled_res.zip
if %ERRORLEVEL% neq 0 (
    echo [ERROR] AAPT2 compile failed with exit code %ERRORLEVEL%.
    exit /b %ERRORLEVEL%
)

"%AAPT2%" link -I "%ANDROID_JAR%" --manifest app/src/main/AndroidManifest.xml --java gen -o base.apk compiled_res.zip
if %ERRORLEVEL% neq 0 (
    echo [ERROR] AAPT2 link failed with exit code %ERRORLEVEL%.
    exit /b %ERRORLEVEL%
)
echo [OK] AAPT2 successfully generated base.apk and gen/ R.java

echo === Step 2: Kotlinc Compilation ===
if exist classes_out rmdir /s /q classes_out
mkdir classes_out

powershell -NoProfile -Command "[string[]]$files = (Get-ChildItem -Path 'app\src\main\java', 'gen' -Recurse -Include '*.kt', '*.java' | Select-Object -ExpandProperty FullName); [System.IO.File]::WriteAllLines('sources.txt', $files, (New-Object System.Text.UTF8Encoding($false)))"

set "CLASSPATH=%ANDROID_JAR%;%STDLIB_JAR%;%COROUTINES_JAR%"
call "%KOTLINC%" -cp "%CLASSPATH%" -d classes_out "@sources.txt"
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Kotlinc compilation failed with exit code %ERRORLEVEL%.
    exit /b %ERRORLEVEL%
)
echo [OK] Kotlinc successfully compiled classes to classes_out

echo === Step 3: D8 Dexing ===
if exist dex_out rmdir /s /q dex_out
mkdir dex_out

powershell -NoProfile -Command "[string[]]$files = (Get-ChildItem -Path 'classes_out' -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName); [System.IO.File]::WriteAllLines('d8_sources.txt', $files, (New-Object System.Text.UTF8Encoding($false)))"

call "%D8%" --min-api 26 --lib "%ANDROID_JAR%" --output dex_out "@d8_sources.txt" "%STDLIB_JAR%" "%COROUTINES_JAR%"
if %ERRORLEVEL% neq 0 (
    echo [ERROR] D8 dexing failed with exit code %ERRORLEVEL%.
    exit /b %ERRORLEVEL%
)
echo [OK] D8 successfully generated classes.dex in dex_out

echo === Step 4: Packaging and Signing APK ===
copy /y base.apk unaligned.apk >nul

where 7z >nul 2>nul
if %ERRORLEVEL% equ 0 (
    7z a -tzip unaligned.apk .\dex_out\classes.dex >nul
) else (
    where jar >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        jar -uf unaligned.apk -C dex_out classes.dex
    ) else (
        echo [ERROR] Neither 7z nor jar found.
        exit /b 1
    )
)

set "KEYSTORE=%~dp0debug.keystore"
if not exist "%KEYSTORE%" (
    if exist "%USERPROFILE%\.android\debug.keystore" (
        set "KEYSTORE=%USERPROFILE%\.android\debug.keystore"
    ) else (
        echo Generating debug.keystore...
        keytool -genkey -v -keystore "%KEYSTORE%" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
    )
)

if exist RewardsNode.apk del /f /q RewardsNode.apk
call "%APKSIGNER%" sign --ks "%KEYSTORE%" --ks-pass pass:android --key-pass pass:android --out RewardsNode.apk unaligned.apk
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Apksigner sign failed with exit code %ERRORLEVEL%.
    exit /b %ERRORLEVEL%
)

call "%APKSIGNER%" verify -v RewardsNode.apk
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Apksigner verify failed with exit code %ERRORLEVEL%.
    exit /b %ERRORLEVEL%
)

echo ==========================================
echo  BUILD SUCCESSFUL!
echo  APK Output: %~dp0RewardsNode.apk
echo ==========================================
