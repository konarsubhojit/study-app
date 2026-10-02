# Backend setup

Use Java 21 and the Android SDK described in [CONTRIBUTING](../CONTRIBUTING.md).

The `production` flavour requires backend configuration for **both debug and release**. There
are no fallback URLs. Supply the deployed Supabase project ref:

```bash
./gradlew installProductionDebug -Pstudyflow.supabaseProjectRef=<project-ref>
```

Alternatively set `STUDYFLOW_SUPABASE_PROJECT_REF`. The Gradle property takes precedence over
the environment variable. The ref derives these service URLs:

- API: `https://<project-ref>.supabase.co/functions/v1/api`
- Storage: `https://<project-ref>.supabase.co/functions/v1/storage`

Each explicit per-service override takes precedence over its derived URL. Without a project
ref, **both** overrides are required, for example for locally running backends:

```bash
./gradlew installProductionDebug \
  -Pstudyflow.apiBaseUrl=http://10.0.2.2:8080 \
  -Pstudyflow.storageBaseUrl=http://10.0.2.2:54321/functions/v1/storage
```

An unconfigured production build fails during Gradle configuration with the property and
environment variable names to supply. This also applies to aggregate tasks such as `check` and
`assemble`, which include production variants. For example:

```bash
./gradlew check -Pstudyflow.supabaseProjectRef=<project-ref>
./gradlew assembleProductionRelease -Pstudyflow.supabaseProjectRef=<project-ref>
```

The offline `mock` flavour performs no network I/O and needs no backend settings:

```bash
./gradlew installMockDebug
```

A backend host that cannot resolve is a non-retryable service failure, not “Pending upload”
or “Waiting for Wi-Fi”. The material shows: “StudyFlow can't reach its service. Your data is
safe on this device. Contact support.” Automatic retries stop and the staged file is retained.
Ordinary dropped connections and timeouts remain retryable.

See [Backend deployment](../infra/README.md) for deploying the API and storage Edge Functions.
