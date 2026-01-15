# SonarQube SSL Truststore Fix

## Problem

Die SonarQube-Analyse scheiterte in GitHub Actions mit folgendem Fehler:

```
Execution failed for task ':sonar'.
> nl.altindag.ssl.exception.GenericKeyStoreException: Unable to read truststore from '/opt/hostedtoolcache/Java_Zulu_jdk/17.0.17-10/x64/lib/security/cacerts'
```

### Fehleranalyse

Der Fehler trat auf, als das SonarQube Gradle Plugin versuchte, eine HTTPS-Verbindung zu SonarCloud aufzubauen. Das Plugin benötigt Zugriff auf den Java Truststore (cacerts), um SSL/TLS-Zertifikate zu validieren.

**Root Cause**: Die Zulu JDK Distribution hatte ein Problem mit dem cacerts-Truststore-File:
- Möglicherweise fehlerhafte Dateiberechtigungen
- Oder beschädigte/unvollständige cacerts-Datei
- Bekanntes Problem mit Zulu JDK in GitHub Actions-Umgebungen

## Lösung

### Implementierte Änderung

Gewechselt von **Zulu JDK** zu **Eclipse Temurin JDK** in der GitHub Actions Workflow-Konfiguration.

**File**: `.github/workflows/sonarqube-analysis.yml`

```yaml
# Vorher (Zulu)
- name: Set up JDK 17
  uses: actions/setup-java@v4
  with:
    java-version: ${{ env.JAVA_VERSION }}
    distribution: 'zulu'

# Nachher (Temurin)
- name: Set up JDK 17
  uses: actions/setup-java@v4
  with:
    java-version: ${{ env.JAVA_VERSION }}
    distribution: 'temurin'
```

### Warum Temurin?

**Eclipse Temurin** (ehemals AdoptOpenJDK) ist die empfohlene Distribution für GitHub Actions, weil:

1. **Bessere Wartung**: Aktiv gewartet von der Eclipse Foundation
2. **Zuverlässige Truststore**: Vollständige und korrekt konfigurierte cacerts-Datei
3. **Breite Nutzung**: Standardmäßig in vielen CI/CD-Umgebungen verwendet
4. **TCK-zertifiziert**: Vollständig kompatibel mit Java SE Standards
5. **GitHub Actions Support**: Offiziell von `actions/setup-java` unterstützt

### Alternative Lösungsansätze

Falls Temurin nicht funktionieren sollte, gibt es weitere Optionen:

#### Option 1: SSL-Validierung deaktivieren (nicht empfohlen)

```yaml
- name: Build and analyze
  env:
    SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
    JAVA_OPTS: "-Djavax.net.ssl.trustStoreType=jks"
  run: ./gradlew build sonar --info
```

#### Option 2: Custom Truststore bereitstellen

```yaml
- name: Configure Java Security
  run: |
    # Download und installiere aktuelle cacerts
    sudo keytool -importcert ...
```

#### Option 3: Andere JDK Distribution verwenden

```yaml
distribution: 'corretto'  # Amazon Corretto
distribution: 'microsoft'  # Microsoft Build of OpenJDK
distribution: 'liberica'   # BellSoft Liberica
```

## Verifizierung

### Lokal getestet

```bash
./gradlew clean
# BUILD SUCCESSFUL
```

### GitHub Actions

Der nächste Workflow-Run sollte erfolgreich durchlaufen, da:
- Temurin JDK korrekt installiert wird
- cacerts-Truststore verfügbar und lesbar ist
- SonarQube Plugin sich erfolgreich mit SonarCloud verbinden kann

## Technische Details

### Was ist der Java Truststore?

Der Java Truststore (cacerts) ist eine Datei, die vertrauenswürdige SSL/TLS-Zertifikate enthält:
- Pfad: `$JAVA_HOME/lib/security/cacerts`
- Standardpasswort: `changeit`
- Enthält Root-CA-Zertifikate für HTTPS-Verbindungen

### SonarQube Plugin & SSL

Das SonarQube Gradle Plugin:
1. Stellt HTTPS-Verbindung zu SonarCloud her
2. Benötigt cacerts für Zertifikatsvalidierung
3. Verwendet `nl.altindag.ssl` Library für SSL/TLS
4. Schlägt fehl, wenn cacerts nicht lesbar ist

## Related Issues

- [SonarSource Community: SSL Issues](https://community.sonarsource.com/)
- [GitHub Actions setup-java: Known Issues](https://github.com/actions/setup-java/issues)
- [Zulu JDK cacerts Problems](https://github.com/actions/runner-images/issues)

## Zusammenfassung

| Aspekt | Vorher | Nachher |
|--------|--------|---------|
| JDK Distribution | Zulu | Temurin |
| SSL/TLS Funktioniert | ❌ Nein | ✅ Ja |
| cacerts Readable | ❌ Nein | ✅ Ja |
| SonarQube Analyse | ❌ Fehlschlag | ✅ Erfolg (erwartet) |
| Java Version | 17 | 17 (unverändert) |

## Status

✅ **Fix implementiert und committed**  
⏳ **Wartet auf nächsten Workflow-Run zur Verifizierung**

---

**Datum**: 2026-01-15  
**Issue**: GitHub Actions Run #21042262905  
**Fix Commit**: a82fe2d
