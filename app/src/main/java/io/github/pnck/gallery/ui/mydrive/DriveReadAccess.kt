package io.github.pnck.gallery.ui.mydrive

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pnck.gallery.BuildConfig
import io.github.pnck.gallery.di.AuthClient
import io.github.pnck.gallery.provider.DriveEntry
import io.github.pnck.gallery.provider.DriveListing
import io.github.pnck.gallery.provider.dto.DriveFileListResponse
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The SEPARATE, opt-in Drive-read access for the "My Drive" browser (all file types).
 *
 * It uses the OAuth 2.0 authorization-code flow with PKCE and a loopback redirect
 * (`http://127.0.0.1:<port>`), exactly like rclone — the system browser opens Google's
 * consent page, Google redirects to a tiny local server we bind, and we exchange the
 * code for a `drive.readonly` token. This needs a "Desktop app" OAuth client
 * (BuildConfig.GOOGLE_DESKTOP_CLIENT_ID), because the device-flow client the BACKUP path
 * uses cannot request `drive.readonly` (invalid_scope).
 *
 * Kept fully apart from the backup path: its token lives in its own encrypted store and
 * is injected per-request onto the bare [AuthClient] client (which rides the tunnel but
 * adds no Bearer of its own), so backup keeps using its least-privilege drive.file token.
 * Read-only, so the drive.file "per-client file ownership" issue never arises.
 */
