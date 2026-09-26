@echo off
REM ============================================================
REM  Build Debug APK (DeskFlow)
REM  Output: app/build/outputs/apk/debug/
REM  Note: Debug build needs no signing and can be installed for testing.
REM        Run unit tests first: build_test.bat
REM ============================================================
setlocal
cd /d "%~dp0"

REM Prefer mise-managed JDK 21 if available (the toolchain required by AGP/Kotlin).
REM This avoids Gradle trying to download JDK 21 from foojay.io (unreachable here).
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
REM Tell Gradle to look for toolchains in the local JDK install dir as well.
if defined JAVA_HOME set "GRADLE_OPTS=-Dorg.gradle.java.installations.paths=%JAVA_HOME% -Dorg.gradle.java.home=%JAVA_HOME%"

echo [INFO] Building Debug APK ...
call gradlew.bat :app:clean :app:assembleDebug --no-configuration-cache
if errorlevel 1 (
    echo [ERROR] Debug build failed. Check the output above.
    pause
    exit /b 1
)

echo.
echo [OK] Debug build finished. Output directory:
echo      app/build/outputs/apk/debug/
echo.
REM Do not block in non-interactive environments (e.g. CI)
if not defined CI pause
endlocal
