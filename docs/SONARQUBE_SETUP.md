# 📊 SonarQube Analysis Setup für FishIT-Mapper

Dieser Guide hilft dir, die SonarQube-Analyse für dein FishIT-Mapper Projekt einzurichten.

## 🎯 Was wurde implementiert?

### ✅ Gradle-Konfiguration
- **SonarQube Plugin** (Version 4.4.1.3373) wurde hinzugefügt
- Plugin wird automatisch auf alle Subprojekte angewendet
- Vollständige Integration mit dem bestehenden Multi-Modul-Build

### ✅ GitHub Actions Workflow
- Workflow-Datei: `.github/workflows/sonarqube-analysis.yml`
- Automatische Ausführung bei Push/PR auf `main` Branch
- Manuelle Auslösung über GitHub UI möglich
- Umfassendes Debug-Reporting für Fehleranalyse

## 🔧 Erforderliche Schritte zur Einrichtung

### Schritt 1: SonarQube/SonarCloud Account

Du hast zwei Optionen:

#### Option A: SonarCloud (Empfohlen für Open Source)
1. Gehe zu [https://sonarcloud.io](https://sonarcloud.io)
2. Melde dich mit deinem GitHub Account an
3. Klicke auf "+" → "Analyze new project"
4. Wähle dein Repository `FishIT-Mapper` aus
5. Notiere dir:
   - **Organization Key** (meist dein GitHub Username)
   - **Project Key** (meist: `username_FishIT-Mapper`)

#### Option B: Self-Hosted SonarQube
1. Installiere SonarQube auf deinem Server
2. Erstelle ein neues Projekt
3. Notiere dir die Server-URL und den Project Key

### Schritt 2: API Token generieren

#### Für SonarCloud:
1. Gehe zu [https://sonarcloud.io/account/security](https://sonarcloud.io/account/security)
2. Scrolle zu "Generate Tokens"
3. Name: `FishIT-Mapper-GitHub-Actions`
4. Type: `Global Analysis Token` oder `Project Analysis Token`
5. Klicke auf "Generate"
6. **Kopiere den Token sofort** (wird nur einmal angezeigt!)

#### Für Self-Hosted SonarQube:
1. Gehe zu: `https://deine-sonarqube-url/account/security`
2. Generiere einen Token wie oben beschrieben

### Schritt 3: GitHub Secrets konfigurieren

Jetzt musst du die Secrets in deinem GitHub Repository hinzufügen:

#### Direkter Link zu deinen Repository Secrets:
🔗 [https://github.com/karlokarate/FishIT-Mapper/settings/secrets/actions](https://github.com/karlokarate/FishIT-Mapper/settings/secrets/actions)

#### Manuelle Navigation:
1. Gehe zu deinem Repository: `https://github.com/karlokarate/FishIT-Mapper`
2. Klicke auf **Settings** (oben rechts)
3. Links im Menü: **Secrets and variables** → **Actions**
4. Klicke auf **New repository secret**

#### Secrets die du hinzufügen musst:

##### Secret 1: `SONAR_TOKEN`
- **Name:** `SONAR_TOKEN`
- **Value:** Der API Token aus Schritt 2
- Klicke auf "Add secret"

##### Secret 2: `SONAR_HOST_URL`
- **Name:** `SONAR_HOST_URL`
- **Value:** 
  - Für SonarCloud: `https://sonarcloud.io`
  - Für Self-Hosted: `https://deine-sonarqube-url`
- Klicke auf "Add secret"

### Schritt 4: Workflow-Parameter anpassen (Optional)

Falls deine SonarCloud Organization oder Project Key anders sind als die Standardwerte, öffne die Datei `.github/workflows/sonarqube-analysis.yml` und passe diese Zeilen an:

```yaml
-Dsonar.projectKey=FishIT-Mapper \
-Dsonar.organization=${{ github.repository_owner }} \
```

Ändere zu:

```yaml
-Dsonar.projectKey=dein_projekt_key \
-Dsonar.organization=deine_organization \
```

## 🚀 Workflow testen

### Option 1: Push einen Commit
```bash
git add .
git commit -m "test: SonarQube workflow"
git push
```

### Option 2: Manuelle Auslösung
1. Gehe zu: [https://github.com/karlokarate/FishIT-Mapper/actions/workflows/sonarqube-analysis.yml](https://github.com/karlokarate/FishIT-Mapper/actions/workflows/sonarqube-analysis.yml)
2. Klicke auf "Run workflow"
3. Wähle den Branch `main`
4. Klicke auf "Run workflow"

## 📈 Ergebnisse ansehen

### GitHub Actions Logs
🔗 [https://github.com/karlokarate/FishIT-Mapper/actions](https://github.com/karlokarate/FishIT-Mapper/actions)

Hier siehst du:
- Build-Status
- Detaillierte Debug-Reports
- Fehleranalyse
- Upload-Artefakte mit Test-Reports

### SonarQube Dashboard
- **SonarCloud:** [https://sonarcloud.io/project/overview?id=FishIT-Mapper](https://sonarcloud.io/project/overview?id=FishIT-Mapper)
- **Self-Hosted:** `https://deine-sonarqube-url/dashboard?id=dein_project_key`

Dort findest du:
- Code Quality Metriken
- Bugs & Vulnerabilities
- Code Smells
- Test Coverage
- Duplikationen

## 📊 Was wird analysiert?

Der Workflow analysiert folgende Module:

| Modul | Pfad | Beschreibung |
|-------|------|--------------|
| **Android App** | `androidApp/src/main` | Hauptanwendung (Compose UI) |
| **Contract** | `shared/contract/src` | Generierte Domain Contracts |
| **Engine** | `shared/engine/src` | Core Business Logic |
| **Codegen** | `tools/codegen-contract/src` | Contract Code Generator |

### Spezielle Features:

✅ **Contract Generation**: Automatische Generierung vor der Analyse  
✅ **Multi-Modul Support**: Alle Module werden analysiert  
✅ **Debug Reporting**: Umfassende Debug-Informationen bei Fehlern  
✅ **Build Artifacts**: Test-Reports und APK werden hochgeladen  
✅ **Dependency Analysis**: Prüfung der Contract-Typ-Verwendung  
✅ **Import Consistency**: Validierung der Import-Konsistenz  

## 🔍 Debug-Report Features

Bei jedem Workflow-Lauf wird automatisch ein Debug-Report erstellt, der Folgendes enthält:

- 📦 **Projekt-Struktur**: Alle Module und ihre Build-Dateien
- 📁 **Generierter Code**: Verifizierung der Contract-Generierung
- 🔧 **Build-Ergebnisse**: Status aller Kompilierungen
- ⚠️ **Compilation Issues**: Unresolved References und Fehler
- 📊 **Code Metrics**: LOC, Datei-Anzahl pro Modul
- 🔗 **Dependency Analysis**: Verwendung von Contract-Typen
- 🧪 **Test Results**: Test-Reports aller Module

Diese Reports werden als Artifacts gespeichert und können 14 Tage lang heruntergeladen werden.

## ❓ Troubleshooting

### Fehler: "SONAR_TOKEN not found"
- Überprüfe ob beide Secrets korrekt angelegt sind
- Secrets müssen EXAKT `SONAR_TOKEN` und `SONAR_HOST_URL` heißen
- Token muss gültig sein (nicht abgelaufen)

### Fehler: "Project not found"
- Überprüfe den `projectKey` in der Workflow-Datei
- Stelle sicher, dass das Projekt in SonarCloud/SonarQube existiert

### Fehler: "Contract generation failed"
- Überprüfe die Schema-Datei: `schema/contract.schema.json`
- Schaue in die Debug-Reports für Details

### Build schlägt fehl
- Überprüfe die Debug-Reports im Artifacts-Bereich
- Schaue nach "Unresolved references" im Report
- Prüfe ob alle Dependencies korrekt sind

## 📚 Weitere Ressourcen

- [SonarCloud Dokumentation](https://docs.sonarcloud.io/)
- [SonarQube Gradle Plugin](https://docs.sonarsource.com/sonarqube/latest/analyzing-source-code/scanners/sonarscanner-for-gradle/)
- [GitHub Actions Dokumentation](https://docs.github.com/en/actions)

## 🎉 Geschafft!

Nach erfolgreicher Einrichtung läuft die SonarQube-Analyse automatisch bei jedem Push oder Pull Request auf `main`. Die Ergebnisse sind dann in deinem SonarQube/SonarCloud Dashboard verfügbar.

Bei Fragen oder Problemen schaue in die Debug-Reports oder öffne ein Issue! 🚀
