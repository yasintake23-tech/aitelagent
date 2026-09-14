# Build Fix Notes

## Toolchain
- Android Gradle Plugin: 9.1.1
- Gradle Wrapper: 9.3.1
- JDK: 17
- compileSdk/targetSdk: 36
- Kotlin: 2.2.10 (AGP 9 built-in Kotlin)
- KSP: 2.2.10-2.0.2 (matched to AGP 9 built-in Kotlin)

## CI hardening
- GitHub Actions explicitly installs Android API 36 and Build Tools 36.0.0.
- Configuration cache is disabled to avoid plugin/annotation-processing cache instability while stabilizing CI.
- Debug keystore generation remains deterministic.

## Why KSP was changed
AGP 9.x uses built-in Kotlin 2.2.10. Android's AGP documentation states that this is the bundled KGP and identifies KSP 2.2.10-2.0.2 as the matching baseline.
