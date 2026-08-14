#!/bin/bash
cd "$(dirname "$0")" || exit 1

ANSI_RESET=$'\033[0m'
ANSI_RED=$'\033[38;2;255;74;74m'
ANSI_GREEN=$'\033[38;2;0;230;118m'
ANSI_YELLOW=$'\033[38;2;255;215;0m'
ANSI_CYAN=$'\033[38;2;0;191;255m'

show_java_help() {
    echo
    echo "${ANSI_CYAN}==========================================================${ANSI_RESET}"
    echo "${ANSI_RED}  JAVA UPDATE REQUIRED${ANSI_RESET}"
    echo "${ANSI_CYAN}==========================================================${ANSI_RESET}"
    echo
    echo "${ANSI_YELLOW}${JAVA_DETECTED}${ANSI_RESET}"
    echo "${ANSI_YELLOW}Required: Java 11 or newer${ANSI_RESET}"
    echo
    echo "${ANSI_GREEN}How to fix this:${ANSI_RESET}"
    echo

    case "$(uname -s)" in
        Darwin)
            echo "  1. Open this page:"
            echo "     ${ANSI_CYAN}https://adoptium.net/temurin/releases/${ANSI_RESET}"
            echo
            echo "  2. Select macOS and the latest LTS JDK."
            echo
            echo "  3. Download and open the ${ANSI_CYAN}PKG${ANSI_RESET} installer shown for your Mac."
            echo
            echo "  4. Complete the installation."
            echo
            echo "  5. After installation, run:"
            echo "     ${ANSI_CYAN}Run-Extractor-Linux.sh${ANSI_RESET}"
            ;;
        *)
            echo "  1. Find your Linux version below."
            echo
            echo "  2. Copy and paste its command into a terminal:"
            echo "     ${ANSI_CYAN}Ubuntu or Debian: sudo apt install default-jdk${ANSI_RESET}"
            echo "     ${ANSI_CYAN}Fedora:           sudo dnf install java-latest-openjdk-devel${ANSI_RESET}"
            echo "     ${ANSI_CYAN}Arch Linux:       sudo pacman -S jdk-openjdk${ANSI_RESET}"
            echo
            echo "  3. Approve the installation if asked."
            echo
            echo "  4. After installation, run:"
            echo "     ${ANSI_CYAN}Run-Extractor-Linux.sh${ANSI_RESET}"
            ;;
    esac
    echo
}

echo "Checking Java installation..."
if ! command -v java >/dev/null 2>&1; then
    JAVA_DETECTED="Detected: Java was not found"
    show_java_help
    read -r -p "Press Enter to exit..."
    exit 1
fi

JAVA_VERSION_OUTPUT=$(java -version 2>&1)
JAVA_VERSION=$(printf '%s\n' "$JAVA_VERSION_OUTPUT" | awk -F'"' '/version/ { print $2; exit }')

case "$JAVA_VERSION" in
    1.*) JAVA_MAJOR=$(printf '%s\n' "$JAVA_VERSION" | cut -d. -f2) ;;
    *) JAVA_MAJOR=${JAVA_VERSION%%.*} ;;
esac

case "$JAVA_MAJOR" in
    ''|*[!0-9]*)
        JAVA_DETECTED="Detected: Java version could not be determined"
        show_java_help
        read -r -p "Press Enter to exit..."
        exit 1
        ;;
esac

if [ "$JAVA_MAJOR" -lt 11 ]; then
    JAVA_DETECTED="Detected: Java $JAVA_VERSION"
    show_java_help
    read -r -p "Press Enter to exit..."
    exit 1
fi

printf '%s\n' "$JAVA_VERSION_OUTPUT"
echo

java ClientModExtractor.java --prompt-affirmation
EXTRACTOR_EXIT=$?

if [ "$EXTRACTOR_EXIT" -ne 0 ]; then
    echo
    echo "!! A fatal runtime error occurred !!"
fi

echo
read -r -p "Press Enter to exit..."
exit "$EXTRACTOR_EXIT"
