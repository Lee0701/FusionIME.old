package io.github.lee0701.inputmethod.fusion.event

import android.view.KeyEvent
import org.mozc.android.inputmethod.japanese.KeycodeConverter
import org.mozc.android.inputmethod.japanese.keyboard.Keyboard
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands

data class MozcKeyEvent(
    override val keyCode: Int,
    override val nativeEvent: KeyEvent?,
    val keyEvent: KeycodeConverter.KeyEventInterface?,
    val mozcKeyEvent: ProtoCommands.KeyEvent?,
    val keyboardSpecification: Keyboard.KeyboardSpecification?,
    val touchEventList: List<ProtoCommands.Input.TouchEvent>?,
): FusionKeyEvent