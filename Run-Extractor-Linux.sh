#!/bin/bash
cd "$(dirname "$0")"

echo "[Linux Launcher] Starting Mod Extractor..."

# Run the Java script natively
java ModExtractor.java

if [ $? -ne 0 ]; then
    echo
    echo "!! A fatal runtime error occurred !!"
    echo
    read -p "Press Enter to exit..."
else
    echo
    read -p "Press Enter to exit..."
fi
