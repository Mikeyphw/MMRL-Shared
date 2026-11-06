# MMRL Shared Core · N05A

A deliberately **pure Kotlin/JVM** library for contracts both `Mikeyphw/MMRL:next` and `Mikeyphw/MMRL-LSPosed:main` can reuse.

## Scope

- Canonical GitHub repository, release tag and APK/ZIP asset URL identity (metadata only).
- SHA-256 digest parsing, computation, and verification (no APK signer claims).
- Exact GitHub Actions run/attempt/source identity and artifact owner matching.
- Locale-independent deterministic display name ordering.

No Android SDK, Compose, Vector, LSPosed, Magisk, KernelSU, network client, downloader, package installer, device/root operation, credentials or background services belong here. Never treat GitHub URL validation or SHA-256 alone as a signed APK or trusted publisher attestation.

## Toolchain and publication

- Kotlin JVM `2.2.0`; Gradle `8.14.3` verified distribution; Java toolchain `21`, JVM target `17` for Android consumers.
- Build and JVM tests run **on GitHub Actions only** in the user workflow. Do not run Gradle on Termux.
- `publishSharedCorePublicationToStagingRepository` generates a **local, unsigned Maven layout** uploaded as a CI artifact for qualification. It does not publish to a public package registry or create a release.
- `io.github.mikeyphw.mmrl:mmrl-shared-core:0.1.0-n05a` is a proposed coordinate, not a published dependency until staging qualification and a versioned release/publishing policy exist.

## Integration roadmap

1. **N05A:** standalone pure Kotlin source, JVM tests, GitHub CI, local staging publication, Devtool federation enrollment only.
2. **N05B:** use released/pinned API in MMRL-LSPosed, replace narrowly identified duplicate release identity and digest logic; prove regression tests.
3. **N05C:** use same API in MMRL `next` behind upstream-friendly adapter interfaces; no changes to privileged/module management APIs.
4. **N05D:** declare Devtool capability exports/requires, affected-target graphs and version locks, then qualify both apps against exactly the same library artifact SHA.

The library is independent of upstream MMRL; app branches retain their own ancestry and sync strategy. Avoid importing the shared library by copying its source into either app.
