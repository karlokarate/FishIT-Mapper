#!/bin/bash
# Post-Create Setup Script für FishIT-Mapper Devcontainer

set -e
echo "🔧 Setting up FishIT-Mapper development environment..."

# Ensure we're in the right directory
cd /workspaces/FishIT-Mapper || exit 1

# Fix Gradle cache permissions (common issue in Codespaces)
if [ -d "/home/vscode/.gradle" ]; then
    sudo chown -R vscode:vscode /home/vscode/.gradle 2>/dev/null || true
    chmod -R 755 /home/vscode/.gradle 2>/dev/null || true
    echo "✅ Gradle cache permissions fixed"
fi

# Make Gradle wrapper executable
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    echo "✅ Gradle wrapper ready"
fi

# Git configuration
git config --global --add safe.directory /workspaces/FishIT-Mapper
git config --global core.autocrlf input
git config --global pull.rebase true
echo "✅ Git configured"

# Verify Java
echo "☕ Java: $(java -version 2>&1 | head -1)"

# Android SDK installation
ANDROID_SDK_ROOT="/home/vscode/android-sdk"
if [ ! -d "$ANDROID_SDK_ROOT/cmdline-tools" ]; then
    echo "📱 Installing Android SDK..."
    mkdir -p "$ANDROID_SDK_ROOT"
    cd "$ANDROID_SDK_ROOT"
    
    # Download command-line tools
    CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    curl -sSL "$CMDLINE_TOOLS_URL" -o cmdline-tools.zip
    unzip -q cmdline-tools.zip
    mkdir -p cmdline-tools/latest
    mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/NOTICE.txt cmdline-tools/source.properties cmdline-tools/latest/ 2>/dev/null || mv cmdline-tools/* cmdline-tools/latest/ 2>/dev/null || true
    rm -f cmdline-tools.zip
    
    # Accept licenses and install SDK components
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
    export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin"
    yes | sdkmanager --licenses > /dev/null 2>&1 || true
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" > /dev/null 2>&1
    
    cd /workspaces/FishIT-Mapper
    echo "✅ Android SDK installed"
else
    echo "✅ Android SDK already installed"
fi

# Set ANDROID_HOME and create local.properties
export ANDROID_HOME="$ANDROID_SDK_ROOT"
echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "📱 Android SDK: $ANDROID_HOME"

# Pre-warm Gradle daemon (background)
if [ -f "./gradlew" ]; then
    echo "🔥 Pre-warming Gradle daemon..."
    ./gradlew --version > /dev/null 2>&1 &
fi

echo ""
echo "✅ Setup complete!"
echo ""
echo "📚 Commands:"
echo "  ./gradlew build                                    - Build project"
echo "  ./gradlew test                                     - Run tests"
echo "  ./gradlew :shared:contract:generateFishitContract  - Generate contract"
echo "  ./gradlew :androidApp:assembleDebug                - Build Android APK"
echo ""
echo "🚀 Happy coding!"
