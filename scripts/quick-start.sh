#!/usr/bin/env bash
# FishIT-Mapper Quick Start Guide for Codex Browser
# Interactive helper script that guides users through setup

set -euo pipefail

# --- Colors ---
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# --- Change to repo root ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

clear
cat << "EOF"
╔════════════════════════════════════════════════════════╗
║                                                        ║
║           FishIT-Mapper Codex Browser                  ║
║              Quick Start Guide                         ║
║                                                        ║
╚════════════════════════════════════════════════════════╝
EOF

echo ""
echo -e "${CYAN}Welcome to FishIT-Mapper!${NC}"
echo ""
echo "This script will guide you through the setup process."
echo ""

# --- Check if already set up ---
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT:-/nonexistent}" ] && command -v java >/dev/null 2>&1; then
    echo -e "${GREEN}✓ Environment appears to be already configured!${NC}"
    echo ""
    echo "What would you like to do?"
    echo ""
    echo "  1) Run environment validation"
    echo "  2) Run maintenance (clean + regenerate contract)"
    echo "  3) Build Android APK"
    echo "  4) Run full setup anyway"
    echo "  5) Show available commands"
    echo "  6) Exit"
    echo ""
    read -p "Enter your choice (1-6): " choice
    
    case $choice in
        1)
            echo ""
            echo -e "${BLUE}Running environment validation...${NC}"
            echo ""
            ./scripts/validate-env.sh
            ;;
        2)
            echo ""
            echo -e "${BLUE}Running maintenance...${NC}"
            echo ""
            ./scripts/maintenance.sh
            ;;
        3)
            echo ""
            echo -e "${BLUE}Building Android APK...${NC}"
            echo ""
            ./gradlew :androidApp:assembleDebug
            echo ""
            echo -e "${GREEN}✓ Build complete!${NC}"
            echo "APK: androidApp/build/outputs/apk/debug/androidApp-debug.apk"
            ;;
        4)
            echo ""
            echo -e "${YELLOW}Running full setup...${NC}"
            echo ""
            ./scripts/codex-setup.sh
            ;;
        5)
            cat << 'EOF'

Available Commands:
===================

Setup & Validation:
  ./scripts/validate-env.sh              - Check environment status
  ./scripts/codex-setup.sh               - Full setup (Android SDK + build)
  ./scripts/maintenance.sh               - Quick maintenance

Development:
  ./gradlew :shared:contract:generateFishitContract
                                         - Regenerate contract from schema
  ./gradlew :androidApp:compileDebugKotlin
                                         - Quick compile check
  ./gradlew test                         - Run unit tests
  ./gradlew :androidApp:assembleDebug    - Build debug APK
  ./gradlew build                        - Full build

Gradle:
  ./gradlew clean                        - Clean build outputs
  ./gradlew tasks                        - List all available tasks
  ./gradlew --version                    - Show Gradle version

Android:
  ./gradlew :androidApp:installDebug     - Install APK on device
  adb devices                            - List connected devices

Help:
  ./scripts/quick-start.sh               - This guide

EOF
            ;;
        6)
            echo "Goodbye!"
            exit 0
            ;;
        *)
            echo "Invalid choice"
            exit 1
            ;;
    esac
else
    echo -e "${YELLOW}Environment is not yet configured.${NC}"
    echo ""
    echo "Let's check what needs to be done..."
    echo ""
    
    # Quick validation
    ./scripts/validate-env.sh
    
    echo ""
    echo "Would you like to run the full setup now?"
    echo ""
    echo "This will:"
    echo "  - Install Android SDK (if needed)"
    echo "  - Download SDK components (~2-3 GB)"
    echo "  - Generate contract code"
    echo "  - Build Android debug APK"
    echo ""
    echo "Estimated time: 5-10 minutes"
    echo ""
    read -p "Proceed with setup? (y/n): " proceed
    
    if [ "$proceed" = "y" ] || [ "$proceed" = "Y" ]; then
        echo ""
        echo -e "${BLUE}Starting full setup...${NC}"
        echo ""
        ./scripts/codex-setup.sh
    else
        echo ""
        echo "Setup cancelled. You can run it later with:"
        echo "  ./scripts/codex-setup.sh"
        echo ""
        echo "For more information, see:"
        echo "  scripts/README.md"
        echo ""
    fi
fi

echo ""
