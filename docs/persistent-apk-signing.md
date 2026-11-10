# Install future QCarDash APKs as updates

Android accepts an update only when **both** conditions hold:
1. The package ID is unchanged (currently `org.qcardash.launcher`).
2. The APK has the **same signing certificate** as the installed version, and the new `versionCode` is not lower.

GitHub-hosted runners use fresh machines each run. Their default debug signing keys are different, forcing a reinstall.

## One-time setup

A dedicated keystore was generated for QCarDash and delivered privately to its owner as `qcardash-signing-setup.zip` in the conversation. **Never commit it to the repository or share it.** Back it up safely; losing it means losing the ability to publish compatible updates.

1. Download and safely store `qcardash-signing-setup.zip`.
2. Extract `qcardash-github-secrets.txt`. It contains four secret names and corresponding values, including the Base64-encoded keystore.
3. Open [repository Actions secrets](https://github.com/host90-gist/dash-car-launcher-improvement/settings/secrets/actions) and add each name and value as a separate **repository secret**:
   - `QCARDASH_KEYSTORE_BASE64`
   - `QCARDASH_STORE_PASSWORD`
   - `QCARDASH_KEY_ALIAS`
   - `QCARDASH_KEY_PASSWORD`
4. Run the [Android 10 APK build workflow](https://github.com/host90-gist/dash-car-launcher-improvement/actions/workflows/android10-ci.yml) on branch `qcar-google-maps-sdk-test`, or push a commit to that branch.
5. Download the new `qcardash-android10-mapsforge-debug` build artifact.

The workflow will **fail with an explanatory error instead of silently creating a randomly signed APK** if the secrets have not been configured.

## First install: IMPORTANT

The APKs created *before* persistent signing were signed using a different, temporary debug key. Android won't let the new certificate replace an old installation, even if the package name and version are identical.

**Uninstall the old APK exactly once** (this can erase the app's settings and locally imported Mapsforge files; export or keep backup copies first). Install the new, consistently signed APK. From then on, install newer APKs over the older app without uninstalling.

The project uses a dynamic versionCode (`1000 + GITHUB_RUN_NUMBER`) and versionName (`1.2.RUN_NUMBER`) for GitHub builds to satisfy Android's update rules.

## Security and troubleshooting

Keep the keystore and its password backed up privately. Anyone possessing them can sign an update with the same identity. If GitHub secrets are deleted or replaced with another keystore, future APKs won't upgrade the existing install.

**Do not use the stock launcher signing key**, even if extracted from the original manufacturer's APK. QCarDash should have its own independent signing identity.

If Android shows “App not installed” despite the configured secrets, verify the old APK was signed with the new key, the application ID matches, and the versionCode increased. The one-time uninstall cannot be avoided when switching signing certificates.
