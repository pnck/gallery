package io.github.pnck.gallery.auth

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.pnck.gallery.R
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.AuthManager
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Receives AppAuth's completion PendingIntent after the browser redirect
 * (T-101, PRD §5.2) and finishes the code→token exchange. Invisible: shows a
 * toast with the outcome and returns to wherever the user was.
 */
@AndroidEntryPoint
class OAuthCallbackActivity : ComponentActivity() {

    @Inject
    lateinit var googleAuthManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val result = googleAuthManager.handleAuthorizationResponse(intent)
            val message = when (result) {
                is ApiResult.Success -> getString(R.string.auth_success)
                is ApiResult.Error -> getString(R.string.auth_failed, result.message)
            }
            Toast.makeText(this@OAuthCallbackActivity, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
