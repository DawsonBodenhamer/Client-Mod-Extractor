@echo off
echo Checking Java installation...
java -version
if %errorlevel% neq 0 (
    echo.
    echo Error: Java is not installed or not in your system PATH.
    echo Note: Minecraft's built-in Java is not accessible to command-line scripts by default.
    echo.
    echo Action Required:
    echo 1. Hold CTRL and click this link to download Java: https://adoptium.net/
    echo 2. Run the downloaded installer.
    echo 3. Make sure to check the box that says "Add to PATH" during installation.
    echo 4. Restart this script.
    echo.
    pause
    exit /b
)
echo.
echo Starting Client Mod Extractor...
java ClientModExtractor.java --prompt-affirmation
echo.
pause