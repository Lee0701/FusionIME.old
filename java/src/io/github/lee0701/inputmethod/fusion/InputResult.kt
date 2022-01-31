package io.github.lee0701.inputmethod.fusion

import android.view.inputmethod.InputConnection

sealed interface InputResult {

    fun process(ime: FusionIME)

}