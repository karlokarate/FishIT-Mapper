#!/usr/bin/env bash
# FishIT-Mapper Setup Script for ChatGPT Codex Browser
# This script sets up the complete development environment including Android SDK

set -euo pipefail

echo "=== FishIT-Mapper Codex Browser Setup ==="

# --- Color output for better readability ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# --- Change to repository root ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory: $(pwd)"

# --- Gradle wrapper permissions ---
log_info "Setting Gradle wrapper permissions..."
chmod +x ./gradlew || true

# --- Java Version Check (AGP 8.x requires JDK 17+) ---
log_info "Checking Java version..."
if command -v java >/dev/null 2>&1; then
    JAVA_VERSION=$(java -version 2>&1 | head -n 1)
    log_info "Java found: $JAVA_VERSION"
    
    # Extract major version number
    JAVA_MAJOR=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F. '{print $1}')
    if [ "$JAVA_MAJOR" -lt 17 ]; then
        log_error "Java 17 or higher is required for Android Gradle Plugin 8.x"
        log_error "Current version: $JAVA_VERSION"
        exit 1
    fi
else
    log_error "Java not found. Please install JDK 17 or higher."
    exit 1
fi

# --- Android SDK Setup ---
log_info "Setting up Android SDK environment..."
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

log_info "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
log_info "ANDROID_HOME=$ANDROID_HOME"

# --- Create SDK directory ---
if [ ! -d "$ANDROID_SDK_ROOT" ]; then
    log_info "Creating Android SDK directory..."
    mkdir -p "$ANDROID_SDK_ROOT"
    
    # Check if we need sudo
    if [ ! -w "$ANDROID_SDK_ROOT" ]; then
        log_warn "Directory $ANDROID_SDK_ROOT is not writable"
        log_warn "Attempting with sudo..."
        sudo mkdir -p "$ANDROID_SDK_ROOT"
        sudo chown -R "$(whoami)" "$ANDROID_SDK_ROOT"
    fi
fi

mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

# --- Install Android Command Line Tools ---
if ! command -v sdkmanager >/dev/null 2>&1; then
    log_info "Android SDK Manager not found. Installing Command Line Tools..."
    
    # Ensure we have required tools
    if ! command -v wget >/dev/null 2>&1; then
        log_warn "wget not found. Attempting to install..."
        if command -v apt-get >/dev/null 2>&1; then
            sudo apt-get update -qq
            sudo apt-get install -y wget unzip
        elif command -v yum >/dev/null 2>&1; then
            sudo yum install -y wget unzip
        else
            log_error "Cannot install wget. Please install it manually."
            exit 1
        fi
    fi
    
    # Download and install command line tools
    CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    CMDLINE_ZIP="/tmp/android-cmdline-tools.zip"
    
    log_info "Downloading Android Command Line Tools..."
    wget -q --show-progress "$CMDLINE_TOOLS_URL" -O "$CMDLINE_ZIP" || {
        log_error "Failed to download Android Command Line Tools"
        exit 1
    }
    
    log_info "Extracting Command Line Tools..."
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    unzip -q "$CMDLINE_ZIP" -d "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    
    # Fix directory structure (unzip creates nested cmdline-tools directory)
    if [ -d "$ANDROID_SDK_ROOT/cmdline-tools/latest/cmdline-tools" ]; then
        mv "$ANDROID_SDK_ROOT/cmdline-tools/latest/cmdline-tools"/* "$ANDROID_SDK_ROOT/cmdline-tools/latest/" 2>/dev/null || true
        rmdir "$ANDROID_SDK_ROOT/cmdline-tools/latest/cmdline-tools" 2>/dev/null || true
    fi
    
    # Clean up
    rm -f "$CMDLINE_ZIP"
    
    # Make sdkmanager executable
    chmod +x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" || true
    
    log_info "Android Command Line Tools installed successfully"
else
    log_info "Android SDK Manager already installed"
fi

# --- Accept SDK Licenses ---
log_info "Accepting Android SDK licenses..."
yes | sdkmanager --licenses >/dev/null 2>&1 || true

# --- Install Required SDK Components ---
log_info "Installing required Android SDK components..."
log_info "This may take several minutes..."

# Install platform-tools, platform, and build-tools
sdkmanager --install \
    "platform-tools" \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    2>&1 | grep -v "Warning:" || true

log_info "Android SDK components installed successfully"

# --- Verify Installation ---
log_info "Verifying Android SDK installation..."
if [ -f "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
    log_info "✓ platform-tools installed"
fi
if [ -d "$ANDROID_SDK_ROOT/platforms/android-34" ]; then
    log_info "✓ android-34 platform installed"
fi
if [ -d "$ANDROID_SDK_ROOT/build-tools/34.0.0" ]; then
    log_info "✓ build-tools 34.0.0 installed"
fi

# --- Gradle Configuration ---
log_info "Gradle configuration..."
./gradlew --version

# --- Clean Build Cache ---
log_info "Cleaning Gradle caches..."
rm -rf .gradle/buildOutputCleanup 2>/dev/null || true
rm -rf .gradle/kotlin 2>/dev/null || true

log_info "Cleaning build outputs..."
./gradlew clean || log_warn "Gradle clean had issues (may be safe to ignore)"

# --- Generate Contract Code ---
log_info "Generating FishIT Contract (KotlinPoet)..."
./gradlew :shared:contract:generateFishitContract || {
    log_error "Contract generation failed"
    exit 1
}

# --- Build Android App ---
log_info "Building Android App (Debug)..."
log_info "This will take a few minutes on first run..."

./gradlew :androidApp:assembleDebug || {
    log_error "Android build failed"
    exit 1
}

# --- Success ---
echo ""
log_info "========================================="
log_info "✅ FishIT-Mapper is ready for development!"
log_info "========================================="
echo ""
log_info "Useful commands:"
echo "  ./gradlew build                                    - Full build"
echo "  ./gradlew test                                     - Run tests"
echo "  ./gradlew :shared:contract:generateFishitContract  - Regenerate contract"
echo "  ./gradlew :androidApp:assembleDebug                - Build Android APK"
echo "  ./gradlew :androidApp:installDebug                 - Install on device"
echo ""
log_info "APK location: androidApp/build/outputs/apk/debug/androidApp-debug.apk"
echo ""
