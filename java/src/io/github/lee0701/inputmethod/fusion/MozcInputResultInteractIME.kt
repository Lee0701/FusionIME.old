package io.github.lee0701.inputmethod.fusion

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import org.mozc.android.inputmethod.japanese.KeycodeConverter
import org.mozc.android.inputmethod.japanese.MozcUtil

class MozcInputResultInteractIME(
    private val keyEvent: KeycodeConverter.KeyEventInterface,
): InputResult.InteractIME {

    override fun interactIME(ime: FusionIME) {
        val keyCode = keyEvent.keyCode
        // Some keys have a potential to be consumed from mozc client.
        if (maybeProcessBackKey(ime, keyCode) || maybeProcessActionKey(ime, keyCode)) {
            // The key event is consumed.
            return
        }

        val inputConnection = ime.inputConnection ?: return

        // Following code is to fallback to target activity.
        val nativeKeyEvent = keyEvent.nativeEvent.orNull()
        if (nativeKeyEvent != null) {
            // Meta keys are from this.onKeyDown/Up so fallback each time.
            if (KeycodeConverter.isMetaKey(nativeKeyEvent)) {
                inputConnection.sendKeyEvent(
                    createKeyEvent(
                        nativeKeyEvent, MozcUtil.getUptimeMillis(),
                        nativeKeyEvent.action, nativeKeyEvent.repeatCount
                    )
                )
                return
            }

            // Other keys are from this.onKeyDown so create dummy Down/Up events.
            inputConnection.sendKeyEvent(
                createKeyEvent(
                    nativeKeyEvent, MozcUtil.getUptimeMillis(), KeyEvent.ACTION_DOWN, 0
                )
            )
            inputConnection.sendKeyEvent(
                createKeyEvent(
                    nativeKeyEvent, MozcUtil.getUptimeMillis(), KeyEvent.ACTION_UP, 0
                )
            )
            return
        }

        // Otherwise, just delegates the key event to the connected application.
        // However space key needs special treatment because it is expected to produce space character
        // instead of sending ACTION_DOWN/UP pair.
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            inputConnection.commitText(" ", 0)
        } else {
            sendDownUpKeyEvents(inputConnection, keyCode)
        }
    }

    /**
     * @return true if the key event is consumed
     */
    private fun maybeProcessBackKey(ime: FusionIME, keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK || !ime.inputViewShown) {
            return false
        }

        // Special handling for back key event, to close the software keyboard or its subview.
        // First, try to hide the subview, such as the symbol input view or the cursor view.
        // If neither is shown, hideSubInputView would fail, then hide the whole software keyboard.
        if (!ime.inputViewManager.hideSubInputView()) {
            ime.requestHideSelf(0)
        }
        return true
    }

    private fun maybeProcessActionKey(ime: FusionIME, keyCode: Int): Boolean {
        // Handle the event iff the enter is pressed.
        return if (keyCode != KeyEvent.KEYCODE_ENTER || !ime.inputViewShown) {
            false
        } else ime.sendEditorAction(true)
    }

    private fun createKeyEvent(
        original: KeyEvent, eventTime: Long, action: Int, repeatCount: Int
    ): KeyEvent {
        return KeyEvent(
            original.downTime, eventTime, action, original.keyCode,
            repeatCount, original.metaState, original.deviceId, original.scanCode,
            original.flags
        )
    }

    private fun sendDownUpKeyEvents(inputConnection: InputConnection, keyEventCode: Int) {
        val eventTime = SystemClock.uptimeMillis()
        inputConnection.sendKeyEvent(
            KeyEvent(
                eventTime, eventTime,
                KeyEvent.ACTION_DOWN, keyEventCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
        inputConnection.sendKeyEvent(
            KeyEvent(
                eventTime, SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, keyEventCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

}