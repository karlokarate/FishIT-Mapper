#!/usr/bin/env bash
# FishIT-Mapper Maintenance Script
# Quick maintenance tasks: clean, regenerate contract, compile check

set -euo pipefail

echo "=== FishIT-Mapper Maintenance ==="

# --- Color output ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

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
log_info "Checking Gradle wrapper..."
chmod +x ./gradlew 2>/dev/null || true

# --- Java Sanity Check ---
log_info "Checking Java installation..."
if ! command -v java >/dev/null 2>&1; then
    log_error "Java not found. Please install JDK 17 or higher."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1)
log_info "Java: $JAVA_VERSION"

# Check Java version (AGP 8.x requires JDK 17+)
JAVA_MAJOR=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F. '{print $1}')
if [ "$JAVA_MAJOR" -lt 17 ]; then
    log_error "Java 17 or higher is required for Android Gradle Plugin 8.x"
    log_error "Current version: $JAVA_VERSION"
    exit 1
fi

# --- Android SDK Sanity Check ---
log_info "Checking Android SDK..."
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    log_error "ANDROID_SDK_ROOT not set"
    log_error "Please run scripts/codex-setup.sh first or set ANDROID_SDK_ROOT manually"
    exit 1
fi

if ! command -v sdkmanager >/dev/null 2>&1; then
    log_error "sdkmanager not found in PATH"
    log_error "Please run scripts/codex-setup.sh first to install Android SDK"
    exit 1
fi

log_info "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
log_info "✓ Android SDK found"

# --- Gradle Cache Hygiene ---
log_info "Cleaning Gradle project cache..."
rm -rf .gradle/buildOutputCleanup 2>/dev/null || true
rm -rf .gradle/kotlin 2>/dev/null || true
log_info "✓ Gradle cache cleaned"

# --- Clean Build Outputs ---
log_info "Cleaning build outputs..."
./gradlew clean || log_warn "Gradle clean had warnings (may be safe to ignore)"
log_info "✓ Build outputs cleaned"

# --- Regenerate Contract ---
log_info "Regenerating FishIT Contract (KotlinPoet)..."
./gradlew :shared:contract:generateFishitContract || {
    log_error "Contract generation failed"
    exit 1
}
log_info "✓ Contract regenerated"

# --- Compile Sanity Check ---
log_info "Running compile sanity check (Android Debug Kotlin)..."
./gradlew :androidApp:compileDebugKotlin || {
    log_error "Compilation failed"
    exit 1
}
log_info "✓ Compilation successful"

# --- Optional: Full Assembly ---
# Uncomment the following lines if you want to do a full APK build
# log_info "Building debug APK..."
# ./gradlew :androidApp:assembleDebug || {
#     log_error "APK build failed"
#     exit 1
# }
# log_info "✓ Debug APK built"

# --- Success ---
echo ""
log_info "====================================="
log_info "✅ Maintenance completed successfully!"
log_info "====================================="
echo ""
log_info "Next steps:"
echo "  - Make your code changes"
echo "  - Run ./gradlew test to verify tests pass"
echo "  - Run ./gradlew :androidApp:assembleDebug to build APK"
echo ""
