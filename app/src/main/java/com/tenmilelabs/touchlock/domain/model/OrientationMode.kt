package com.tenmilelabs.touchlock.domain.model

import androidx.annotation.StringRes
import com.tenmilelabs.touchlock.R

enum class OrientationMode(@StringRes val titleRes: Int) {
    PORTRAIT(R.string.orientation_portrait),
    LANDSCAPE(R.string.orientation_landscape),
    FOLLOW_SYSTEM(R.string.orientation_auto)
}
