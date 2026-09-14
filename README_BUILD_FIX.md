# Build Fix

## Root cause
GitHub Actions reached Gradle successfully, but `app/build.gradle.kts` failed during Kotlin DSL script compilation:

- line 60: `kotlin {` -> unresolved reference
- line 61: `jvmToolchain(17)` -> unresolved reference

The module was applying the Kotlin Compose compiler plugin but not the Kotlin Android plugin, so the Kotlin Android Gradle extension was not available to the module script.

## Fix
Added `org.jetbrains.kotlin.android` (Kotlin 2.2.10) to the version catalog and applied it to the root/app plugin blocks. The existing Java/Kotlin JVM 17 configuration is retained.

## Verification
The exact previous CI log was inspected and the fix targets the reported script-compilation error directly. A local Gradle build could not be executed in this sandbox because outbound network access prevents downloading the Gradle 8.13 distribution; GitHub Actions has network access and the workflow's SDK/toolchain setup is already in place.
