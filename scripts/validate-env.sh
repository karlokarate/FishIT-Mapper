#!/usr/bin/env bash
# FishIT-Mapper Environment Validation Script
# Checks if the environment is ready for building FishIT-Mapper

set -euo pipefail

# --- Colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}✓${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
}

log_error() {
    echo -e "${RED}✗${NC} $1"
}

log_header() {
    echo -e "\n${BLUE}=== $1 ===${NC}"
}

# --- Script location ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

echo ""
echo "FishIT-Mapper Environment Check"
echo "================================"

# --- Java Check ---
log_header "Java Environment"

if command -v java >/dev/null 2>&1; then
    JAVA_VERSION=$(java -version 2>&1 | head -n 1)
    log_info "Java found: $JAVA_VERSION"
    
    JAVA_MAJOR=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F. '{print $1}')
    if [ "$JAVA_MAJOR" -ge 17 ]; then
        log_info "Java version is compatible (>= 17)"
    else
        log_error "Java version is too old (need >= 17, have $JAVA_MAJOR)"
        echo "  Install: sudo apt-get install openjdk-17-jdk"
    fi
    
    if [ -n "${JAVA_HOME:-}" ]; then
        log_info "JAVA_HOME is set: $JAVA_HOME"
    else
        log_warn "JAVA_HOME not set (usually not required)"
    fi
else
    log_error "Java not found in PATH"
    echo "  Install: sudo apt-get install openjdk-17-jdk"
fi

# --- Gradle Check ---
log_header "Gradle"

if [ -f "./gradlew" ]; then
    log_info "Gradle wrapper found"
    if [ -x "./gradlew" ]; then
        log_info "Gradle wrapper is executable"
    else
        log_warn "Gradle wrapper is not executable (will be fixed by setup)"
    fi
    
    # Try to get Gradle version
    if ./gradlew --version >/dev/null 2>&1; then
        GRADLE_VER=$(./gradlew --version 2>/dev/null | grep "Gradle" | head -n 1)
        log_info "Gradle: $GRADLE_VER"
    else
        log_warn "Could not determine Gradle version"
    fi
else
    log_error "Gradle wrapper (gradlew) not found"
fi

# --- Android SDK Check ---
log_header "Android SDK"

