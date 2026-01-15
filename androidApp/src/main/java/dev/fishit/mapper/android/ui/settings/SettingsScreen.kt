package dev.fishit.mapper.android.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fishit.mapper.android.cert.CertificateInfo
import dev.fishit.mapper.android.cert.CertificateManager
import dev.fishit.mapper.android.di.LocalAppContainer
import dev.fishit.mapper.android.proxy.MitmProxyServer
import dev.fishit.mapper.android.ui.common.SimpleVmFactory
import dev.fishit.mapper.android.vpn.TrafficCaptureVpnService
import dev.fishit.mapper.contract.RecorderEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Settings-Screen für Zertifikats-Management und VPN-Konfiguration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    
    val viewModel: SettingsViewModel = viewModel(
        factory = SimpleVmFactory {
            SettingsViewModel(
                certificateManager = CertificateManager(context)
            )
        }
    )
    
    val state by viewModel.state.collectAsState()
    
    // VPN Permission Launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpn(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Zertifikats-Management Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "CA-Zertifikat",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    // Zertifikats-Status
                    if (state.certificateInfo != null) {
                        CertificateInfoCard(state.certificateInfo!!)
                    } else {
                        Text(
                            "Kein Zertifikat vorhanden",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    
                    // Zertifikat generieren
                    Button(
                        onClick = { viewModel.generateCertificate() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isGenerating
                    ) {
                        if (state.isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.certificateInfo != null) "Neu generieren" else "Zertifikat generieren")
                    }
                    
                    // Zertifikat exportieren
                    Button(
                        onClick = { viewModel.exportCertificate(context) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.certificateInfo != null && !state.isExporting
                    ) {
                        if (state.isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Zertifikat exportieren")
                    }
                    
                    // Zertifikat installieren
                    Button(
                        onClick = { viewModel.openCertificateInstallation(context) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.certificateInfo != null && state.exportedCertPath != null
                    ) {
                        Text("Zertifikat installieren")
                    }
                    
                    if (state.exportedCertPath != null) {
                        Text(
                            "Exportiert nach:\n${state.exportedCertPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            // VPN Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "VPN Traffic Capture",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    Text(
                        "VPN aktivieren, um system-weiten Netzwerk-Traffic zu erfassen und HTTPS zu entschlüsseln.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("VPN Status:")
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (state.isVpnRunning) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Aktiv",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text("Aktiv", color = MaterialTheme.colorScheme.primary)
                            } else {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Inaktiv",
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text("Inaktiv", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    
                    if (!state.isVpnRunning) {
                        Button(
                            onClick = {
                                val intent = VpnService.prepare(context)
                                if (intent != null) {
                                    vpnPermissionLauncher.launch(intent)
                                } else {
                                    viewModel.startVpn(context)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("VPN starten")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.stopVpn(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("VPN stoppen")
                        }
                    }
                    
                    Text(
                        "⚠️ Hinweis: Das CA-Zertifikat muss installiert sein, damit HTTPS-Entschlüsselung funktioniert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            // Anleitung
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Anleitung",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    Text(
                        "1. Zertifikat generieren\n" +
                        "2. Zertifikat exportieren\n" +
                        "3. Zertifikat installieren (oder manuell über Einstellungen)\n" +
                        "4. VPN starten\n" +
                        "5. Traffic wird nun entschlüsselt erfasst",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Text(
                        "Manuelle Installation:\n" +
                        "Einstellungen → Sicherheit → Verschlüsselung & Anmeldedaten → Zertifikat installieren → CA-Zertifikat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Status-Meldungen
            if (state.statusMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.isError) 
                            MaterialTheme.colorScheme.errorContainer 
                        else 
                            MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        state.statusMessage!!,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun CertificateInfoCard(info: CertificateInfo) {
    val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Zertifikats-Informationen",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        
        InfoRow("Subject:", info.subject)
        InfoRow("Gültig von:", dateFormat.format(info.notBefore))
        InfoRow("Gültig bis:", dateFormat.format(info.notAfter))
        InfoRow("Seriennummer:", info.serialNumber)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * ViewModel für Settings-Screen.
 */
class SettingsViewModel(
    private val certificateManager: CertificateManager
) : ViewModel() {
    
    companion object {
        private const val TAG = "SettingsViewModel"
    }
    
    data class State(
        val certificateInfo: CertificateInfo? = null,
        val isGenerating: Boolean = false,
        val isExporting: Boolean = false,
        val isVpnRunning: Boolean = false,
        val exportedCertPath: String? = null,
        val statusMessage: String? = null,
        val isError: Boolean = false
    )
    
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    
    init {
        loadCertificateInfo()
    }
    
    private fun loadCertificateInfo() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                certificateManager.getCACertificateInfo()
            }
            _state.value = _state.value.copy(certificateInfo = info)
        }
    }
    
    fun generateCertificate() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isGenerating = true, statusMessage = null)
            
            try {
                withContext(Dispatchers.IO) {
                    if (certificateManager.hasCACertificate()) {
                        certificateManager.deleteCACertificate()
                    }
                    certificateManager.getOrCreateCACertificate()
                }
                
                loadCertificateInfo()
                _state.value = _state.value.copy(
                    isGenerating = false,
                    statusMessage = "Zertifikat erfolgreich generiert",
                    isError = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate certificate", e)
                _state.value = _state.value.copy(
                    isGenerating = false,
                    statusMessage = "Fehler beim Generieren: ${e.message}",
                    isError = true
                )
            }
        }
    }
    
    fun exportCertificate(context: android.content.Context) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isExporting = true, statusMessage = null)
            
            try {
                val exportDir = File(context.getExternalFilesDir(null), "certificates")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }
                
                val certFile = File(exportDir, "fishit-mapper-ca.pem")
                
                val success = withContext(Dispatchers.IO) {
                    certificateManager.exportCACertificate(certFile)
                }
                
                if (success) {
                    _state.value = _state.value.copy(
                        isExporting = false,
                        exportedCertPath = certFile.absolutePath,
                        statusMessage = "Zertifikat erfolgreich exportiert",
                        isError = false
                    )
                } else {
                    _state.value = _state.value.copy(
                        isExporting = false,
                        statusMessage = "Fehler beim Exportieren",
                        isError = true
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export certificate", e)
                _state.value = _state.value.copy(
                    isExporting = false,
                    statusMessage = "Fehler beim Exportieren: ${e.message}",
                    isError = true
                )
            }
        }
    }
    
    fun openCertificateInstallation(context: android.content.Context) {
        try {
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            context.startActivity(intent)
            
            _state.value = _state.value.copy(
                statusMessage = "Bitte installiere das Zertifikat manuell unter 'Verschlüsselung & Anmeldedaten'",
                isError = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open security settings", e)
            _state.value = _state.value.copy(
                statusMessage = "Konnte Einstellungen nicht öffnen: ${e.message}",
                isError = true
            )
        }
    }
    
    fun startVpn(context: android.content.Context) {
        try {
            val intent = Intent(context, TrafficCaptureVpnService::class.java).apply {
                action = TrafficCaptureVpnService.ACTION_START_VPN
            }
            context.startService(intent)
            
            _state.value = _state.value.copy(
                isVpnRunning = true,
                statusMessage = "VPN gestartet",
                isError = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            _state.value = _state.value.copy(
                statusMessage = "Fehler beim Starten des VPN: ${e.message}",
                isError = true
            )
        }
    }
    
    fun stopVpn(context: android.content.Context) {
        try {
            val intent = Intent(context, TrafficCaptureVpnService::class.java).apply {
                action = TrafficCaptureVpnService.ACTION_STOP_VPN
            }
            context.startService(intent)
            
            _state.value = _state.value.copy(
                isVpnRunning = false,
                statusMessage = "VPN gestoppt",
                isError = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN", e)
            _state.value = _state.value.copy(
                statusMessage = "Fehler beim Stoppen des VPN: ${e.message}",
                isError = true
            )
        }
    }
}
