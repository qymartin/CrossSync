package io.github.qymartin.crosssync.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User settings (plain SharedPreferences). */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var syncHour: Int
        get() = prefs.getInt("syncHour", 1)
        set(v) = prefs.edit().putInt("syncHour", v).apply()

    var syncMinute: Int
        get() = prefs.getInt("syncMinute", 0)
        set(v) = prefs.edit().putInt("syncMinute", v).apply()

    var enabledMetrics: Set<Metric>
        get() = prefs.getStringSet("metrics", null)
            ?.mapNotNull { Metric.fromKey(it) }?.toSet()
            ?: setOf(Metric.TOTAL_CALORIES)
        set(v) = prefs.edit().putStringSet("metrics", v.map { it.key }.toSet()).apply()

    var backfillQueued: Boolean
        get() = prefs.getBoolean("backfillQueued", false)
        set(v) = prefs.edit().putBoolean("backfillQueued", v).apply()

    var displayName: String?
        get() = prefs.getString("displayName", null)
        set(v) = prefs.edit().putString("displayName", v).apply()

    /** Shown as a red banner until a later run succeeds. */
    var lastError: String?
        get() = prefs.getString("lastError", null)
        set(v) = prefs.edit().putString("lastError", v).apply()

    /** True when the banner's fix is "sign in again" rather than "retry". */
    var lastErrorNeedsLogin: Boolean
        get() = prefs.getBoolean("lastErrorNeedsLogin", false)
        set(v) = prefs.edit().putBoolean("lastErrorNeedsLogin", v).apply()

    var lastDailyRunAt: Long
        get() = prefs.getLong("lastDailyRunAt", 0)
        set(v) = prefs.edit().putLong("lastDailyRunAt", v).apply()

    var nextRunAt: Long
        get() = prefs.getLong("nextRunAt", 0)
        set(v) = prefs.edit().putLong("nextRunAt", v).apply()
}

/** Small string store encrypted with a key that never leaves the Android Keystore. */
class SecureStore(context: Context, name: String) {
    private val prefs = context.applicationContext.getSharedPreferences("secure_$name", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun put(k: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(k).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit().putString(k, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(k: String): String? = try {
        prefs.getString(k, null)?.let {
            val blob = Base64.decode(it, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(cipher.doFinal(blob, 12, blob.size - 12))
        }
    } catch (e: Exception) {
        null
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val ALIAS = "crosssync"
    }
}
