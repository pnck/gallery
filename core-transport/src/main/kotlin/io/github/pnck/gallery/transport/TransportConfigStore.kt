package io.github.pnck.gallery.transport

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Persisted transport form + the derived public key (shown after reload). */
data class SavedTransport(val form: TransportForm, val publicKey: String)

/**
 * Persists the transport configuration so it survives leaving the screen and app
 * restarts. WG private keys / preshared keys / SOCKS passwords are sensitive
 * (PRD §8.4.6), so the whole set lives in EncryptedSharedPreferences — never in
 * Room, never logged.
 */
class TransportConfigStore(
    appContext: Context,
) {
    @Suppress("DEPRECATION") // EncryptedSharedPreferences deprecated w/o a 1:1 replacement
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "transport_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): SavedTransport? {
        if (!prefs.contains(PRIVATE_KEY) && !prefs.contains(SOCKS_HOST)) return null
        val form = TransportForm(
            wgEnabled = prefs.getBoolean(WG_ENABLED, true),
            socksEnabled = prefs.getBoolean(SOCKS_ENABLED, true),
            privateKey = prefs.getString(PRIVATE_KEY, "").orEmpty(),
            peerPublicKey = prefs.getString(PEER_PUBLIC_KEY, "").orEmpty(),
            presharedKey = prefs.getString(PRESHARED_KEY, "").orEmpty(),
            useSrv = prefs.getBoolean(USE_SRV, false),
            endpoint = prefs.getString(ENDPOINT, "").orEmpty(),
            srvName = prefs.getString(SRV_NAME, "").orEmpty(),
            interfaceAddress = prefs.getString(INTERFACE_ADDRESS, "10.0.0.2/32").orEmpty(),
            dns = prefs.getString(DNS, "").orEmpty(),
            keepaliveSecs = prefs.getString(KEEPALIVE, "25").orEmpty(),
            mtu = prefs.getString(MTU, "").orEmpty(),
            socksHost = prefs.getString(SOCKS_HOST, "").orEmpty(),
            socksPort = prefs.getString(SOCKS_PORT, "1080").orEmpty(),
            socksUser = prefs.getString(SOCKS_USER, "").orEmpty(),
            socksPass = prefs.getString(SOCKS_PASS, "").orEmpty(),
        )
        return SavedTransport(form, prefs.getString(PUBLIC_KEY, "").orEmpty())
    }

    /** Whether the user last left the tunnel ON — drives auto-reconnect at launch. */
    fun isActive(): Boolean = prefs.getBoolean(AUTO_CONNECT, false)

    fun setActive(active: Boolean) {
        prefs.edit().putBoolean(AUTO_CONNECT, active).apply()
    }

    fun save(form: TransportForm, publicKey: String) {
        prefs.edit()
            .putBoolean(WG_ENABLED, form.wgEnabled)
            .putBoolean(SOCKS_ENABLED, form.socksEnabled)
            .putString(PRIVATE_KEY, form.privateKey)
            .putString(PUBLIC_KEY, publicKey)
            .putString(PEER_PUBLIC_KEY, form.peerPublicKey)
            .putString(PRESHARED_KEY, form.presharedKey)
            .putBoolean(USE_SRV, form.useSrv)
            .putString(ENDPOINT, form.endpoint)
            .putString(SRV_NAME, form.srvName)
            .putString(INTERFACE_ADDRESS, form.interfaceAddress)
            .putString(DNS, form.dns)
            .putString(KEEPALIVE, form.keepaliveSecs)
            .putString(MTU, form.mtu)
            .putString(SOCKS_HOST, form.socksHost)
            .putString(SOCKS_PORT, form.socksPort)
            .putString(SOCKS_USER, form.socksUser)
            .putString(SOCKS_PASS, form.socksPass)
            .apply()
    }

    private companion object {
        const val AUTO_CONNECT = "auto_connect"
        const val WG_ENABLED = "wg_enabled"
        const val SOCKS_ENABLED = "socks_enabled"
        const val PRIVATE_KEY = "private_key"
        const val PUBLIC_KEY = "public_key"
        const val PEER_PUBLIC_KEY = "peer_public_key"
        const val PRESHARED_KEY = "preshared_key"
        const val USE_SRV = "use_srv"
        const val ENDPOINT = "endpoint"
        const val SRV_NAME = "srv_name"
        const val INTERFACE_ADDRESS = "interface_address"
        const val DNS = "dns"
        const val KEEPALIVE = "keepalive"
        const val MTU = "mtu"
        const val SOCKS_HOST = "socks_host"
        const val SOCKS_PORT = "socks_port"
        const val SOCKS_USER = "socks_user"
        const val SOCKS_PASS = "socks_pass"
    }
}
