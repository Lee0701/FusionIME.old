package io.github.lee0701.inputmethod.fusion

import android.view.inputmethod.InputConnection
import io.github.lee0701.inputmethod.fusion.input.InputHandler
import io.github.lee0701.inputmethod.fusion.view.InputViewManager

interface FusionIME {
    val inputViewShown: Boolean

    val inputConnection: InputConnection?
    var inputViewManager: InputViewManager
    var inputHandler: InputHandler

    fun reset()

    fun hideWindow()

    fun sendEditorAction(fromEnterKey: Boolean): Boolean
}