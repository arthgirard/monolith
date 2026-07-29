package com.monolith.app.domain.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystemApp: Boolean,
)
