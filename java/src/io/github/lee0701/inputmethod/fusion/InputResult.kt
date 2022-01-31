package io.github.lee0701.inputmethod.fusion

import android.view.inputmethod.InputConnection

sealed interface InputResult {
    interface InteractIME: InputResult {
        fun interactIME(ime: FusionIME)
    }

    interface RenderInputConnection: InputResult {
        fun renderInputConnection(inputConnection: InputConnection?)
    }

    interface RenderInputView: InputResult {
        fun renderInputView(inputViewManager: InputViewManager)
    }

    interface RenderCandidateView: InputResult {
        fun renderCandidateView(candidateViewManager: CandidateViewManager)
    }

}