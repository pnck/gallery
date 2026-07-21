package io.github.pnck.gallery.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diagnostics hub (debug/alpha builds only, BuildConfig.DIAGNOSTICS_ENABLED):
 * transport log level, staged reachability probe, config dump, export bundle.
 * All dev tooling lives here so release builds drop one screen, not features.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val logLevel by viewModel.logLevel.collectAsState()
    val output by viewModel.output.collectAsState()
    val running by viewModel.running.collectAsState()
    val transportState by viewModel.transportState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var target by remember { mutableStateOf("https://www.google.com/generate_204") }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = viewModel.exportBundle()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(viewModel.buildInfo, style = MaterialTheme.typography.bodySmall)
            Text("Transport: $transportState", style = MaterialTheme.typography.bodySmall)

            Text("Transport log level (gallery-wg)", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiagnosticsViewModel.LEVELS.forEach { level ->
                    FilterChip(
                        selected = logLevel == level,
                        onClick = { viewModel.setLogLevel(level) },
                        label = { Text(level) },
                    )
                }
            }

            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text("Diag target (URL or host:port)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.runDiag(target) },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text(if (running) "Running…" else "Run diagnostics") }
                OutlinedButton(onClick = viewModel::dumpTransport, modifier = Modifier.weight(1f)) {
                    Text("Dump transport")
                }
            }
            OutlinedButton(
                onClick = { exportLauncher.launch("gallery-diagnostics.txt") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export diagnostics bundle") }

            if (output.isNotBlank()) {
                Text(
                    output,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
