@echo off
setlocal

set "ROOT=%~dp0"
set "CLI_DIR=%ROOT%packages\cli"

if not exist "%CLI_DIR%\src\index.js" (
  echo [Web2Blue] CLI entry not found.
  exit /b 1
)

where node >nul 2>nul
if errorlevel 1 (
  echo [Web2Blue] Node.js was not found. Please install Node.js 18+ first.
  echo [Web2Blue] Download: https://nodejs.org/
  exit /b 1
)

where npm >nul 2>nul
if errorlevel 1 (
  echo [Web2Blue] npm was not found. Please reinstall Node.js with npm enabled.
  exit /b 1
)

if not exist "%CLI_DIR%\node_modules" (
  echo [Web2Blue] First run detected. Installing CLI dependencies...
  call npm ci --prefix "%CLI_DIR%"
  if errorlevel 1 (
    echo [Web2Blue] Failed to install CLI dependencies.
    exit /b 1
  )
)

if not defined JAVA_HOME (
  for /d %%D in ("%ROOT%jdk17\*") do (
    if exist "%%~fD\bin\java.exe" (
      set "JAVA_HOME=%%~fD"
      goto java_ready
    )
  )
)

:java_ready
node "%CLI_DIR%\src\index.js" %*
