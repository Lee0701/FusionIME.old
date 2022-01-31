package io.github.lee0701.inputmethod.fusion

import android.view.KeyEvent

interface FusionKeyEvent {
    val keyCode: Int
    val nativeEvent: KeyEvent?
}