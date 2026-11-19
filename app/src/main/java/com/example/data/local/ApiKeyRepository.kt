package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ApiKeySource(val description: String) {
    NONE("No API key configured"),
    KEYSTORE_ENCRYPTED("Stored on-device (Android Keystore AES-256-GCM)"),
    BUILD_CONFIG_FALLBACK("BuildConfig development fallback (APK-embedded — do not distribute APK publicly)")
}

interface ApiKeyStorage {
    val hasApiKeyFlow: StateFlow<Boolean>
    val maskedApiKeyFlow: StateFlow<String>
    val keySourceFlow: StateFlow<ApiKeySource>
    suspend fun getApiKey(): String
    suspend fun saveApiKey(rawKey: String)
    suspend fun clearApiKey()
}

/**
 * BYOK local-only API key repository backed by Android Keystore AES-256-GCM encryption.
 *
 * Security Audit Notes:
 * 1. Runtime keys entered by the user are encrypted with a non-exportable 256-bit AES-GCM
 *    key generated inside the Android Keystore (`AndroidKeyStore`).
 * 2. Build-time fallback (`BuildConfig.GEMINI_API_KEY`) is supported for AI Studio emulator
 *    testing convenience, but `BuildConfig` values are compiled into DEX bytecode and can be
 *    extracted by decompiling the APK. [keySourceFlow] explicitly distinguishes
 *    [ApiKeySource.BUILD_CONFIG_FALLBACK] from [ApiKeySource.KEYSTORE_ENCRYPTED] so the UI
 *    warns the user never to share an APK containing a `BuildConfig` key.
 */
class ApiKeyRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cipherProvider: KeystoreCipherProvider = AndroidKeystoreCipherProvider(),
    private val allowBuildConfigFallback: Boolean = true
) : ApiKeyStorage {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _hasApiKeyFlow = MutableStateFlow(false)
    override val hasApiKeyFlow: StateFlow<Boolean> = _hasApiKeyFlow.asStateFlow()

    private val _maskedApiKeyFlow = MutableStateFlow("Not configured")
    override val maskedApiKeyFlow: StateFlow<String> = _maskedApiKeyFlow.asStateFlow()

    private val _keySourceFlow = MutableStateFlow(ApiKeySource.NONE)
    override val keySourceFlow: StateFlow<ApiKeySource> = _keySourceFlow.asStateFlow()

    init {
        refreshKeyStateSync()
    }

    override suspend fun getApiKey(): String = withContext(ioDispatcher) {
        resolveActiveKeyAndSourceSync().first
    }

    override suspend fun saveApiKey(rawKey: String) = withContext(ioDispatcher) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            clearApiKey()
            return@withContext
        }
        val encryptedPayload = cipherProvider.encrypt(trimmed.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encryptedPayload.ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(encryptedPayload.iv, Base64.NO_WRAP))
            .remove(KEY_USER_CLEARED)
            .commit()
        _hasApiKeyFlow.value = true
        _maskedApiKeyFlow.value = maskKey(trimmed)
        _keySourceFlow.value = ApiKeySource.KEYSTORE_ENCRYPTED
    }

    override suspend fun clearApiKey() = withContext(ioDispatcher) {
        prefs.edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .putBoolean(KEY_USER_CLEARED, true)
            .commit()
        _hasApiKeyFlow.value = false
        _maskedApiKeyFlow.value = maskKey("")
        _keySourceFlow.value = ApiKeySource.NONE
    }

    private fun refreshKeyStateSync() {
        val (activeKey, source) = resolveActiveKeyAndSourceSync()
        _hasApiKeyFlow.value = activeKey.isNotBlank()
        _maskedApiKeyFlow.value = maskKey(activeKey)
        _keySourceFlow.value = source
    }

    private fun resolveActiveKeyAndSourceSync(): Pair<String, ApiKeySource> {
        val stored = readDecryptedSync()
        if (stored.isNotBlank()) {
            return stored to ApiKeySource.KEYSTORE_ENCRYPTED
        }
        if (!allowBuildConfigFallback || prefs.getBoolean(KEY_USER_CLEARED, false)) {
            return "" to ApiKeySource.NONE
        }
        val injectedKey = runCatching { BuildConfig.GEMINI_API_KEY.trim() }.getOrDefault("")
        return if (isUsableInjectedSecret(injectedKey)) {
            injectedKey to ApiKeySource.BUILD_CONFIG_FALLBACK
        } else {
            "" to ApiKeySource.NONE
        }
    }

    private fun readDecryptedSync(): String {
        val ciphertextB64 = prefs.getString(KEY_CIPHERTEXT, null) ?: return ""
        val ivB64 = prefs.getString(KEY_IV, null) ?: return ""
        return try {
            val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val decryptedBytes = cipherProvider.decrypt(EncryptedPayload(ciphertext, iv))
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // If Keystore key was invalidated, clear corrupted entry safely without logging key material
            prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).commit()
            ""
        }
    }

    companion object {
        private const val PREFS_NAME = "screensight_secure_keystore_prefs"
        private const val KEY_CIPHERTEXT = "enc_gemini_api_key_ct"
        private const val KEY_IV = "enc_gemini_api_key_iv"
        private const val KEY_USER_CLEARED = "user_explicitly_cleared_key"
        private const val PLACEHOLDER_KEY = "MY_GEMINI_API_KEY"

        fun isUsableInjectedSecret(secret: String): Boolean {
            val clean = secret.trim()
            return clean.isNotEmpty() &&
                clean != PLACEHOLDER_KEY &&
                !clean.startsWith("YOUR_") &&
                isValidApiKeyFormat(clean)
        }

        fun maskKey(key: String): String {
            val clean = key.trim()
            if (clean.isEmpty()) return "Not configured"
            if (clean.length <= 8) return "••••••••"
            val prefix = clean.take(4)
            val suffix = clean.takeLast(4)
            return "$prefix••••••••••••$suffix"
        }

        fun isValidApiKeyFormat(key: String): Boolean {
            val trimmed = key.trim()
            return trimmed.length >= 20 && !trimmed.contains(" ")
        }
    }
}

data class EncryptedPayload(
    val ciphertext: ByteArray,
    val iv: ByteArray
)

interface KeystoreCipherProvider {
    fun encrypt(plaintext: ByteArray): EncryptedPayload
    fun decrypt(payload: EncryptedPayload): ByteArray
}

class AndroidKeystoreCipherProvider : KeystoreCipherProvider {
    override fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedPayload(ciphertext = ciphertext, iv = cipher.iv)
    }

    override fun decrypt(payload: EncryptedPayload): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
        return cipher.doFinal(payload.ciphertext)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) {
            return existingEntry.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ScreenSightGeminiApiKeyMasterKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
