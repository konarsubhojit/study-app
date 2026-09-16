# Play Console setup and app signing

Everything that is done **once**, before StudyFlow can be published, and the procedure for the
things that must be repeatable afterwards — building a signed bundle, and recovering the signing
keys. Issue [#71](https://github.com/konarsubhojit/study-app/issues/71).

> No key material, password, or service-account file ever enters this repository. Every file that
> could carry one (`*.jks`, `*.keystore`, `keystore.properties`, `play-service-account*.json`) is
> listed in [`.gitignore`](../../.gitignore), and CI scans every push for committed secrets.

## 1. Identity: the application id is final

| Field | Value |
|---|---|
| Application id / package name | `dev.studyflow.app` |
| App name | StudyFlow |
| Default language | English (United Kingdom) |

The application id **cannot be changed after the first upload**: Play treats it as the app's
identity, and a different id is a different app that existing installs cannot update to. It is
derived from the `:app` module namespace by
[`AndroidApplicationConventionPlugin`](../../build-logic/convention/src/main/kotlin/dev/studyflow/buildlogic/AndroidApplicationConventionPlugin.kt),
so the manifest and the Play listing cannot drift apart.

`versionCode` and `versionName` default to `1` and `0.1.0` for local builds and are supplied per
run by the release workflow. Play rejects a bundle whose version code it has already accepted, so
every upload — including a replacement for a failed one — needs a higher code than the last.

## 2. Create the app in Play Console

1. **All apps → Create app**: name `StudyFlow`, default language English (UK), type *App*, free.
   Accept the developer programme policies and US export laws declarations.
2. Complete **Policy → App content**: privacy policy URL, ads declaration (StudyFlow serves no
   ads), app access, content rating questionnaire, target audience, data safety, and government
   apps. [`store-listing.md`](store-listing.md) holds the prepared answers.
3. **Test and release → Setup → App integrity → App signing**: keep **Play App Signing** enabled
   (the default for new apps). Google then holds the *app signing key* and re-signs every bundle;
   what we hold and must protect is the *upload key*.

## 3. Generate the upload key

Once, on a trusted machine. A 4096-bit RSA key with a validity long enough to outlive the app
(Play requires validity until at least 2033; 10 000 days is about 27 years):

```bash
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -storetype JKS \
  -alias studyflow-upload \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=StudyFlow, OU=StudyFlow, O=StudyFlow, C=GB"
```

Record the keystore password, key alias and key password in the shared password manager **at the
moment they are chosen** — they cannot be recovered from the keystore.

Keep `upload-keystore.jks` outside the repository working tree. It is git-ignored, but the only
safe habit is for it never to be inside the clone in the first place.

## 4. Store the credentials

| Where | What | Why |
|---|---|---|
| Password manager, shared vault, at least two admins | keystore file (attachment), keystore password, key alias, key password, certificate fingerprint | The recoverable copy of record |
| Offline backup: encrypted USB or printed base64 in a safe | keystore file | Survives loss of the vault |
| GitHub environment `play-internal` | `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD` | Lets CI sign without a human handling the key |

```bash
base64 -w0 upload-keystore.jks           # value for the UPLOAD_KEYSTORE_BASE64 secret
keytool -list -v -keystore upload-keystore.jks -alias studyflow-upload   # fingerprint to record
```

### Recovery procedure

- **Restoring from backup** — decode the vault attachment to a file outside the clone, confirm it
  with `keytool -list -v` against the recorded SHA-256 fingerprint, then re-create the four GitHub
  secrets from it.
- **Upload key lost or compromised** — not fatal. In Play Console use *App integrity → App
  signing → Request upload key reset*, upload a PEM of the new key's certificate, and Google
  accepts the new upload key within about 48 hours. Then replace the GitHub secrets and the vault
  entries and delete the old ones.
- **App signing key** — held by Google under Play App Signing, so losing every copy of our upload
  key never orphans the app or its installs. That is precisely why Play App Signing stays enabled.
- Anyone leaving the project is removed from the vault and from the `play-internal` environment;
  the upload key is reset if they ever held the raw file.

## 5. Build a signed bundle

CI is the normal path. Run the **Release bundle** workflow
([`.github/workflows/release.yml`](../../.github/workflows/release.yml)) from the Actions tab with
a version name and a version code. It decodes the keystore into `RUNNER_TEMP` — never the
workspace — builds `:app:bundleRelease`, deletes the keystore, and publishes the AAB and the R8
`mapping.txt` as a run artifact.

Locally, for a one-off or when CI is unavailable, put the credentials in a `keystore.properties`
file in the repository root (git-ignored):

```properties
storeFile=/absolute/path/outside/the/repo/upload-keystore.jks
storePassword=…
keyAlias=studyflow-upload
keyPassword=…
```

```bash
./gradlew :app:bundleRelease -Pstudyflow.requireReleaseSigning=true \
  -Pstudyflow.versionName=0.1.0 -Pstudyflow.versionCode=1
```

The same four values may be supplied as the `STUDYFLOW_UPLOAD_STORE_FILE`,
`STUDYFLOW_UPLOAD_STORE_PASSWORD`, `STUDYFLOW_UPLOAD_KEY_ALIAS` and
`STUDYFLOW_UPLOAD_KEY_PASSWORD` environment variables instead.

With no credentials at all the release build type stays **unsigned**, so a fresh clone can still
run `assembleRelease`. `-Pstudyflow.requireReleaseSigning=true` turns that convenience off: missing
credentials then fail the build rather than yielding an artifact that cannot be uploaded. Half a
credential set is an error either way.

Verify what was produced before uploading:

```bash
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
```

## 6. Internal testing track

1. **Test and release → Testing → Internal testing → Create new release**.
2. Upload the AAB from the workflow artifact, and `mapping.txt` alongside it so crash stack traces
   are deobfuscated.
3. Release name `<versionName> (<versionCode>)`; release notes say what a tester should exercise.
4. **Testers** tab: create the *StudyFlow internal* email list (up to 100 testers), add the
   maintainers, save, and share the opt-in link. A tester must accept the invitation before the
   Play Store offers them the app.
5. Roll out. Availability takes a few minutes, and installation is through the Play Store, so the
   install exercises the real Play App Signing signature rather than a locally signed APK.

### Release checklist

- [ ] `versionCode` is higher than every code already uploaded.
- [ ] Workflow run is green and its artifact contains an `.aab` and `mapping.txt`.
- [ ] `keytool -printcert -jarfile` shows the expected upload certificate fingerprint.
- [ ] Bundle uploaded to internal testing and installed from the Play Store on a real device.
- [ ] Store listing and App content declarations show no "action required" in Console.
