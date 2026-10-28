/*
 * StarryPass Android — Keystore hardware isolation policy
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.security

import android.os.Build
import android.security.keystore.KeyProperties

internal object KeystoreHardwarePolicy {
    fun isHardwareBacked(sdk: Int, securityLevel: Int?, insideSecureHardware: Boolean?): Boolean =
        if (sdk >= Build.VERSION_CODES.S) {
            securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            insideSecureHardware == true
        }
}
