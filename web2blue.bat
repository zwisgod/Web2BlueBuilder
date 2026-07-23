@echo off
setlocal

set "ROOT=%~dp0"

if not exist "%ROOT%packages\cli\src\index.js" (
  echo [Web2Blue] CLI entry not found.
  exit /b 1
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
node "%ROOT%packages\cli\src\index.js" %*
