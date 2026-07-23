@echo off
setlocal

set "ROOT=%~dp0"
set "CLI_DIR=%ROOT%packages\cli"

if not defined WEB2BLUE_NODE (
  if exist "%ROOT%node\node.exe" (
    set "WEB2BLUE_NODE=%ROOT%node\node.exe"
    set "PATH=%ROOT%node;%PATH%"
  ) else (
    for /d %%D in ("%ROOT%node\*") do (
      if exist "%%~fD\node.exe" (
        set "WEB2BLUE_NODE=%%~fD\node.exe"
        set "PATH=%%~fD;%PATH%"
        goto node_ready
      )
    )
  )
)

:node_ready
if not exist "%CLI_DIR%\src\index.js" (
  echo [Web2Blue] CLI entry not found.
  exit /b 1
)

if defined WEB2BLUE_NODE (
  "%WEB2BLUE_NODE%" --version >nul 2>nul
  if errorlevel 1 (
    echo [Web2Blue] Bundled Node.js is invalid: %WEB2BLUE_NODE%
    exit /b 1
  )
) else (
  where node >nul 2>nul
  if errorlevel 1 (
    echo [Web2Blue] Node.js was not found. Please install Node.js 18+ first.
    echo [Web2Blue] Or run: powershell -ExecutionPolicy Bypass -File .\setup-portable.ps1
    exit /b 1
  )
  set "WEB2BLUE_NODE=node"
)

where npm >nul 2>nul
if errorlevel 1 (
  echo [Web2Blue] npm was not found. Please run setup-portable.ps1 or reinstall Node.js with npm enabled.
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
"%WEB2BLUE_NODE%" "%CLI_DIR%\src\index.js" %*
