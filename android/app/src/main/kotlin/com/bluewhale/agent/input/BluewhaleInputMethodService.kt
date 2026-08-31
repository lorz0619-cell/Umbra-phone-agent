package com.bluewhale.agent.input

import android.inputmethodservice.InputMethodService
import android.view.View

class BluewhaleInputMethodService : InputMethodService() {
    companion object {
        @Volatile
        private var instance: BluewhaleInputMethodService? = null

        fun commit(text: String): Boolean {
            val service = instance ?: return false
            val connection = service.currentInputConnection ?: return false
            return connection.commitText(text, 1)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onCreateInputView(): View? = null
}