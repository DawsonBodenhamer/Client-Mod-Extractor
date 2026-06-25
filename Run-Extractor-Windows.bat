@echo off
cd /d "%~dp0"

echo [Windows Launcher] Starting Mod Extractor...

:: Run the Java script natively
java ModExtractor.java

if %ERRORLEVEL% neq 0 (
    echo.
    echo !! A fatal runtime error occurred !!
    echo.
    pause
) else (
    echo.
    echo Press any key to exit...
    pause >nul
)
