package io.github.lee0701.inputmethod.fusion.input

import android.content.SharedPreferences
import io.github.lee0701.inputmethod.fusion.event.FusionKeyEvent

interface InputHandler {

    val listener: Listener

    fun onKeyEvent(keyEvent: FusionKeyEvent)
    fun onConversionCandidateSelected(candidateId: Int, rowIndex: Int?)

    fun setPreferences(sharedPreferences: SharedPreferences) {}

    interface Listener {

        fun onResult(inputResult: InputResult)

    }
}