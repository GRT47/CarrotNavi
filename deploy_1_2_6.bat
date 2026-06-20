@echo off
echo =========================================
echo CarrotNavi v1.2.6 Build and Git Push Tool
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
copy /Y app\build\outputs\apk\debug\app-debug.apk apk\CarrotNavi_v1.2.6.apk

echo [3/3] Creating Git Branch and Pushing...
git checkout master
git add -A
git commit -m "Bump version to 1.2.6 (Enhance widget corner touch recognition area evenly)"
git checkout -b 1.2.6
git push -u origin 1.2.6

echo =========================================
echo Done! CarrotNavi_v1.2.6.apk has been generated and git branch has been pushed.
echo =========================================
pause
