package io.github.lee0701.inputmethod.fusion

import android.view.inputmethod.InputConnection

interface FusionIME {
    val inputViewShown: Boolean

    val inputConnection: InputConnection?
    var inputViewManager: InputViewManager
    var inputHandler: InputHandler

    fun requestHideSelf(flags: Int)

    fun sendEditorAction(fromEnterKey: Boolean): Boolean
}