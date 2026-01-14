#!/bin/bash

# Copilot Ruleset Verification Script
# Prüft ob das Ruleset korrekt konfiguriert ist

# Don't exit on errors - we want to show all results
set +e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "🔍 Copilot Ruleset Verification"
echo "================================"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

SUCCESS=0
WARNINGS=0
ERRORS=0

# Check functions
check_file() {
    local file=$1
    local description=$2
    
    if [ -f "$file" ]; then
        echo -e "${GREEN}✅${NC} $description: $file"
        ((SUCCESS++))
        return 0
    else
        echo -e "${RED}❌${NC} $description fehlt: $file"
        ((ERRORS++))
        return 1
    fi
}

check_json_syntax() {
    local file=$1
    local description=$2
    
    if python3 -m json.tool "$file" > /dev/null 2>&1; then
        echo -e "${GREEN}✅${NC} $description: JSON Syntax valide"
        ((SUCCESS++))
        return 0
    else
        echo -e "${RED}❌${NC} $description: JSON Syntax Fehler"
        ((ERRORS++))
        return 1
    fi
}

check_directory() {
    local dir=$1
    local description=$2
    
    if [ -d "$dir" ]; then
        echo -e "${GREEN}✅${NC} $description: $dir"
        ((SUCCESS++))
        return 0
    else
        echo -e "${YELLOW}⚠️${NC} $description fehlt: $dir"
        ((WARNINGS++))
        return 1
    fi
}

warn_if_missing() {
    local file=$1
    local description=$2
    
    if [ -f "$file" ]; then
        echo -e "${GREEN}✅${NC} $description: $file"
        ((SUCCESS++))
        return 0
    else
        echo -e "${YELLOW}⚠️${NC} $description fehlt: $file (optional)"
        ((WARNINGS++))
        return 1
    fi
}

# 1. Check Ruleset File
echo "1. Ruleset Datei"
echo "----------------"
check_file ".github/copilot/workflow-automation.json" "Workflow Automation Config"
check_json_syntax ".github/copilot/workflow-automation.json" "Workflow Automation JSON Syntax"
echo ""

# 2. Check Agents Configuration
echo "2. Agent Konfiguration"
echo "----------------------"
check_file ".github/copilot/agents.json" "Agents Config"
check_json_syntax ".github/copilot/agents.json" "Agents JSON Syntax"
echo ""

# 3. Check Orchestrator Files
echo "3. Orchestrator Integration"
echo "---------------------------"
check_file "scripts/orchestrator/transition.mjs" "Orchestrator Script"
check_file ".github/workflows/orchestrator.yml" "Orchestrator Workflow"
check_file "codex/CHECKPOINT.md" "Checkpoint File"
check_file "codex/TODO_QUEUE.md" "TODO Queue File"
echo ""

# 4. Check Documentation
echo "4. Dokumentation"
echo "----------------"
check_file "docs/COPILOT_RULESET.md" "Ruleset Dokumentation"
check_file "docs/COPILOT_RULESET_QUICKSTART.md" "Quick Start Guide"
check_file "docs/COPILOT_RULESET_MIGRATION.md" "Migration Guide"
check_file "docs/ORCHESTRATOR.md" "Orchestrator Dokumentation"
echo ""

# 5. Check Issue Templates
echo "5. Issue Templates"
echo "------------------"
check_directory ".github/ISSUE_TEMPLATE" "Issue Template Verzeichnis"
check_file ".github/ISSUE_TEMPLATE/automated-workflow.md" "Automated Workflow Template"
echo ""

# 6. Check Ruleset Structure
echo "6. Ruleset Struktur"
echo "-------------------"

if [ -f ".github/copilot/workflow-automation.json" ]; then
    # Check for required rules
    REQUIRED_RULES=(
        "auto-issue-tasklist-generation"
        "auto-start-first-task"
        "auto-ready-for-review"
        "auto-fix-review-findings"
        "auto-merge-on-approval"
        "auto-close-issue-when-complete"
    )
    
    for rule in "${REQUIRED_RULES[@]}"; do
        if grep -q "\"id\": \"$rule\"" ".github/copilot/workflow-automation.json"; then
            echo -e "${GREEN}✅${NC} Regel gefunden: $rule"
            ((SUCCESS++))
        else
            echo -e "${RED}❌${NC} Regel fehlt: $rule"
            ((ERRORS++))
        fi
    done
else
    echo -e "${RED}❌${NC} Workflow Automation Datei nicht gefunden"
    ((ERRORS++))
fi
echo ""

# 7. Check Settings in Ruleset
echo "7. Ruleset Einstellungen"
echo "------------------------"

