/*
 * StarryPass Android — Shared Context → Activity resolution
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Unwraps [ContextWrapper] chains (Compose `LocalContext`, themed/inspection wrappers) down to
 * the hosting [Activity]. Returns null for a null `baseContext` or a self-referencing wrapper so
 * window-level effects degrade gracefully instead of crashing or looping.
 */
internal tailrec fun Context.findActivity(): Activity? {
    if (this is Activity) return this
    val base = (this as? ContextWrapper)?.baseContext ?: return null
    if (base === this) return null
    return base.findActivity()
}
