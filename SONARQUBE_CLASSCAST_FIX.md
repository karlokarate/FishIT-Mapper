# SonarQube ClassCastException - Problem und Lösung

## 🔍 Problem-Diagnose

### Fehlermeldung
```
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':sonar'.
> class java.lang.String cannot be cast to class java.util.Collection 
  (java.lang.String and java.util.Collection are in module java.base of loader 'bootstrap')
```

### Ursache
Das SonarQube Gradle Plugin (Version 4.4.1.3373) erwartet für mehrteilige Pfad-Eigenschaften wie `sonar.sources`, `sonar.exclusions` und `sonar.cpd.exclusions` eine **Collection** (List oder Set), nicht einen komma-separierten String.

#### Vorher (❌ Falsch):
```kotlin
sonar {
    properties {
        property("sonar.sources",
            "androidApp/src/main/java," +
            "shared/contract/src/commonMain/kotlin," +
            "shared/contract/src/generated/kotlin"
        )
    }
}
```

In diesem Fall übergibt man einen String, und das Plugin versucht intern, diesen in eine Collection zu casten - was fehlschlägt.

## ✅ Lösung

### Änderungen in `build.gradle.kts`

Alle mehrteiligen Pfad-Eigenschaften wurden zu Listen konvertiert:

```kotlin
sonar {
    properties {
        // ✅ Richtig: Liste statt String
        property("sonar.sources", listOf(
            "androidApp/src/main/java",
            "shared/contract/src/commonMain/kotlin",
            "shared/contract/src/generated/kotlin",
            "shared/engine/src/commonMain/kotlin",
            "tools/codegen-contract/src/main/kotlin"
        ))
        
        property("sonar.exclusions", listOf(
            "**/build/**",
            "**/test/**",
            "**/androidTest/**",
            "**/*.json",
            "**/*.xml",
            "**/R.java",
            "**/R\$*.java",
            "**/BuildConfig.java",
            "**/Manifest.java"
        ))
        
        property("sonar.cpd.exclusions", listOf(
            "**/generated/**",
            "**/contract/src/generated/**"
        ))
    }
}
```

### Änderungen in `.github/workflows/sonarqube-analysis.yml`

Keine Änderungen am Workflow erforderlich. Der Workflow behält die "Build Source Paths" Logik für zukünftige Erweiterungen, verwendet sie aber aktuell nicht. Standardmäßig werden alle Module analysiert, wie in `build.gradle.kts` definiert.

## 🎯 Warum die letzten 5 Commits das Problem nicht beheben konnten

Die vorherigen Versuche haben sich auf andere Aspekte der Konfiguration konzentriert:
- Workflow-Parameter-Anpassungen
- Build-Schritte-Optimierung
- Dependency-Management

**Das Kernproblem wurde nicht erkannt**: Die Datentyp-Inkompatibilität zwischen String und Collection in der Gradle-Konfiguration.

## 📊 Technische Details

### SonarQube Gradle Plugin Verhalten

Das Plugin verwendet intern Reflection und Type-Casting für die Properties:

1. **Erwartet**: `Collection<String>` für Multi-Path-Properties
2. **Erhielt**: `String` (komma-separiert)
3. **Resultat**: ClassCastException beim Versuch, String zu Collection zu casten

### Betroffene Properties

Folgende SonarQube-Properties erwarten Collections:
- ✅ `sonar.sources` - Quell-Verzeichnisse
- ✅ `sonar.exclusions` - Ausschluss-Muster
- ✅ `sonar.cpd.exclusions` - Duplikats-Erkennungs-Ausschlüsse
- ⚠️ `sonar.inclusions` - falls verwendet
- ⚠️ `sonar.test.inclusions` - falls verwendet
- ⚠️ `sonar.coverage.exclusions` - falls verwendet

Single-Value-Properties können weiterhin als String übergeben werden:
- ✅ `sonar.projectKey`
- ✅ `sonar.projectName`
- ✅ `sonar.sourceEncoding`
- ✅ `sonar.android.lint.report`

## 🧪 Validierung

### Lokaler Test
```bash
./gradlew help --no-daemon
# ✅ BUILD SUCCESSFUL

./gradlew :sonar --dry-run --no-daemon
# ✅ BUILD SUCCESSFUL - sonar task wird erkannt
```

### CI/CD Test
Der Fix wird automatisch im nächsten Workflow-Run getestet:
1. Push/PR auf `main` Branch
2. Oder manuell via GitHub Actions UI

## 📚 Referenzen

- **SonarQube Gradle Plugin**: https://docs.sonarsource.com/sonarqube/latest/analyzing-source-code/scanners/sonarscanner-for-gradle/
- **Gradle DSL**: https://docs.gradle.org/current/dsl/
- **Kotlin Collections**: https://kotlinlang.org/docs/collections-overview.html

## 🎉 Erwartetes Ergebnis

Nach diesem Fix sollte der SonarQube-Task erfolgreich ausgeführt werden:

```
> Task :sonar

BUILD SUCCESSFUL in Xs
1 actionable task: 1 executed
```

Die Analyse-Ergebnisse werden dann auf SonarCloud verfügbar sein unter:
`https://sonarcloud.io/project/overview?id=<your-project-id>`
