# SonarQube Android Lint Integration Fix

## Problem

SonarQube zeigte Warnungen beim Importieren von Android Lint Reports:

```
Unable to import Android Lint report file(s):
- /path/to/androidApp/build/reports/lint-results-debug.xml
The report file(s) can not be found.
```

## Root Cause

Android Lint generiert standardmäßig **nur HTML-Reports**. SonarQube erwartet jedoch **XML-Reports**, um Lint-Issues zu importieren.

Die Workflow-Logs zeigten:
```
Wrote HTML report to file:///path/to/androidApp/build/reports/lint-results-debug.html
```

Aber keine XML-Reports wurden generiert.

## Solution

Für jedes Android-Modul (`androidApp`, `shared:contract`, `shared:engine`) wurde die Lint-Konfiguration in `build.gradle.kts` erweitert:

```kotlin
android {
    // ... existing config ...
    
    lint {
        // Enable XML output for SonarQube import
        xmlReport = true
        xmlOutput = file("build/reports/lint-results-debug.xml")
        
        // Also keep HTML for human-readable reports
        htmlReport = true
        htmlOutput = file("build/reports/lint-results-debug.html")
        
        // Don't abort build on lint errors
        abortOnError = false
    }
}
```

## Affected Files

- `androidApp/build.gradle.kts`
- `shared/contract/build.gradle.kts`
- `shared/engine/build.gradle.kts`

## Verification

After this fix:
1. Lint generates both XML and HTML reports
2. SonarQube can successfully import Android Lint issues
3. No more "Unable to import" warnings for actual Android modules

## Additional Notes

You may still see warnings for non-Android modules like `tools:codegen-contract`. This is expected and harmless, as these modules don't have Android Lint enabled.

## References

- [Android Lint Documentation](https://developer.android.com/studio/write/lint)
- [SonarQube Android Lint Integration](https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/importing-external-issues/importing-third-party-issues/)
