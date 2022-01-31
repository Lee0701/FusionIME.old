package io.github.lee0701.inputmethod.fusion

import android.content.Context
import android.content.SharedPreferences
import android.view.View

interface ViewManager {

    fun initView(context: Context): View

    fun setPreferences(sharedPreferences: SharedPreferences) {}

}