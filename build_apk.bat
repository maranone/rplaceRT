@echo off
setlocal
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
cd /d "%ROOT%"
set "STUDIO=C:\Program Files\Android\Android Studio"
set "JAVA_HOME=%STUDIO%\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
rem Reuse the shared Gradle 8.7 install from questretrodepth instead of duplicating 271MB
set "GRADLE_HOME=C:\Users\waz-tower\Documents\questretrodepth\.tools\gradle-8.7"
set "GRADLE_CMD=%GRADLE_HOME%\bin\gradle.bat"
rem Release variant (CMake RelWithDebInfo -- real optimizations, unlike the
rem debug variant's unoptimized default) is the day-to-day build now; it's
rem debug-signed via app/build.gradle's release.signingConfig so it can still
rem be adb-installed directly. Package has no ".debug" suffix, so this
rem replaces (not coexists with) any previously-installed debug build.
set "APK_SRC=%ROOT%\app\build\outputs\apk\release\app-release.apk"
set "APK_DST_DIR=%ROOT%\apk_out"
set "APK_DST=%APK_DST_DIR%\rplaceRT-release.apk"
set "PACKAGE_NAME=com.maranone.rplacert"
set "ACTIVITY=com.maranone.rplacert.QuestVrActivity"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Android Studio JBR not found: "%JAVA_HOME%\bin\java.exe"
  exit /b 1
)

if not exist "%SDK%" (
  echo Android SDK not found: "%SDK%"
  exit /b 1
)

if not exist "%GRADLE_CMD%" (
  echo Gradle 8.7 not found at "%GRADLE_CMD%"
  exit /b 1
)

echo Using JAVA_HOME=%JAVA_HOME%
echo Using SDK=%SDK%
echo Using Gradle=%GRADLE_CMD%

call "%GRADLE_CMD%" assembleRelease
if errorlevel 1 exit /b %ERRORLEVEL%

if not exist "%APK_SRC%" (
  echo APK not found at "%APK_SRC%"
  exit /b 1
)

mkdir "%APK_DST_DIR%" 2>nul
copy /y "%APK_SRC%" "%APK_DST%" >nul

echo.
echo APK copied to:
echo   %APK_DST%

if not exist "%ADB%" (
  echo.
  echo adb not found. APK built, but install was skipped.
  exit /b 0
)

echo.
echo Checking for connected Quest devices...
"%ADB%" start-server >nul 2>nul
set "DEVICE_FOUND="
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices') do (
  if "%%B"=="device" set "DEVICE_FOUND=1"
)

if not defined DEVICE_FOUND (
  echo No Quest device detected over adb. APK built, but install was skipped.
  echo   ^(USB: check cable/authorization. WiFi: run adb_connect_wifi.bat first.^)
  exit /b 0
)

echo Installing APK on connected device...
"%ADB%" install -r "%APK_DST%"
if errorlevel 1 (
  echo adb install failed.
  exit /b 1
)

echo.
echo Installed package:
echo   %PACKAGE_NAME%

echo.
echo Launching on device...
"%ADB%" shell am start -n %PACKAGE_NAME%/%ACTIVITY%

exit /b 0
