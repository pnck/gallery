package io.github.pnck.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.pnck.gallery.ui.navigation.GalleryNavHost
import io.github.pnck.gallery.ui.settings.TransportConnector
import io.github.pnck.gallery.ui.theme.GalleryTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var transportConnector: TransportConnector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cheap WG reconnect if the user left the tunnel on (once per process).
        lifecycleScope.launch { transportConnector.reconnectIfActive() }
        setContent {
            GalleryTheme {
                GalleryNavHost()
            }
        }
    }
}