if [ -f ".github/copilot/workflow-automation.json" ]; then
    REQUIRED_SETTINGS=(
        "max_iterations"
        "max_check_failures"
        "merge_strategy"
        "auto_merge_enabled"
    )
    
    for setting in "${REQUIRED_SETTINGS[@]}"; do
        if grep -q "\"$setting\"" ".github/copilot/workflow-automation.json"; then
            echo -e "${GREEN}✅${NC} Einstellung gefunden: $setting"
            ((SUCCESS++))
        else
            echo -e "${YELLOW}⚠️${NC} Einstellung fehlt: $setting"
            ((WARNINGS++))
        fi
    done
fi
echo ""

# 8. Check GitHub CLI (optional but recommended)
echo "8. GitHub CLI"
echo "-------------"
if command -v gh &> /dev/null; then
    GH_VERSION=$(gh --version | head -n 1)
    echo -e "${GREEN}✅${NC} GitHub CLI installiert: $GH_VERSION"
    ((SUCCESS++))
    
    # Check if authenticated
    if gh auth status &> /dev/null; then
        echo -e "${GREEN}✅${NC} GitHub CLI authentifiziert"
        ((SUCCESS++))
    else
        echo -e "${YELLOW}⚠️${NC} GitHub CLI nicht authentifiziert (optional)"
        ((WARNINGS++))
    fi
else
    echo -e "${YELLOW}⚠️${NC} GitHub CLI nicht installiert (optional aber empfohlen)"
    echo "   Installation: https://cli.github.com/"
    ((WARNINGS++))
fi
echo ""

# 9. Check Node.js (for orchestrator script)
echo "9. Node.js"
echo "----------"
if command -v node &> /dev/null; then
    NODE_VERSION=$(node --version)
    echo -e "${GREEN}✅${NC} Node.js installiert: $NODE_VERSION"
    ((SUCCESS++))
    
    # Check version >= 18
    NODE_MAJOR=$(node --version | cut -d'v' -f2 | cut -d'.' -f1)
    if [ "$NODE_MAJOR" -ge 18 ]; then
        echo -e "${GREEN}✅${NC} Node.js Version kompatibel (>= 18)"
        ((SUCCESS++))
    else
        echo -e "${YELLOW}⚠️${NC} Node.js Version veraltet. Empfohlen: >= 18"
        ((WARNINGS++))
    fi
else
    echo -e "${RED}❌${NC} Node.js nicht installiert (erforderlich für Orchestrator)"
    ((ERRORS++))
fi
echo ""

# 10. README Updates
echo "10. README Updates"
echo "------------------"
if grep -q "Copilot Ruleset" "README.md"; then
    echo -e "${GREEN}✅${NC} README erwähnt Copilot Ruleset"
    ((SUCCESS++))
else
    echo -e "${YELLOW}⚠️${NC} README erwähnt Copilot Ruleset nicht"
    ((WARNINGS++))
fi
echo ""

# Summary
echo ""
echo "📊 Zusammenfassung"
echo "=================="
echo -e "${GREEN}✅ Erfolgreich:${NC} $SUCCESS"
echo -e "${YELLOW}⚠️  Warnungen:${NC} $WARNINGS"
echo -e "${RED}❌ Fehler:${NC} $ERRORS"
echo ""

if [ $ERRORS -eq 0 ]; then
    if [ $WARNINGS -eq 0 ]; then
        echo -e "${GREEN}🎉 Perfekt! Ruleset ist vollständig konfiguriert!${NC}"
        echo ""
        echo "Nächste Schritte:"
        echo "1. Die Datei .github/copilot/workflow-automation.json dokumentiert die gewünschte Automation"
        echo "   HINWEIS: GitHub Repository Rulesets werden über Settings > Branches > Rulesets konfiguriert, nicht über JSON-Dateien"
        echo ""
        echo "2. Test-Issue erstellen:"
        echo "   gh issue create --title '[TEST] Copilot Workflow' --label 'orchestrator:enabled,orchestrator:run'"
        echo ""
        echo "3. Workflow beobachten in Issue-Kommentaren und codex/CHECKPOINT.md"
        exit 0
    else
        echo -e "${YELLOW}⚠️  Ruleset ist funktional, aber es gibt optionale Verbesserungen${NC}"
        echo ""
        echo "Empfohlene Aktionen:"
        echo "- GitHub CLI installieren und authentifizieren für bessere Integration"
        echo "- Optionale Dateien erstellen für vollständige Dokumentation"
        exit 0
    fi
else
    echo -e "${RED}❌ Fehler gefunden! Bitte behebe die oben genannten Probleme.${NC}"
    echo ""
    echo "Hilfe:"
    echo "- Dokumentation: docs/COPILOT_RULESET.md"
    echo "- Quick Start: docs/COPILOT_RULESET_QUICKSTART.md"
    echo "- Migration: docs/COPILOT_RULESET_MIGRATION.md"
    exit 1
fi
