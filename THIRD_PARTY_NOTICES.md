# Third-Party Notices

StarryPass Android (`io.github.lingyicute.starrypass`, Copyright (C) 2024-2026 lingyicute) is licensed under the GNU Affero General Public License v3.0 or later (`LICENSE`), consistent with [StarryPass](https://github.com/lingyicute/StarryPass).

This distribution incorporates the following audited open-source libraries under GPL/AGPL-compatible permissive licenses:

---

## 1. The Legion of the Bouncy Castle (`org.bouncycastle:bcprov-jdk18on:1.86`)

Used for RFC 9106 Argon2id v1.3 (`Argon2BytesGenerator`) and RFC 5869 HKDF-SHA-256 (`HKDFBytesGenerator`).

```text
Copyright (c) 2000 - 2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. Android Jetpack / AndroidX & Kotlin Standard Libraries

- `androidx.compose.*`, `androidx.material3`, `androidx.biometric`, `androidx.datastore`, `androidx.autofill`, `androidx.lifecycle`, `androidx.core`, `androidx.activity` — Copyright (C) The Android Open Source Project, licensed under the **Apache License, Version 2.0**.
- `org.jetbrains.kotlin:*`, `org.jetbrains.kotlinx:kotlinx-coroutines-*`, `org.jetbrains.kotlinx:kotlinx-serialization-json` — Copyright (C) JetBrains s.r.o., licensed under the **Apache License, Version 2.0**.
- `com.google.crypto.tink:tink-android` (unit test cross-verification only, not shipped in APK) — Copyright (C) Google LLC, licensed under the **Apache License, Version 2.0**.

Full text of the Apache License, Version 2.0 is available at <https://www.apache.org/licenses/LICENSE-2.0>.
