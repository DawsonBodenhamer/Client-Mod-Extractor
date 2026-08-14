@echo off
setlocal
cd /d "%~dp0"

for /f "delims=" %%E in ('echo prompt $E^| cmd') do set "ESC=%%E"
set "ANSI_RESET=%ESC%[0m"
set "ANSI_RED=%ESC%[38;2;255;74;74m"
set "ANSI_GREEN=%ESC%[38;2;0;230;118m"
set "ANSI_YELLOW=%ESC%[38;2;255;215;0m"
set "ANSI_CYAN=%ESC%[38;2;0;191;255m"

echo Checking Java installation...
where java >nul 2>&1
if errorlevel 1 goto java_missing

set "JAVA_VERSION="
for /f "tokens=3" %%V in ('call java -version 2^>^&1 ^| findstr /i "version"') do if not defined JAVA_VERSION set "JAVA_VERSION=%%~V"
if not defined JAVA_VERSION goto java_unknown

for /f "tokens=1,2 delims=." %%A in ("%JAVA_VERSION%") do (
    set "JAVA_MAJOR=%%A"
    if "%%A"=="1" set "JAVA_MAJOR=%%B"
)

if not defined JAVA_MAJOR goto java_unknown
for /f "delims=0123456789" %%A in ("%JAVA_MAJOR%") do goto java_unknown
if %JAVA_MAJOR% LSS 11 goto java_outdated

call java -version
echo.
call java ClientModExtractor.java --prompt-affirmation
set "EXTRACTOR_EXIT=%ERRORLEVEL%"
echo.
pause
exit /b %EXTRACTOR_EXIT%

:java_missing
set "JAVA_DETECTED=Detected: Java was not found"
goto java_help

:java_outdated
set "JAVA_DETECTED=Detected: Java %JAVA_VERSION%"
goto java_help

:java_unknown
set "JAVA_DETECTED=Detected: Java version could not be determined"
goto java_help

:java_help
echo.
echo %ANSI_CYAN%==========================================================%ANSI_RESET%
echo %ANSI_RED%  JAVA UPDATE REQUIRED%ANSI_RESET%
echo %ANSI_CYAN%==========================================================%ANSI_RESET%
echo.
echo %ANSI_YELLOW%%JAVA_DETECTED%%ANSI_RESET%
echo %ANSI_YELLOW%Required: Java 11 or newer%ANSI_RESET%
echo.
echo %ANSI_GREEN%How to fix this:%ANSI_RESET%
echo.
echo   1. Open this page:
echo      %ANSI_CYAN%https://adoptium.net/temurin/releases/%ANSI_RESET%
echo.
echo   2. Find the Windows download for the latest LTS version.
echo.
echo   3. Choose:
echo      %ANSI_CYAN%Package:      JDK%ANSI_RESET%
echo      %ANSI_CYAN%Installer:    MSI%ANSI_RESET%
echo      %ANSI_CYAN%Architecture: x64%ANSI_RESET%
echo.
echo   4. Open the downloaded installer.
echo.
echo   5. Keep %ANSI_CYAN%"Add to PATH"%ANSI_RESET% enabled.
echo.
echo   6. Click Next, Install, and Finish.
echo.
echo   7. Close this window, then run:
echo      %ANSI_CYAN%Run-Extractor-Windows.bat%ANSI_RESET%
echo.
pause
exit /b 1
