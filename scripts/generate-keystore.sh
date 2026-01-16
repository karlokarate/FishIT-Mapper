#!/bin/bash
# =============================================================================
# FishIT-Mapper Release Keystore Generator
# =============================================================================
# Dieses Script erstellt einen Release-Keystore für die APK-Signierung.
#
# WICHTIG:
# - Den Keystore NIEMALS committen!
# - Passwörter sicher aufbewahren!
# - Für GitHub Actions: Secrets konfigurieren (siehe unten)
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYSTORE_DIR="$SCRIPT_DIR/../keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/release.jks"

# Farben für Output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}==============================================================================${NC}"
echo -e "${BLUE}  FishIT-Mapper Release Keystore Generator${NC}"
echo -e "${BLUE}==============================================================================${NC}"
echo ""

# Prüfe ob Keystore bereits existiert
if [ -f "$KEYSTORE_FILE" ]; then
    echo -e "${YELLOW}⚠️  Keystore existiert bereits: $KEYSTORE_FILE${NC}"
    read -p "Überschreiben? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Abgebrochen."
        exit 0
    fi
    rm -f "$KEYSTORE_FILE"
fi

# Erstelle Keystore-Verzeichnis
mkdir -p "$KEYSTORE_DIR"

# Generiere sichere Passwörter (oder nutze vorgegebene)
if [ -z "$KEYSTORE_PASSWORD" ]; then
    KEYSTORE_PASSWORD=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
fi
if [ -z "$KEY_PASSWORD" ]; then
    KEY_PASSWORD=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
fi

KEY_ALIAS="fishit-mapper"
VALIDITY_DAYS=10000  # ~27 Jahre

echo -e "${GREEN}📝 Generiere Keystore...${NC}"
echo ""

# Erstelle Keystore
keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_FILE" \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity $VALIDITY_DAYS \
    -dname "CN=FishIT-Mapper,O=FishIT,L=Germany,C=DE"

echo ""
echo -e "${GREEN}✅ Keystore erfolgreich erstellt!${NC}"
echo ""
echo -e "${BLUE}==============================================================================${NC}"
echo -e "${YELLOW}📋 WICHTIG - Diese Werte sicher aufbewahren:${NC}"
echo -e "${BLUE}==============================================================================${NC}"
echo ""
echo -e "  Keystore-Datei:  ${GREEN}$KEYSTORE_FILE${NC}"
echo -e "  Key Alias:       ${GREEN}$KEY_ALIAS${NC}"
echo -e "  Keystore-Passwort: ${GREEN}$KEYSTORE_PASSWORD${NC}"
echo -e "  Key-Passwort:      ${GREEN}$KEY_PASSWORD${NC}"
echo ""
echo -e "${BLUE}==============================================================================${NC}"
echo -e "${YELLOW}🔐 Für GitHub Actions - Secrets konfigurieren:${NC}"
echo -e "${BLUE}==============================================================================${NC}"
echo ""
echo "1. Gehe zu: Repository → Settings → Secrets and variables → Actions"
echo ""
echo "2. Erstelle folgende Repository Secrets:"
echo ""
echo -e "   ${GREEN}KEYSTORE_BASE64${NC}"
echo "   (Base64-encoded Keystore - siehe unten)"
echo ""
echo -e "   ${GREEN}KEYSTORE_PASSWORD${NC}"
echo "   $KEYSTORE_PASSWORD"
echo ""
echo -e "   ${GREEN}KEY_ALIAS${NC}"
echo "   $KEY_ALIAS"
echo ""
echo -e "   ${GREEN}KEY_PASSWORD${NC}"
echo "   $KEY_PASSWORD"
echo ""
echo -e "${BLUE}==============================================================================${NC}"
echo -e "${YELLOW}📦 Base64-encoded Keystore für KEYSTORE_BASE64:${NC}"
echo -e "${BLUE}==============================================================================${NC}"
echo ""
base64 -w 0 "$KEYSTORE_FILE"
echo ""
echo ""
echo -e "${BLUE}==============================================================================${NC}"
echo -e "${RED}⚠️  SICHERHEITSHINWEISE:${NC}"
echo -e "${BLUE}==============================================================================${NC}"
echo ""
echo "• Den Keystore NIEMALS in Git committen!"
echo "• Passwörter NIEMALS im Code speichern!"
echo "• Backup des Keystores sicher aufbewahren!"
echo "• Bei Verlust können Updates nicht mehr signiert werden!"
echo ""
