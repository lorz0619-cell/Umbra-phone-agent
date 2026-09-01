# Public release checklist

## Before tagging

1. Confirm `git status` contains only intended source, documentation and deletion changes.
2. Run:

   ```powershell
   cd android
   .\gradlew.bat clean :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
   ```

3. Confirm the generated Release `BuildConfig.DEFAULT_API_KEY` is empty.
4. Scan both the repository snapshot and extracted APK for API keys, tokens, keystores, local
   paths, device serials, addresses and test logs.
5. Sign with the project's long-lived private release key. Never use the Android debug key for a
   public artifact and never commit the keystore or passwords.
6. Verify the signature and install the signed APK on a clean test device.
7. Record SHA-256 checksums for every uploaded binary.

## GitHub assets

- Signed universal APK.
- SHA-256 checksum file.
- Release notes from `docs/releases/v2.0.0.md`.

The unsigned Gradle APK is a build intermediate and must not be offered as the installable asset.
Future updates must use the same signing key.

## Android developer verification

Self-signing the APK does not require prior approval. For distribution exclusively outside Google
Play, plan to register the developer identity, package name `com.bluewhale.agent` and signing key
through Android Developer Console before the broader 2027 verification rollout. This registration
is separate from APK signing and must never require uploading the private keystore.

## Repository policy

- `android/local.properties`, `.env`, keystores, signing properties, logs, JSONL traces,
  temporary screenshots and build outputs remain ignored.
- Release builds never inherit `umbra.apiKey` from `android/local.properties`.
- Detailed Agent and input logs are permitted only in Debug builds.
