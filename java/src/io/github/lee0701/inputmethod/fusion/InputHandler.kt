package io.github.lee0701.inputmethod.fusion

import android.content.SharedPreferences

interface InputHandler {

    val listener: Listener

    fun onKeyEvent(keyEvent: FusionKeyEvent)
    fun onConversionCandidateSelected(candidateId: Int, rowIndex: Int?)

    fun setPreferences(sharedPreferences: SharedPreferences) {}

    interface Listener {

        fun onResult(inputResult: InputResult)

    }
}