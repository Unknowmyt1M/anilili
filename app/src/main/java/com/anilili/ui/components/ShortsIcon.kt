package com.anilili.ui.components

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

val ShortsIcon: ImageVector
    get() {
        if (_shortsIcon != null) {
            return _shortsIcon!!
        }
        _shortsIcon = materialIcon(name = "ShortsIcon") {
            materialPath {
                moveTo(8.0f, 4.0f)
                lineTo(16.0f, 4.0f)
                curveTo(17.1f, 4.0f, 18.0f, 4.9f, 18.0f, 6.0f)
                lineTo(18.0f, 18.0f)
                curveTo(18.0f, 19.1f, 17.1f, 20.0f, 16.0f, 20.0f)
                lineTo(8.0f, 20.0f)
                curveTo(6.9f, 20.0f, 6.0f, 19.1f, 6.0f, 18.0f)
                lineTo(6.0f, 6.0f)
                curveTo(6.0f, 4.9f, 6.9f, 4.0f, 8.0f, 4.0f)
                close()
                moveTo(10.5f, 8.5f)
                lineTo(10.5f, 15.5f)
                lineTo(15.0f, 12.0f)
                close()
            }
        }
        return _shortsIcon!!
    }

private var _shortsIcon: ImageVector? = null
