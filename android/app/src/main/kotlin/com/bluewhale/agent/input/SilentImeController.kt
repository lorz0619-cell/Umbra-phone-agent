package com.bluewhale.agent.input

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.bluewhale.agent.virtualdisplay.ShizukuVirtualDisplayClient

object SilentImeController {
    private const val TAG = "SilentIme"
    private const val IME_ID = "com.bluewhale.agent/.input.BluewhaleInputMethodService"

    private val client = ShizukuVirtualDisplayClient()

    @Volatile
    private var previousIme: String? = null

    @Volatile
    private var active = false

    fun prepare(context: Context): Boolean {
        if (active) return true
        val available = client.isAvailable()
        val permitted = client.hasPermission()
        Log.i(TAG, "prepare available=$available permitted=$permitted previous=$previousIme")
        if (!available || !permitted) return false

        previousIme =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            )

        val enabled =
            client.executeShellCommand(arrayOf("ime", "enable", IME_ID)) == 0 ||
                client.executeShellCommand(arrayOf("ime", "enable", IME_ID)) == 0
        val switched = client.executeShellCommand(arrayOf("ime", "set", IME_ID)) == 0
        Log.i(TAG, "prepare enabled=$enabled switched=$switched")
        active = enabled && switched
        return active
    }

    fun restore() {
        Log.i(TAG, "restore active=$active previous=$previousIme")
        if (!active) return
        val previous = previousIme
        if (!previous.isNullOrBlank()) {
            client.executeShellCommand(arrayOf("ime", "set", previous))
        }
        active = false
        previousIme = null
    }

    suspend fun <T> withSilentIme(context: Context, block: suspend () -> T): T {
        val prepared = prepare(context)
        try {
            return block()
        } finally {
            if (prepared) restore()
        }
    }

    fun commit(text: String): Boolean = BluewhaleInputMethodService.commit(text)
}