@Singleton
class DriveReadAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    @AuthClient private val client: OkHttpClient,
    private val moshi: Moshi,
) {
    @Suppress("DEPRECATION")
    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "mydrive_auth",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val configured: Boolean get() = BuildConfig.GOOGLE_DESKTOP_CLIENT_ID.isNotBlank()

    fun isAuthorized(): Boolean = prefs.getString(REFRESH, null) != null

    /**
     * Live authorization flag, derived from the token store: seeded on first
     * collection (the keystore read happens on the collector's dispatcher, never
     * at DI-construction time), flipped by [authorize]/[signOut]. Both the My
     * Drive gate and the Settings account panel collect this — one truth, no
     * synchronization.
     */
    private val _authorized = MutableStateFlow<Boolean?>(null)
    val authorized: Flow<Boolean> = _authorized
        .map { it ?: isAuthorized() }
        .distinctUntilChanged()

    /**
     * Run the browser consent flow. Opens the system browser to Google, captures the
     * redirect on a loopback server, exchanges the code for tokens, and stores them.
     * @return null on success, else a human-readable error.
     */
    suspend fun authorize(): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext "No Desktop OAuth client configured (GALLERY_GOOGLE_DESKTOP_CLIENT_ID)."
        val verifier = randomUrlSafe(64)
        val challenge = s256(verifier)
        runCatching {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = AUTH_TIMEOUT_MS
                val redirect = "http://127.0.0.1:${server.localPort}"
                val authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
                    "?client_id=${enc(BuildConfig.GOOGLE_DESKTOP_CLIENT_ID)}" +
                    "&redirect_uri=${enc(redirect)}" +
                    "&response_type=code" +
                    "&scope=${enc(io.github.pnck.gallery.provider.OAuthConfig.Google.SCOPE_DRIVE_READ)}" +
                    "&code_challenge=$challenge&code_challenge_method=S256" +
                    "&access_type=offline&prompt=consent"
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, authUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                val code = server.accept().use { readCode(it) }
                    ?: return@runCatching "No authorization code returned."
                exchangeCode(code, verifier, redirect).also { error ->
                    if (error == null) _authorized.value = true
                }
            }
        }.getOrElse { "Authorization failed: ${it.message}" }
    }

    /** Fresh access token (refreshing if needed), or null if not authorized / refresh failed. */
    suspend fun validToken(): String? = withContext(Dispatchers.IO) {
        val access = prefs.getString(ACCESS, null)
        val expiry = prefs.getLong(EXPIRY, 0L)
        if (access != null && System.currentTimeMillis() < expiry - 60_000L) return@withContext access
        val refresh = prefs.getString(REFRESH, null) ?: return@withContext null
        refresh(refresh)
    }

    /**
     * Live grant check (tri-state): a stored refresh token proves NOTHING — the
     * grant may have been revoked server-side weeks ago (owner report: the panel
     * kept offering "revoke" for a long-dead grant). true = Drive answered;
     * false = server rejected the grant (401/403); null = couldn't tell (offline).
     */
    suspend fun probe(): Boolean? = withContext(Dispatchers.IO) {
        if (!isAuthorized()) return@withContext false
        val token = validToken() ?: return@withContext null
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/about?fields=user")
            .header("Authorization", "Bearer $token")
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> true
                    resp.code == 401 || resp.code == 403 -> false
                    else -> null
                }
            }
        }.getOrNull()
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        _authorized.value = false
    }

    // ── Drive read calls (bare client + our Bearer) ─────────────────────────
    suspend fun browse(folderId: String, pageToken: String?): DriveListing = withContext(Dispatchers.IO) {
        val token = validToken() ?: throw IllegalStateException("Drive read access expired — re-enable browsing.")
        val q = enc("'$folderId' in parents and trashed = false")
        val fields = enc("nextPageToken, files(id, name, mimeType, size, thumbnailLink)")
        val page = pageToken?.let { "&pageToken=${enc(it)}" }.orEmpty()
        val url = "https://www.googleapis.com/drive/v3/files?q=$q&orderBy=folder,name&pageSize=200&fields=$fields$page"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Drive ${resp.code}: ${body.take(200)}")
            val parsed = moshi.adapter(DriveFileListResponse::class.java).fromJson(body)
                ?: throw IllegalStateException("Unparseable Drive response")
            DriveListing(
                entries = parsed.files.map {
                    DriveEntry(
                        id = it.id,
                        name = it.name ?: it.id,
                        mimeType = it.mimeType ?: "application/octet-stream",
                        sizeBytes = it.size,
                        thumbnailUrl = it.thumbnailLink,
                    )
                },
                nextPageToken = parsed.nextPageToken,
            )
        }
    }

    /** Streamed original bytes (for download/preview). Caller closes the stream. */
    suspend fun download(fileId: String): InputStream = withContext(Dispatchers.IO) {
        val token = validToken() ?: throw IllegalStateException("Drive read access expired.")
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            val body = runCatching { resp.body?.string()?.take(300).orEmpty() }.getOrDefault("")
            resp.close()
            Log.w(TAG, "download $fileId FAILED: HTTP ${resp.code} $body")
            throw IllegalStateException("Download ${resp.code} for $fileId")
        }
        resp.body?.byteStream() ?: throw IllegalStateException("Empty body for $fileId")
    }

    /** Full metadata for the details panel (the list call only carries a few fields). */
    suspend fun details(fileId: String): DriveFileDetails = withContext(Dispatchers.IO) {
        val token = validToken() ?: throw IllegalStateException("Drive read access expired.")
        val fields = enc("name, mimeType, size, fileExtension, createdTime, modifiedTime, md5Checksum, owners")
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?fields=$fields"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "details $fileId FAILED: HTTP ${resp.code} ${body.take(300)}")
                throw IllegalStateException("Details ${resp.code}: ${body.take(200)}")
            }
            val j = JSONObject(body)
            val owner = j.optJSONArray("owners")?.optJSONObject(0)
            DriveFileDetails(
                name = j.optString("name"),
                mimeType = j.optString("mimeType"),
                sizeBytes = j.optString("size").toLongOrNull(),
                extension = j.optString("fileExtension").takeIf { it.isNotEmpty() },
                createdTime = j.optString("createdTime").takeIf { it.isNotEmpty() },
                modifiedTime = j.optString("modifiedTime").takeIf { it.isNotEmpty() },
                md5 = j.optString("md5Checksum").takeIf { it.isNotEmpty() },
                owner = owner?.optString("displayName")?.takeIf { it.isNotEmpty() }
                    ?: owner?.optString("emailAddress")?.takeIf { it.isNotEmpty() },
            )
        }
    }

    // ── Token endpoint ──────────────────────────────────────────────────────
    private fun exchangeCode(code: String, verifier: String, redirect: String): String? {
        val form = FormBody.Builder()
            .add("code", code)
            .add("client_id", BuildConfig.GOOGLE_DESKTOP_CLIENT_ID)
            .add("client_secret", BuildConfig.GOOGLE_DESKTOP_CLIENT_SECRET)
            .add("redirect_uri", redirect)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier)
            .build()
        return postToken(form, storeRefresh = true)
    }

    private fun refresh(refreshToken: String): String? {
        val form = FormBody.Builder()
            .add("client_id", BuildConfig.GOOGLE_DESKTOP_CLIENT_ID)
            .add("client_secret", BuildConfig.GOOGLE_DESKTOP_CLIENT_SECRET)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        return postToken(form, storeRefresh = false)
    }

    /** POST the token endpoint; persist tokens. @return the fresh access token (as an
     *  error string when [storeRefresh] and it failed, null on refresh failure). */
    private fun postToken(form: FormBody, storeRefresh: Boolean): String? {
        val req = Request.Builder()
            .url(io.github.pnck.gallery.provider.OAuthConfig.Google.TOKEN_ENDPOINT)
            .post(form)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "token call FAILED: HTTP ${resp.code} ${body.take(300)} (storeRefresh=$storeRefresh)")
                return if (storeRefresh) "Token exchange failed (${resp.code}): ${body.take(200)}" else null
            }
            val json = JSONObject(body)
            val access = json.optString("access_token").takeIf { it.isNotEmpty() }
                ?: return if (storeRefresh) "No access token in response" else null
            val expiresIn = json.optInt("expires_in", 3600)
            val editor = prefs.edit()
                .putString(ACCESS, access)
                .putLong(EXPIRY, System.currentTimeMillis() + expiresIn * 1000L)
            json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { editor.putString(REFRESH, it) }
            editor.apply()
            // On a successful code exchange we return null (no error); on refresh we
            // return the access token for immediate use.
            return if (storeRefresh) null else access
        }
    }

    // ── Loopback redirect parsing ───────────────────────────────────────────
    private fun readCode(socket: java.net.Socket): String? = socket.use { s ->
        val requestLine = s.getInputStream().bufferedReader().readLine().orEmpty()
        // "GET /?code=XYZ&scope=... HTTP/1.1"
        val path = requestLine.substringAfter("GET ", "").substringBefore(" HTTP")
        val query = path.substringAfter('?', "")
        val params = query.split('&').mapNotNull {
            val k = it.substringBefore('=', "")
            val v = it.substringAfter('=', "")
            if (k.isEmpty()) null else k to java.net.URLDecoder.decode(v, "UTF-8")
        }.toMap()
        val html = "<html><body style='font-family:sans-serif;text-align:center;padding:3em'>" +
            "<h3>${if (params.containsKey("code")) "Authorized" else "Authorization failed"}</h3>" +
            "<p>You can close this tab and return to BYOS Gallery.</p></body></html>"
        s.getOutputStream().apply {
            write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n$html").toByteArray(),
            )
            flush()
        }
        params["code"]
    }

    // ── PKCE ────────────────────────────────────────────────────────────────
    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun s256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TAG = "gallery-mydrive"
        const val ACCESS = "access_token"
        const val EXPIRY = "access_expiry"
        const val REFRESH = "refresh_token"
        const val AUTH_TIMEOUT_MS = 180_000
    }
}

/** Full metadata for one file, shown in the details panel. */
data class DriveFileDetails(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val extension: String?,
    val createdTime: String?,
    val modifiedTime: String?,
    val md5: String?,
    val owner: String?,
)
