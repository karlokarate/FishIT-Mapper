#!/bin/bash
# Post-Create Setup Script für FishIT-Mapper Devcontainer

echo "🔧 Setting up FishIT-Mapper development environment..."

# Gradle Wrapper executable machen
chmod +x ./gradlew

# Gradle Dependencies herunterladen (im Hintergrund)
echo "📦 Downloading Gradle dependencies..."
./gradlew --version

# Git Config Setup
echo "🔧 Configuring Git..."
git config --global --add safe.directory /workspaces/FishIT-Mapper

# Android SDK License akzeptieren (falls benötigt)
if [ -d "$ANDROID_SDK_ROOT" ]; then
    echo "📱 Accepting Android SDK licenses..."
    yes | sdkmanager --licenses > /dev/null 2>&1 || true
fi

# Projekt-Info ausgeben
echo ""
echo "✅ Setup complete!"
echo ""
echo "📚 Useful commands:"
echo "  ./gradlew build                                    - Build the project"
echo "  ./gradlew test                                     - Run tests"
echo "  ./gradlew :shared:contract:generateFishitContract  - Generate contract"
echo "  ./gradlew :androidApp:assembleDebug                - Build Android APK"
echo ""
echo "🚀 Happy coding with GitHub Copilot!"
