package com.example.smswebhookforwarder

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class BuildStore(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "build_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getPat(): String = prefs.getString(KEY_PAT, "").orEmpty()
    fun savePat(pat: String) { prefs.edit().putString(KEY_PAT, pat.trim()).apply() }
    fun hasPat(): Boolean = getPat().isNotBlank()

    companion object {
        private const val KEY_PAT = "github_pat"
    }
}
