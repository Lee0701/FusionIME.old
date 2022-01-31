package io.github.lee0701.inputmethod.fusion

import android.inputmethodservice.InputMethodService
import android.preference.PreferenceManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class FusionIMEService: InputMethodService(), FusionIME, InputViewManager.Listener, InputHandler.Listener {

    override val inputViewShown: Boolean get() = isInputViewShown
    override val inputConnection: InputConnection? get() = currentInputConnection

    override lateinit var inputViewManager: InputViewManager
    override lateinit var inputHandler: InputHandler

    override fun onCreate() {
        super.onCreate()
        inputViewManager = MozcInputViewManager(this, this)
        inputHandler = MozcInputHandler(this, this)

        val sharedPreference = PreferenceManager.getDefaultSharedPreferences(this)
        inputViewManager.setPreferences(sharedPreference)
        inputHandler.setPreferences(sharedPreference)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        return inputViewManager.initView(this)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        super.onFinishInput()
    }

    override fun onComputeInsets(outInsets: Insets?) {
        inputViewManager.computeInsets(applicationContext, outInsets, window.window)
    }

    override fun onKeyEvent(keyEvent: FusionKeyEvent) {
        inputHandler.onKeyEvent(keyEvent)
    }

    override fun onResult(inputResult: InputResult) {
        inputResult.process(this)
    }

    override fun sendEditorAction(fromEnterKey: Boolean): Boolean {
        // If custom action label is specified (=non-null), special action id is also specified.
        // If there is no IME_FLAG_NO_ENTER_ACTION option, we should send the id to the InputConnection.
        val editorInfo = currentInputEditorInfo
        if (editorInfo != null && editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 && editorInfo.actionLabel != null) {
            val inputConnection = currentInputConnection
            if (inputConnection != null) {
                inputConnection.performEditorAction(editorInfo.actionId)
                return true
            }
        }
        // No custom action label is specified. Fall back to default EditorAction.
        return sendDefaultEditorAction(fromEnterKey)
    }

}