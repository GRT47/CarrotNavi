@echo off
echo =========================================
echo CarrotNavi v1.1.5 Build and Git Push Tool
echo =========================================
cd /d g:\github\CarrotNavi

echo [1/3] Building Debug APK...
call gradlew.bat assembleDebug
if %ERRORLEVEL% neq 0 (
    echo [Error] Build failed!
    pause
    exit /b %ERRORLEVEL%
)

echo [2/3] Copying APK to apk\ folder...
if not exist apk mkdir apk
copy /Y app\build\outputs\apk\debug\app-debug.apk apk\CarrotNavi_v1.1.5.apk

echo [3/3] Creating Git Branch and Pushing...
git add app\build.gradle.kts app\src\main\java\com\example\carrotnavi\UdpSenderService.kt deploy_1_1_5.bat
git commit -m "Bump version to 1.1.5 (Add roadcate=8 logic for speed bumps)"
git branch 1.1.5
git checkout 1.1.5
git push -u origin 1.1.5

echo =========================================
echo Done! CarrotNavi_v1.1.5.apk has been generated and git branch has been pushed.
echo =========================================
pause
