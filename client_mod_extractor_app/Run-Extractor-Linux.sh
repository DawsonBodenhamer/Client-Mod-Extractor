#!/bin/bash
cd "$(dirname "$0")"

echo "[Linux Launcher] Checking Java installation..."
if ! command -v java &> /dev/null; then
    echo
    echo "Error: Java is not installed or not in your system PATH."
    echo "Note: Minecraft's built-in Java is not accessible to command-line scripts by default."
    echo
    echo "Action Required:"
    echo "Please install a Java Development Kit (JDK) 11 or newer via your package manager."
    echo "Examples:"
    echo "  Debian/Ubuntu: sudo apt install default-jdk"
    echo "  Arch Linux:    sudo pacman -S jdk-openjdk"
    echo "  macOS:         brew install openjdk"
    echo
    read -p "Press Enter to exit..."
    exit 1
fi

java -version
echo

java ClientModExtractor.java --prompt-affirmation

if [ $? -ne 0 ]; then
    echo
    echo "!! A fatal runtime error occurred !!"
    echo
    read -p "Press Enter to exit..."
else
    echo
    read -p "Press Enter to exit..."
fi