@echo off
REM ============================================================
REM  Run JVM unit tests (DeskFlow)
REM  Report: app/build/reports/tests/testDebugUnitTest/index.html
REM ============================================================
setlocal
cd /d "%~dp0"

REM Prefer a JDK 21 toolchain; never let an inherited system JAVA_HOME (e.g. JDK 11) win.
set "JAVA_HOME_PREV=%JAVA_HOME%"
set "JAVA_HOME="
for /f "tokens=*" %%i in ('mise where java@21 2^>nul') do set "JAVA_HOME=%%i"
REM mise only resolves when MISE_DATA_DIR is set (bash profile sets it, a fresh cmd
REM does not), so fall back to the usual mise data roots, 21.x installs only.
if not defined JAVA_HOME (
    for %%r in ("%MISE_DATA_DIR%" "%USERPROFILE%\.local\share\mise" "D:\dev\mise\data") do (
        if not defined JAVA_HOME (
            for /d %%d in ("%%~r\installs\java\21*") do (
                if exist "%%d\bin\java.exe" set "JAVA_HOME=%%d"
            )
        )
    )
)
if not defined JAVA_HOME set "JAVA_HOME=%JAVA_HOME_PREV%"
if defined JAVA_HOME (
    echo [INFO] JAVA_HOME = %JAVA_HOME%
) else (
    echo [WARN] No JDK 21 detected via mise, falling back to current JAVA_HOME/PATH (JDK 21 required)
)
if defined JAVA_HOME set "GRADLE_OPTS=-Dorg.gradle.java.installations.paths=%JAVA_HOME% -Dorg.gradle.java.home=%JAVA_HOME%"

echo [INFO] Running unit tests ...
call gradlew.bat :app:testDebugUnitTest --console=plain
if errorlevel 1 (
    echo [ERROR] Tests failed. See app/build/reports/tests/testDebugUnitTest/index.html
    pause
    exit /b 1
)

echo [OK] All tests passed.
if not defined CI pause
endlocal
