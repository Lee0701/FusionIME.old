package io.github.lee0701.inputmethod.fusion.input

import io.github.lee0701.inputmethod.fusion.FusionIME

sealed interface InputResult {

    fun process(ime: FusionIME)

}