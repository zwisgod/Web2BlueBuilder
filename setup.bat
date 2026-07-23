@echo off
setlocal

set "ROOT=%~dp0"
set "CLI_DIR=%ROOT%packages\cli"

echo [Web2Blue] Checking Node.js...
where node >nul 2>nul
if errorlevel 1 (
  echo [Web2Blue] Node.js was not found. Please install Node.js 18+ first.
  echo [Web2Blue] Download: https://nodejs.org/
  exit /b 1
)

node --version

echo [Web2Blue] Installing CLI dependencies...
call npm ci --prefix "%CLI_DIR%"
if errorlevel 1 (
  echo [Web2Blue] npm install failed.
  exit /b 1
)

echo [Web2Blue] Checking Android SDK...
if defined ANDROID_SDK_ROOT (
  echo [Web2Blue] ANDROID_SDK_ROOT=%ANDROID_SDK_ROOT%
) else if defined ANDROID_HOME (
  echo [Web2Blue] ANDROID_HOME=%ANDROID_HOME%
) else (
  echo [Web2Blue] ANDROID_SDK_ROOT/ANDROID_HOME is not set.
  echo [Web2Blue] If Android Studio is installed in the default location, the CLI will try to detect it automatically.
)

echo [Web2Blue] Checking JDK...
if defined JAVA_HOME (
  echo [Web2Blue] JAVA_HOME=%JAVA_HOME%
) else (
  echo [Web2Blue] JAVA_HOME is not set. Please install JDK 17 if Gradle build fails.
)

echo [Web2Blue] Setup finished.
