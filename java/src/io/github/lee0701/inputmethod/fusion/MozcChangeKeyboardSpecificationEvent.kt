package io.github.lee0701.inputmethod.fusion

import android.view.KeyEvent
import org.mozc.android.inputmethod.japanese.keyboard.Keyboard

data class MozcChangeKeyboardSpecificationEvent(
    val keyboardSpecification: Keyboard.KeyboardSpecification,
    override val keyCode: Int = 0,
    override val nativeEvent: KeyEvent? = null,
): FusionKeyEvent