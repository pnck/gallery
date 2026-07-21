package io.github.pnck.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.ui.navigation.GalleryNavHost
import io.github.pnck.gallery.ui.settings.TransportConnector
import io.github.pnck.gallery.ui.theme.GalleryTheme
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.gallery_transport.setTransportLogLevel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var transportConnector: TransportConnector

    @Inject
    lateinit var settings: AppSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cheap WG reconnect if the user left the tunnel on (once per process).
        lifecycleScope.launch { transportConnector.reconnectIfActive() }
        // Apply the persisted transport log level (default warn).
        lifecycleScope.launch {
            val level = settings.transportLogLevel.first()
            withContext(Dispatchers.IO) { setTransportLogLevel(level) }
        }
        setContent {
            GalleryTheme {
                GalleryNavHost()
            }
        }
    }
}
