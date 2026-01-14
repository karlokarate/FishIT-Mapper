# GitHub Copilot Anweisungen für FishIT-Mapper

## Sprachpräferenz
**Deutsch** ist die bevorzugte Sprache für alle Erklärungen, Kommentare und Dokumentation. Code kann auf Englisch sein, aber Erklärungen sollten auf Deutsch erfolgen.

## Long Context Nutzung
- **IMMER** den vollständigen verfügbaren Kontext nutzen für bessere und präzisere Antworten
- Bei komplexen Aufgaben alle relevanten Dateien und deren Zusammenhänge berücksichtigen
- Historischen Kontext aus vorherigen Interaktionen einbeziehen
- Projekt-weite Muster und Konventionen verstehen und anwenden

## Automatische Tool-Empfehlungen
Bei **jeder Aufgabe** automatisch Tools vorschlagen, die helfen können:

### Code-Qualität & Analyse
- **Linting**: ktlint, detekt für Kotlin-Code
- **Formatierung**: ktlint für einheitlichen Code-Stil
- **Statische Analyse**: detekt für Code-Qualität und potenzielle Bugs
- **Dependency Updates**: Gradle Versions Plugin für Dependency-Management

### Testing & Debugging
- **Unit Tests**: JUnit, Kotest für Kotlin-Testing
- **Android Tests**: Espresso, Compose UI Testing
- **Coverage**: JaCoCo für Test-Coverage-Analyse
- **Debugging**: Android Studio Debugger, Logcat

### Build & CI/CD
- **Build Automation**: Gradle mit optimierten Build-Skripten
- **GitHub Actions**: Automatisierte CI/CD-Workflows
  - Automatische Tests bei Pull Requests
  - Code-Quality-Checks
  - Automatische Releases
  - Dependency-Scanning

### Dokumentation
- **Code Documentation**: KDoc für Kotlin-Code
- **Architecture Diagrams**: Mermaid für Architektur-Diagramme
- **API Documentation**: Dokka für API-Dokumentation

### Android Development
- **UI Development**: Jetpack Compose mit Material 3
- **Architecture**: Clean Architecture, MVI/MVVM Patterns
- **Dependency Injection**: Manuelles DI (wie im Projekt verwendet)
- **Persistence**: Kotlinx Serialization für JSON

### Code Generation
- **KotlinPoet**: Für automatische Code-Generierung (wie contract generation)
- **Annotation Processing**: Für Boilerplate-Reduzierung

## Proaktive Vorschläge

### Bei Code-Reviews
- **Automatisch vorschlagen**:
  - Potenzielle Bugs oder Edge Cases
  - Performance-Optimierungen
  - Sicherheitsprobleme
  - Code-Stil-Verbesserungen
  - Test-Coverage-Lücken
  - Dokumentations-Bedarf

### Bei Debugging
- **Automatisch vorschlagen**:
  - Relevante Logcat-Filter
  - Breakpoint-Strategien
  - Memory Profiling Tools
  - Network Inspection Tools

### Bei Testing
- **Automatisch vorschlagen**:
  - Fehlende Test-Cases
  - Edge Cases und Randbedingungen
  - Mock-Strategien
  - Test-Utilities und Helper-Funktionen

### Bei Dokumentation
- **Automatisch vorschlagen**:
  - Fehlende KDoc-Kommentare
  - README-Updates bei API-Änderungen
  - Architecture Decision Records (ADRs)
  - Sequenz-Diagramme für komplexe Flows

## Best Practices für FishIT-Mapper

### Architektur-Prinzipien
- **Contract-First**: Domain Contract wird aus `schema/contract.schema.json` generiert
- **Multiplatform**: Shared Code in `shared/`, Platform-spezifisch in `androidApp/`
- **Clean Architecture**: Trennung von Engine, Data Layer, UI Layer

### Code-Conventions
- Kotlin Coding Conventions befolgen
- Compose Best Practices für UI
- Immutable Data Structures bevorzugen
- Dependency Injection Pattern beibehalten

### Aufgaben-Zerlegung
Bei **komplexen Aufgaben**:
1. Aufgabe in kleinere, handhabbare Teile zerlegen
2. Für jeden Teil passende Tools und Agents vorschlagen
3. Reihenfolge und Abhängigkeiten klar machen
4. Fortschritt kontinuierlich tracken

### MCP Server Features
- **Nutze erweiterte MCP-Funktionen** für:
  - Repository-weite Code-Analysen
  - Multi-File-Refactorings
  - Dependency Graph Analysen
  - Build-Output-Analysen
  - Test-Result-Analysen

## Workflow-Optimierung

### Pull Request Workflow
1. **Branch-Naming**: `feature/`, `bugfix/`, `refactor/`, `docs/` Präfixe
2. **Commit Messages**: Conventional Commits Format
3. **PR Description**: Problem, Lösung, Testing, Breaking Changes
4. **Code Review**: Automatische Tool-Checks vor manueller Review

### Continuous Integration
- **Automatische Checks** bei jedem Push:
  - Gradle Build
  - Unit Tests
  - Lint Checks
  - Code Style Validation
  - Dependency Vulnerability Scanning

### Release Workflow
- **Semantic Versioning** befolgen
- **Change Logs** automatisch generieren
- **Release Notes** mit wichtigen Änderungen
- **APK/Bundle** automatisch bauen und anhängen

## Hilfreiche Befehle

```bash
# Build & Test
./gradlew build
./gradlew test
./gradlew :androidApp:assembleDebug

# Code Generation
./gradlew :shared:contract:generateFishitContract

# Linting
./gradlew ktlintCheck
./gradlew detekt

# Dependency Updates
./gradlew dependencyUpdates

# Clean Build
./gradlew clean build
```

## Sicherheit & Best Practices
- **Keine Secrets** im Code committen
- **Input Validation** bei allen User-Inputs
- **Secure Storage** für sensible Daten
- **HTTPS** für alle Netzwerk-Requests
- **Code Signing** für Release-Builds

## Zusammenarbeit mit Agents
- **GitHub Copilot Workspace** nutzen für Multi-File-Änderungen
- **Copilot Chat** für komplexe Fragen und Architekturdiskussionen
- **Copilot Agents** für automatisierte Tasks wie:
  - Code Reviews
  - Refactorings
  - Test-Generierung
  - Dokumentations-Updates

---

**Ziel**: Entwickler produktiver machen durch intelligente Tool-Vorschläge und proaktive Unterstützung bei allen Aspekten der Softwareentwicklung.
