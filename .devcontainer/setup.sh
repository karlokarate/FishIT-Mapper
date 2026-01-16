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

# Android SDK setup
if [ -n "$ANDROID_HOME" ]; then
    echo "📱 Android SDK: $ANDROID_HOME"
    # Create local.properties for Gradle
    echo "sdk.dir=$ANDROID_HOME" > local.properties
    echo "✅ local.properties created"
elif [ -d "/usr/lib/android-sdk" ]; then
    export ANDROID_HOME="/usr/lib/android-sdk"
    echo "sdk.dir=$ANDROID_HOME" > local.properties
    echo "📱 Android SDK found at: $ANDROID_HOME"
else
    echo "⚠️  ANDROID_HOME not set - Android builds may fail"
    echo "   Run: sudo apt-get update && sudo apt-get install -y android-sdk"
fi

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
