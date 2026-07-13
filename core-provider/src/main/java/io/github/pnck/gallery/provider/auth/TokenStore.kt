package io.github.pnck.gallery.provider.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.pnck.gallery.provider.ProviderType

/** Persisted token set for one provider. */
data class StoredTokens(
    val accessToken: String,
    val accessExpiryEpochMs: Long,
    val refreshToken: String,
    /** Space-separated granted scopes — used to tell if the "My Drive" read grant is present. */
    val scope: String = "",
)

/**
 * Token persistence contract (PRD §5.2, §8.4.6): tokens live only here, never in
 * Room, never logged. Interface so the device-flow logic is unit-testable with an
 * in-memory fake; the production impl is [EncryptedTokenStore].
 */
interface TokenStore {
    fun read(provider: ProviderType): StoredTokens?
    fun write(provider: ProviderType, tokens: StoredTokens)
    fun clear(provider: ProviderType)
}

/** EncryptedSharedPreferences-backed store, one key set per provider. */
class EncryptedTokenStore(appContext: Context) : TokenStore {

    // EncryptedSharedPreferences is deprecated in security-crypto 1.1.0 with no
    // 1:1 replacement; the PRD mandates it for token persistence.
    @Suppress("DEPRECATION")
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "auth_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(provider: ProviderType): StoredTokens? {
        val refresh = prefs.getString(key(provider, REFRESH), null) ?: return null
        val access = prefs.getString(key(provider, ACCESS), null) ?: return null
        val expiry = prefs.getLong(key(provider, EXPIRY), 0L)
        val scope = prefs.getString(key(provider, SCOPE), "").orEmpty()
        return StoredTokens(access, expiry, refresh, scope)
    }

    override fun write(provider: ProviderType, tokens: StoredTokens) {
        prefs.edit()
            .putString(key(provider, ACCESS), tokens.accessToken)
            .putLong(key(provider, EXPIRY), tokens.accessExpiryEpochMs)
            .putString(key(provider, REFRESH), tokens.refreshToken)
            .putString(key(provider, SCOPE), tokens.scope)
            .apply()
    }

    override fun clear(provider: ProviderType) {
        prefs.edit()
            .remove(key(provider, ACCESS))
            .remove(key(provider, EXPIRY))
            .remove(key(provider, REFRESH))
            .remove(key(provider, SCOPE))
            .apply()
    }

    private fun key(provider: ProviderType, field: String) = "${provider.name}_$field"

    private companion object {
        const val ACCESS = "access_token"
        const val EXPIRY = "access_expiry"
        const val REFRESH = "refresh_token"
        const val SCOPE = "granted_scope"
    }
}