if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    log_info "ANDROID_SDK_ROOT is set: $ANDROID_SDK_ROOT"
    
    if [ -d "$ANDROID_SDK_ROOT" ]; then
        log_info "Android SDK directory exists"
        
        # Check for required components
        if [ -f "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
            log_info "platform-tools installed"
        else
            log_warn "platform-tools not found"
        fi
        
        if [ -d "$ANDROID_SDK_ROOT/platforms/android-34" ]; then
            log_info "android-34 platform installed"
        else
            log_warn "android-34 platform not found"
        fi
        
        if [ -d "$ANDROID_SDK_ROOT/build-tools/34.0.0" ]; then
            log_info "build-tools 34.0.0 installed"
        else
            log_warn "build-tools 34.0.0 not found"
        fi
    else
        log_warn "Android SDK directory does not exist yet"
        echo "  Will be created by: ./scripts/codex-setup.sh"
    fi
    
    if command -v sdkmanager >/dev/null 2>&1; then
        log_info "sdkmanager found in PATH"
    else
        log_warn "sdkmanager not in PATH"
        echo "  Will be installed by: ./scripts/codex-setup.sh"
    fi
else
    log_warn "ANDROID_SDK_ROOT not set"
    echo "  Will be set by: ./scripts/codex-setup.sh"
fi

if [ -n "${ANDROID_HOME:-}" ]; then
    log_info "ANDROID_HOME is set: $ANDROID_HOME"
else
    log_warn "ANDROID_HOME not set (will be set by setup)"
fi

# --- System Tools Check ---
log_header "System Tools"

TOOLS=("wget" "unzip" "git" "bash")
for tool in "${TOOLS[@]}"; do
    if command -v "$tool" >/dev/null 2>&1; then
        log_info "$tool found"
    else
        log_warn "$tool not found (will be installed if needed)"
    fi
done

# --- Disk Space Check ---
log_header "Disk Space"

# More robust disk space parsing
if command -v df >/dev/null 2>&1; then
    AVAILABLE_GB=$(df -BG . 2>/dev/null | awk 'NR==2 {print $4}' | sed 's/G//' || echo "0")
    if [ -n "$AVAILABLE_GB" ] && [ "$AVAILABLE_GB" -ge 5 ]; then
        log_info "Available disk space: ${AVAILABLE_GB}GB (sufficient)"
    elif [ -n "$AVAILABLE_GB" ]; then
        log_warn "Available disk space: ${AVAILABLE_GB}GB (may be tight, 5GB+ recommended)"
    else
        log_warn "Could not determine available disk space"
    fi
else
    log_warn "df command not available, cannot check disk space"
fi

# --- Memory Check ---
log_header "Memory"

if command -v free >/dev/null 2>&1; then
    TOTAL_MEM_MB=$(free -m | awk 'NR==2 {print $2}')
    TOTAL_MEM_GB=$((TOTAL_MEM_MB / 1024))
    if [ "$TOTAL_MEM_GB" -ge 4 ]; then
        log_info "Total memory: ${TOTAL_MEM_GB}GB (sufficient)"
    else
        log_warn "Total memory: ${TOTAL_MEM_GB}GB (4GB+ recommended)"
    fi
else
    log_warn "Could not determine memory (free command not found)"
fi

# --- Project Structure Check ---
log_header "Project Structure"

EXPECTED_DIRS=("androidApp" "shared" "schema" "gradle" "scripts")
for dir in "${EXPECTED_DIRS[@]}"; do
    if [ -d "$dir" ]; then
        log_info "$dir/ exists"
    else
        log_error "$dir/ not found"
    fi
done

EXPECTED_FILES=("build.gradle.kts" "settings.gradle.kts" "gradle.properties" "schema/contract.schema.json")
for file in "${EXPECTED_FILES[@]}"; do
    if [ -f "$file" ]; then
        log_info "$file exists"
    else
        log_error "$file not found"
    fi
done

# --- Scripts Check ---
log_header "Setup Scripts"

if [ -f "scripts/codex-setup.sh" ]; then
    log_info "codex-setup.sh exists"
    if [ -x "scripts/codex-setup.sh" ]; then
        log_info "codex-setup.sh is executable"
    else
        log_warn "codex-setup.sh is not executable"
        echo "  Fix: chmod +x scripts/codex-setup.sh"
    fi
else
    log_error "codex-setup.sh not found"
fi

if [ -f "scripts/maintenance.sh" ]; then
    log_info "maintenance.sh exists"
    if [ -x "scripts/maintenance.sh" ]; then
        log_info "maintenance.sh is executable"
    else
        log_warn "maintenance.sh is not executable"
        echo "  Fix: chmod +x scripts/maintenance.sh"
    fi
else
    log_error "maintenance.sh not found"
fi

# --- Summary ---
log_header "Summary"

echo ""
echo "Environment Status:"
echo "-------------------"

# Count checks
JAVA_OK=0
GRADLE_OK=0
SDK_OK=0

if command -v java >/dev/null 2>&1; then
    JAVA_MAJOR=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F. '{print $1}')
    [ "$JAVA_MAJOR" -ge 17 ] && JAVA_OK=1
fi

[ -f "./gradlew" ] && GRADLE_OK=1

[ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT:-/nonexistent}" ] && SDK_OK=1

if [ $JAVA_OK -eq 1 ] && [ $GRADLE_OK -eq 1 ] && [ $SDK_OK -eq 1 ]; then
    echo -e "${GREEN}✓ Ready to build!${NC}"
    echo ""
    echo "Next steps:"
    echo "  ./scripts/maintenance.sh              # Quick maintenance"
    echo "  ./gradlew :androidApp:assembleDebug   # Build APK"
elif [ $JAVA_OK -eq 1 ] && [ $GRADLE_OK -eq 1 ]; then
    echo -e "${YELLOW}⚠ Needs Android SDK setup${NC}"
    echo ""
    echo "Next steps:"
    echo "  ./scripts/codex-setup.sh   # Install Android SDK and build"
else
    echo -e "${RED}✗ Needs setup${NC}"
    echo ""
    if [ $JAVA_OK -eq 0 ]; then
        echo "1. Install Java 17+:"
        echo "   sudo apt-get update"
        echo "   sudo apt-get install -y openjdk-17-jdk"
    fi
    echo "2. Run setup:"
    echo "   ./scripts/codex-setup.sh"
fi

echo ""
