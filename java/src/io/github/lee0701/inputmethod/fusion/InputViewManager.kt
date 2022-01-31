package io.github.lee0701.inputmethod.fusion

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.Window

interface InputViewManager: ViewManager {

    val listener: Listener

    fun computeInsets(context: Context, outInsets: InputMethodService.Insets?, window: Window?)
    fun hideSubInputView(): Boolean

    interface Listener {

        fun onKeyEvent(keyEvent: FusionKeyEvent)

    }
}