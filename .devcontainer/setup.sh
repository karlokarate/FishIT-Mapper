#!/bin/bash
# Post-Create Setup Script für FishIT-Mapper Devcontainer

set -e
echo "🔧 Setting up FishIT-Mapper development environment..."

WORKSPACE_ROOT="/workspaces/FishIT-Mapper"

# Ensure we're in the right directory
cd "$WORKSPACE_ROOT" || exit 1

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
git config --global --add safe.directory "$WORKSPACE_ROOT"
git config --global core.autocrlf input
git config --global pull.rebase true
echo "✅ Git configured"

# ── Java 21 ──────────────────────────────────────────────────────────────
JAVA_DIR="/usr/lib/jvm/msopenjdk-current"
if [ ! -d "$JAVA_DIR" ]; then
    JAVA_DIR=$(find /usr/lib/jvm -maxdepth 1 -type d -name "*21*" 2>/dev/null | head -1)
fi
if [ -z "$JAVA_DIR" ] || [ ! -d "$JAVA_DIR" ]; then
    JAVA_DIR=$(dirname $(dirname $(readlink -f $(which java))))
fi
export JAVA_HOME="$JAVA_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
grep -q "JAVA_HOME=" ~/.bashrc 2>/dev/null || {
    echo "export JAVA_HOME=\"$JAVA_DIR\"" >> ~/.bashrc
    echo "export PATH=\"\$JAVA_HOME/bin:\$PATH\"" >> ~/.bashrc
}
echo "☕ Java: $(java -version 2>&1 | head -1)"

# ── Android SDK ──────────────────────────────────────────────────────────
SDK_DIR="$HOME/.android-sdk"
if [ ! -d "$SDK_DIR/cmdline-tools/latest/bin" ]; then
    echo "📱 Installing Android SDK..."
    mkdir -p "$SDK_DIR/cmdline-tools"

    CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    TEMP_ZIP="/tmp/cmdline-tools.zip"

    curl -L -o "$TEMP_ZIP" "$CMDLINE_TOOLS_URL"
    unzip -q "$TEMP_ZIP" -d "$SDK_DIR/cmdline-tools"
    mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm "$TEMP_ZIP"
    echo "✅ Android cmdline-tools installed"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"

grep -q "ANDROID_HOME" ~/.bashrc 2>/dev/null || {
    echo 'export ANDROID_HOME="$HOME/.android-sdk"' >> ~/.bashrc
    echo 'export ANDROID_SDK_ROOT="$HOME/.android-sdk"' >> ~/.bashrc
    echo 'export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"' >> ~/.bashrc
}

# Install SDK packages (matching FishIT-Player: platform 35, build-tools 35)
if [ -d "$SDK_DIR/cmdline-tools/latest/bin" ]; then
    echo "📱 Installing Android SDK packages..."
    yes | sdkmanager --licenses > /dev/null 2>&1 || true
    sdkmanager --install \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" \
        2>/dev/null || true
    echo "✅ SDK packages installed"
fi

echo "sdk.dir=$SDK_DIR" > "$WORKSPACE_ROOT/local.properties"
echo "📱 Android SDK: $SDK_DIR"

# ── Python / Jupyter ─────────────────────────────────────────────────────
echo "🐍 Setting up Python & Jupyter..."
if ! command -v pip3 &> /dev/null; then
    sudo apt-get update -qq
    sudo apt-get install -y --no-install-recommends python3-pip python3-venv 2>/dev/null || true
fi
pip3 install --user --break-system-packages --upgrade jupyter jupyterlab notebook ipywidgets 2>/dev/null || true
grep -q ".local/bin" ~/.bashrc 2>/dev/null || echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
export PATH="$HOME/.local/bin:$PATH"
echo "✅ Jupyter: $(jupyter --version 2>&1 | head -1)"

# ── Pre-warm Gradle daemon ──────────────────────────────────────────────
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
echo "  jupyter lab --ip=0.0.0.0 --port=8888 --no-browser  - Start JupyterLab"
echo ""
echo "🚀 Happy coding!"
