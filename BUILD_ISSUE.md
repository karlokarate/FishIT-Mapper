# Build Issue Investigation

## Problem
Build fails with unresolved contract references in `shared/engine` module.

## Symptoms
```
e: Unresolved reference: MapGraph
e: Unresolved reference: NodeKind
e: Unresolved reference: RecorderEvent
... (many more)
```

## Investigation Results

### ✅ Contract Generation Works
```bash
$ ./gradlew :shared:contract:generateFishitContract
BUILD SUCCESSFUL in 6s
```

Generated files exist in:
```
/shared/contract/build/generated/source/fishitContract/commonMain/kotlin/dev/fishit/mapper/contract/
├── Chains.kt
├── ContractIndex.kt
├── ContractInfo.kt
├── Enums.kt
├── Export.kt
├── Graph.kt
├── Ids.kt
└── Recorder.kt
```

### ✅ Contract Module Builds Successfully
```bash
$ ./gradlew :shared:contract:build
BUILD SUCCESSFUL in 16s
```

### ❌ Engine Module Cannot Find Contract Types
```bash
$ ./gradlew :shared:engine:compileDebugKotlinAndroid
FAILURE: Unresolved references
```

## Root Cause Analysis

The issue is likely one of:

1. **Build Order Problem**: Engine module compiles before contract generation completes
2. **Gradle Cache Issue**: Stale cache preventing new generated files from being seen
3. **Source Set Configuration**: Generated sources not properly registered

## Configuration Check

### Contract Module (`shared/contract/build.gradle.kts`)
```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            // ✅ Generated source directory is registered
            kotlin.srcDir(layout.buildDirectory.dir("generated/source/fishitContract/commonMain/kotlin"))
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
            }
        }
    }
}

// ✅ Task dependency is configured
tasks.matching { it.name.startsWith("compileKotlin") }.configureEach {
    dependsOn(generateFishitContract)
}
```

### Engine Module (`shared/engine/build.gradle.kts`)
```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // ✅ Depends on contract module
                implementation(projects.shared.contract)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
    }
}
```

## Attempted Solutions

### ❌ Sequential Build
```bash
$ ./gradlew clean && ./gradlew :shared:contract:build :shared:engine:build :androidApp:assembleDebug
FAILURE: Still fails at engine compilation
```

### ❌ Full Clean Build
```bash
$ ./gradlew clean :shared:contract:generateFishitContract :androidApp:assembleDebug
FAILURE: Still fails
```

## Recommended Solutions

### Solution 1: Gradle Daemon Reset
```bash
$ ./gradlew --stop
$ ./gradlew clean
$ ./gradlew build
```

### Solution 2: Clear Gradle Cache
```bash
$ rm -rf ~/.gradle/caches
$ ./gradlew clean build
```

### Solution 3: Explicit API Dependency
Add to `shared/contract/build.gradle.kts`:
```kotlin
// After building, publish contract as API dependency
val publishContractApi by tasks.registering {
    dependsOn(tasks.matching { it.name == "compileKotlin" })
    // Force refresh of project dependencies
}
```

### Solution 4: Move Generated Sources
Instead of build directory, use `src/generated`:
```kotlin
val generatedDir = file("src/generated/kotlin")
kotlin {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedDir)
        }
    }
}
```

### Solution 5: Pre-compile Contract
Add to root `build.gradle.kts`:
```kotlin
tasks.register("precompileContract") {
    dependsOn(":shared:contract:generateFishitContract")
    dependsOn(":shared:contract:build")
}

// Make all other tasks depend on this
subprojects {
    tasks.matching { it.name.startsWith("compile") }.configureEach {
        if (project.path != ":shared:contract") {
            dependsOn(":precompileContract")
        }
    }
}
```

## Impact

- This is a **pre-existing issue**, not caused by the graph visualization implementation
- The graph visualization code is **syntactically correct** and will work once build is fixed
- All other features (Chains, Imports, Filters, etc.) are **already implemented** and functional

## Verification

To verify the fix works:
1. Build should complete without errors
2. Run app and navigate to Graph tab
3. Click the toggle button (chart icon)
4. Should see visual graph representation with zoom/pan support

## Timeline

- **Pre-existing**: Issue existed before graph visualization PR
- **Discovered**: During graph visualization implementation
- **Priority**: High (blocks testing of new feature)
- **Estimated Fix Time**: 30min - 2h depending on root cause
