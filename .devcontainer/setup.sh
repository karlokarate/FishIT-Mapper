#!/bin/bash
# Post-Create Setup Script für FishIT-Mapper Devcontainer

set -e
echo "🔧 Setting up FishIT-Mapper development environment..."

# Ensure we're in the right directory
cd /workspaces/FishIT-Mapper || exit 1

# Make Gradle wrapper executable
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    echo "✅ Gradle wrapper ready"
fi

# Git safe directory
git config --global --add safe.directory /workspaces/FishIT-Mapper

# Verify Java
echo "☕ Java: $(java -version 2>&1 | head -1)"

echo ""
echo "✅ Setup complete!"
echo ""
echo "📚 Commands:"
echo "  ./gradlew build                                    - Build project"
echo "  ./gradlew test                                     - Run tests"
echo "  ./gradlew :shared:contract:generateFishitContract  - Generate contract"
echo ""
echo "🚀 Happy coding!"
