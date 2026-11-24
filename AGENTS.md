# Repository Guidelines

## Project Structure

`app/` is the Android application. Kotlin and Compose code lives in
`app/src/main/java/app/marxistlibrary/reader/`, with feature packages such as
`catalog/`, `reader/`, `sources/`, and `update/`. Reader scripts, bundled
PDF.js, and catalog data live in `app/src/main/assets/`. Android resources
are in `app/src/main/res/`. JVM tests are in `app/src/test/`, Room schema
exports in `app/schemas/`, and project artwork in `design/`.

## Build, Test, and Run

Use JDK 17 or later, Android SDK Platform 37, and Build Tools 37.0.0. Set
`sdk.dir` in `local.properties` for command-line builds. Run the Gradle
Wrapper from the repository root, using `.\gradlew.bat` in Windows PowerShell.

- `./gradlew :app:assembleDebug` builds the debug APK.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` installs it on a connected device or emulator.
- `./gradlew :app:testDebugUnitTest :app:lintDebug` runs JVM tests and Android Lint.
- `./gradlew :app:assembleRelease` builds the release APK for signing.

## Coding Style

Indent Kotlin with four spaces and match the surrounding JavaScript and CSS
style. Use `PascalCase` for Kotlin types and composables and `lowerCamelCase`
for other functions and properties. Place changes in the owning feature
package and reuse its models and entry points. Explain unusual archive
markup and reader behavior in comments. Manage dependency versions in
`gradle/libs.versions.toml`.

## Testing Guidelines

Use JUnit 4 in `*Test.kt` files and name methods for the behavior they verify.
Cover changed parsing, navigation, download, or update behavior with focused
regressions. For reader changes, check navigation and resume on a device or
emulator using HTML with footnotes and text or scanned PDFs as applicable.
For download changes, check interrupted transfers and offline opening.
Verify migration from existing databases and commit updated Room schema
exports when the database changes.

## Commits and Pull Requests

Use short, imperative subjects such as `Prepare Marxist Library Android
1.1.7`. Describe the change and verification in the pull request, link
related issues, and include screenshots for visible changes.

## Local Configuration and Releases

Keep local SDK settings, signing keys, and build artifacts outside Git.
Update `versionName`, `versionCode`, and `CHANGELOG.md` for releases. Tag the
matching source commit with `v<versionName>`, such as `v1.1.8` for an APK with
`versionName` `1.1.8`. The app checks the tag against the APK version during
updates. Attach the signed APK to the GitHub Release.
