# StarryPass for Android

[简体中文](./README.md) | **English**

> **Zero-compromise specification parity, built 100% on battle-tested, standard cryptographic libraries.**
> Every password derived by StarryPass Web v2 / v2.1 is **byte-for-byte identical** when derived by this Android client.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPLv3-blue.svg)](./LICENSE)
[![Platform: Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026--35)-3DDC84.svg)](#)
[![UI: Material You (M3)](https://img.shields.io/badge/UI-Material%20You%20(Monet)-6750A4.svg)](#)

![image](https://raw.githubusercontent.com/lingyicute/StarryPass/master/banner.jpg)

## 📦 Download

Releases and automatic **Nightly** builds are available on the GitHub **Releases** page. Every push to `main` runs the full compatibility test suite in GitHub Actions and publishes an unsigned `app-release-unsigned.apk`.

> [!IMPORTANT]
> The project does not host any signing key for Nightly builds (`app-release-unsigned.apk`). Sign the APK with your own key before installing it.

- **APK permissions**: declares only `USE_BIOMETRIC` / `USE_FINGERPRINT` (**no `INTERNET` permission and no storage read/write permission**; the merged manifest only adds AndroidX's internal signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).

---

## 🔐 Cryptographic Architecture & Compatibility

### 1. Primitive replacement table

| Component | Web v2 (PoC) | Web v2.1 (production) | **This repository (Android production)** | Audit and adoption |
| --- | --- | --- | --- | --- |
| **Argon2id v1.3** (RFC 9106) | Hand-written JS + WASM | `@noble/hashes 2.4.0` | **Bouncy Castle `1.86`** (`Argon2BytesGenerator`) | Industry-standard lightweight crypto API (`bcprov-jdk18on`), widely adopted across Spring Security, KeePassDX, and AOSP |
| **HKDF-SHA-256** (RFC 5869) | Hand-written loop | Web Crypto `deriveBits` | **Bouncy Castle `1.86`** (`HKDFBytesGenerator`) | Standard stateful RFC 5869 streaming implementation (cross-checked in unit tests with **Google Tink `1.24.0`**) |
| **SHA-256** (FIPS 180-4) | Hand-written | Web Crypto `digest` | **Android JCA** `MessageDigest("SHA-256")` | Backed by Google BoringSSL (Conscrypt) |
| **HMAC-SHA-256** (RFC 2104) | Hand-written | Web Crypto `sign` | **Android JCA** `Mac("HmacSHA256")` + `MessageDigest.isEqual` | BoringSSL plus constant-time digest comparison |
| **Session CSPRNG** | `getRandomValues` | `crypto.getRandomValues` | **Android JCA** `java.security.SecureRandom` | BoringSSL / Linux kernel `getrandom(2)` entropy |
| **Hardware master-password vault (optional)** | None | None | **Android Keystore** (`AES-256-GCM`, TEE / StrongBox) + `BiometricPrompt.CryptoObject` | Non-exportable hardware key; every decryption requires a Class 3 strong biometric (fingerprint or 3D face) |

> [!tip]
> Why not `libsodium` high-level APIs or `argon2kt`?
>
> On every Argon2id derivation StarryPass v2 supplies the RFC 9106 **associated data (`X`)** `StarryPass-v2:argon2id-ad:v1` with a fixed parallelism `p = 4`, and its power-on self-test checks the RFC 9106 §5.3 KAT vector that uses both the `secret (K)` and `ad (X)`. Some simplified wrappers (`libsodium`'s `crypto_pwhash` fixes `p = 1` and supports neither `K` nor `X`; the `argon2kt` JNI does not expose `ad` / `secret`) **produce results that differ from the web version**. Bouncy Castle's `Argon2Parameters.Builder` implements every RFC 9106 parameter (`withSalt` / `withSecret` / `withAdditional`) and sensitive-buffer zeroing (`clear()`).

### 2. Derivation specification

1. **Asymmetric canonicalisation**:
   - `master` (master password): **never trimmed** (leading/trailing spaces are kept); only Unicode `NFC` normalisation, then UTF-8 encoding.
   - `code` (site code): **trimmed first using the ECMA-262 whitespace set** (including `\uFEFF`, `\u3000`, `\u00A0`), then `NFC`-normalised, and **strictly case-sensitive**.
   - Unpaired UTF-16 surrogates become `U+FFFD`, exactly like the web `TextEncoder`. Older Android releases may have encoded such malformed input as `?`. If you ever used such input, recover your passwords with the old version and migrate before upgrading.
2. **Domain-separation constants (note the exact trailing NUL `\x00` bytes)**:
   - `DOMAIN_SALT` = `"StarryPass-v2:salt\x00"` (19 bytes)
   - `DOMAIN_AD` = `"StarryPass-v2:argon2id-ad:v1"` (28 bytes, no `\x00`)
   - `DOMAIN_EXTRACT` = `"StarryPass-v2:hkdf-extract:v1"` (29 bytes, no `\x00`)
   - `DOMAIN_EXPAND` = `"StarryPass-v2:hkdf-expand:v1\x00"` (29 bytes)
   - `DOMAIN_CACHE` = `"StarryPass-v2:session-cache-tag:v1\x00"` (35 bytes)
3. **Single-slot session cache + coroutine `Mutex` queue**:
   - An `HMAC-SHA-256` tag keyed with a per-session random nonce lets the engine cache the 32-byte session key `sk` without keeping the master password bytes resident. When `(master, code, tier)` is unchanged and only the rotation counter, length or charset changes, the cache is hit in **`< 1 ms`** and Argon2id is not re-run.
   - All `derive` and `wipeSession` calls are strictly serialised through one coroutine `Mutex`, which rules out wipe races.
4. **Zero-modulo-bias rejection sampling + deterministic Fisher-Yates fallback**:
   - Bytes are sampled without bias using `limit = 256 - (256 % n)`. Up to 128 rounds check that every required character class is present; if none succeeds, the same required-class injection plus Fisher-Yates shuffle as the web version is applied.
5. **Power-on cryptographic self-test (FIPS 140-3 style KAT)**:
   - Before the first derivation the app runs the **RFC 9106 §5.3 Argon2id KAT** and the **RFC 5869 Test Case 1 HMAC / HKDF KAT**. Derivation is refused if either fails.

### 3. Compatibility test suite

The web repository's test files are included unchanged in `app/src/test/resources/` and verified automatically by JUnit:

- ✅ **Web test vectors `vectors.json` (7 / 7)**: Chinese and English passphrases, codes with surrounding spaces, an NFC / NFD equivalence pair (`café` vs `cafe\u0301`), PIN mode, and the 256 MiB `paranoid` tier.
- ✅ **Web golden set `golden.json` (44 / 44)**: ligatures (`ﬁ`), emoji (`🦄🔒`), Arabic RTL, full-width characters, very long input, `counter` / `length` clamping and non-numeric fallback, default fallback for unknown `mode` / `tier`, and the `EMPTY_INPUT` error path.
- ✅ **Cross-library check**: Bouncy Castle `HkdfStream` multi-block output compared with Google Tink `Hkdf.computeHkdf`.

---

## 🎨 Material You Design & Android-native Features

- **Native Material You (Material 3) dynamic colour with smooth transitions**:
  - Android 12+ uses **Monet wallpaper colours** by default (`dynamicLightColorScheme` / `dynamicDarkColorScheme`). Android 8 – 11, or with dynamic colour turned off, fall back to the Starry Blue M3 palette (`#415F91` / `#AAC7FF`).
  - All colour slots animate smoothly between schemes. You can follow the system or force light / dark, and the app has an ambient radial-gradient background and an Android 13+ **monochrome adaptive icon** for themed launchers.
  - Quick length presets (`12` / `16` / `20` / `24` / `32`, or `4` / `6` / `8` / `12` in PIN mode, where switching to PIN from a length above 8 picks `6`) plus a **custom 4–64 character slider**, syntax-highlighted letters, digits and symbols, and a one-tap mask toggle.
  - Full **Simplified Chinese / English** resources and Android 13+ per-app language settings (`localeConfig`).
- **Layered Android system hardening**:
  - **OS-enforced zero network permission**: `AndroidManifest.xml` does not declare `INTERNET`, so the operating system blocks the app from opening any network sockets and keeps all derivations strictly offline.
  - **Screenshot and recents protection**: `WindowManager.LayoutParams.FLAG_SECURE` is on by default.
  - **Sensitive clipboard protection with auto-clear**: copies carry the `ClipDescription.EXTRA_IS_SENSITIVE` flag to hide plain text in the Android 13+ clipboard preview, and automatically clear when the countdown expires (verifying the SHA-256 digest while in the foreground to preserve unrelated copies, or clearing unconditionally in the background where Android 10+ restricts clipboard reads so passwords never linger).
  - **Wipe on background**: a `ProcessLifecycleOwner` observer reacts when the app moves to the background (ignoring configuration changes such as screen rotation), immediately clearing on-screen inputs, cancelling in-flight tasks, invalidating pending biometric callbacks, and zeroing the session key and HMAC tag as soon as any active Argon2 critical section completes.
- **Optional site policy presets (no master password stored)**:
  - You can stay 100% stateless, or save frequently used "code + alias + length + charset + rotation counter + tier + matching domain or package name" as local presets. Presets structurally contain no master password field, and a leaked site can be handled with a **one-tap `+1` rotation**.
- **Optional Android Keystore hardware biometric vault**:
  - Off by default (strict zero storage). When enabled, the master password is encrypted with a non-exportable hardware `AES-256-GCM` key in `AndroidKeyStore`, and `setInvalidatedByBiometricEnrollment(true)` prevents newly enrolled biometrics from gaining access.
  - Verifies hardware isolation via `KeyInfo` on enrollment and before every unlock (requiring TEE or StrongBox on Android 12+ and `isInsideSecureHardware` on Android 8–11), rejecting software-only or unverified keys.
  - Built on `BiometricPrompt` + `BIOMETRIC_STRONG` + `CryptoObject`, supporting every **fingerprint** sensor and Class 3 certified **3D face / iris** recognition per the Android CDD. (Ordinary single-camera 2D face unlock is Class 2 weak biometrics; the hardware Keystore does not let it unlock a `CryptoObject` key, which keeps the isolation truly hardware-backed.)
- **System Autofill service (Android Autofill Service + inline keyboard suggestions)**:
  - In browsers (the `webDomain` is extracted automatically) and third-party apps (the package name is extracted and matched against presets), a floating Material You sheet appears on password fields. You can switch presets or adjust policy parameters in the sheet, and with biometrics unlock the derivation runs on the spot and fills the target field, without going through the system clipboard.

### Honest security boundary

As a strictly offline, zero-storage-first cryptographic tool, we pair defense-in-depth engineering with transparent documentation of platform and architectural boundaries:

1. **Managed runtime & sensitive-buffer zeroization (JVM / Android ART)**:
   - Every sensitive `ByteArray` / `CharArray` owned by this project (canonical UTF-8 password bytes, salts, HKDF blocks, the session key `sk`, and Bouncy Castle `Argon2Parameters` buffers) is explicitly zeroed (`fill(0)`) in `finally` blocks.
   - Because Jetpack Compose text fields (`OutlinedTextField`) and Android `AutofillValue.forText` operate on immutable `java.lang.String` instances, and the ART garbage collector may relocate object copies during heap compaction, application code cannot overwrite transient string copies held inside the VM, JCA providers, or third-party input methods — a platform boundary shared by all software running on modern managed runtimes.
2. **Cryptographic libraries & self-test scope**:
   - This project builds on the standard Bouncy Castle release (`bcprov-jdk18on`) and Android platform JCA (`BoringSSL` / `Conscrypt`), running power-on RFC Known-Answer Tests (KAT) and the full web vector suite to ensure continuous algorithmic correctness and byte-for-byte parity (note: the general-purpose `bcprov-jdk18on` artifact is distinct from the separate [BC FIPS distribution](https://github.com/bcgit/bc-java), and this application has not separately undergone commercial FIPS certification or a third-party commercial audit).
3. **Operational guidance & trust boundaries**:
   - **Offline Autofill verification**: By design, the app has no network permission and does not fetch online Digital Asset Links; instead, the Autofill sheet displays both the reported web domain and the host package name so you can verify the target before unlocking.
   - **Clipboard vs. Autofill**: System clipboard contents remain visible to active input methods or clipboard managers granted access by the user, and background timer execution is bounded by the OS process lifecycle; prefer the system Autofill service for everyday sign-ins.
   - **Strength estimation & tier consistency**: Master-password strength scores are local heuristic estimates based on character space and common weak patterns — avoid famous quotations or public phrases; different KDF tiers (`64 MiB` / `256 MiB`) derive distinct passwords, so keep the original tier when recovering an existing password.

---

## 🛠️ Build & Test

Requirements: **JDK 17+** and the **Android SDK (API 35)**.

```bash
# 1. Run the full compatibility unit tests
#    (7 web vectors + 44 golden cases + RFC KAT self-test + Tink cross-check)
./gradlew testDebugUnitTest

# 2. Build the Debug and R8-optimised Release packages
./gradlew assembleDebug assembleRelease
```

---

## 📄 License

Copyright (C) 2024-2026 lingyicute

This project is released under the **GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)**; see [LICENSE](./LICENSE). Third-party open-source component notices are in [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md).
