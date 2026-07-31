package es.adeodato.hermes.ui.alerts

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import es.adeodato.hermes.data.model.AlertaCruda
import es.adeodato.hermes.data.network.ActiveResponseConfig
import es.adeodato.hermes.data.network.ActiveResponseSource
import es.adeodato.hermes.data.network.ArgosConfig
import es.adeodato.hermes.data.network.EnrichmentData
import es.adeodato.hermes.data.network.EnrichmentSourceFactory
import es.adeodato.hermes.security.CredentialStore
import es.adeodato.hermes.ui.theme.HermesAmber
import es.adeodato.hermes.ui.theme.HermesBg
import es.adeodato.hermes.ui.theme.HermesBlue
import es.adeodato.hermes.ui.theme.HermesGreen
import es.adeodato.hermes.ui.theme.HermesInkDim
import es.adeodato.hermes.ui.theme.HermesRed
import es.adeodato.hermes.ui.theme.HermesSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

private sealed class EstadoEnriquecimiento {
    data object Cargando : EstadoEnriquecimiento()
    data object NoAplica : EstadoEnriquecimiento()      // severidad Baja: no se consulta ni se muestra
    data object NoDisponible : EstadoEnriquecimiento()  // ambar/rojo pero sin doc aun, o error
    data class Disponible(val datos: EnrichmentData) : EstadoEnriquecimiento()
}

/**
 * Estado del boton "Bloquear IP" (Active Response !firewall-drop, ver
 * ActiveResponseSource). Solo IT: se oculta por completo si la alerta es OT
 * (AlertaCruda.isOt) -- el aislamiento de activos OT es un flujo aparte con
 * confirmacion humana, pendiente (punto D del roadmap).
 */
private sealed class EstadoBloqueo {
    data object Idle : EstadoBloqueo()
    data object Bloqueando : EstadoBloqueo()
    data object Bloqueada : EstadoBloqueo()
    data class Error(val mensaje: String) : EstadoBloqueo()
}

/**
 * Estado del boton "Deshabilitar cuenta" (Active Response ESTANDAR
 * !disable-account, no el custom -- verificado en el servidor el 2026-07-31
 * que los AR custom no se ejecutan al invocarlos por API). Visible cuando la
 * alerta trae un usuario de origen (p.ej. regla 100002, abuso de sudo) y no
 * es OT, igual criterio que "Bloquear IP".
 */
private sealed class EstadoDeshabilitar {
    data object Idle : EstadoDeshabilitar()
    data object Deshabilitando : EstadoDeshabilitar()
    data object Deshabilitada : EstadoDeshabilitar()
    data class Error(val mensaje: String) : EstadoDeshabilitar()
}

/**
 * PASO 2b: detalle de una alerta. Muestra los datos ya conocidos por la app
 * (descripcion, agente, nivel/color) y, para ambar/rojo, consulta
 * argos-ai-enrichment por el id de la alerta (join 1:1, ver
 * EnrichmentSource.kt) para mostrar el analisis de Ollama si ya existe.
 */
@Composable
fun AlertDetailScreen(alerta: AlertaCruda?, onVolver: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onVolver) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
            Text("Detalle de alerta", style = MaterialTheme.typography.titleMedium)
        }

        if (alerta == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Alerta no encontrada.", color = HermesInkDim)
            }
            return
        }

        var estado by remember(alerta.docId) { mutableStateOf<EstadoEnriquecimiento>(EstadoEnriquecimiento.Cargando) }
        var estadoBloqueo by remember(alerta.docId) { mutableStateOf<EstadoBloqueo>(EstadoBloqueo.Idle) }
        var mostrarConfirmacionBloqueo by remember(alerta.docId) { mutableStateOf(false) }
        var estadoDeshabilitar by remember(alerta.docId) { mutableStateOf<EstadoDeshabilitar>(EstadoDeshabilitar.Idle) }
        var mostrarConfirmacionDeshabilitar by remember(alerta.docId) { mutableStateOf(false) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        LaunchedEffect(alerta.docId) {
            Log.d("AlertDetailScreen", "docId=${alerta.docId} sourceId=${alerta.sourceId} severidad=${alerta.severidad}")
            if (alerta.severidad == AlertaCruda.Severidad.BAJA) {
                estado = EstadoEnriquecimiento.NoAplica
                return@LaunchedEffect
            }
            val sourceId = alerta.sourceId
            if (sourceId == null) {
                Log.w("AlertDetailScreen", "sourceId es null para docId=${alerta.docId}; no se consulta el enriquecimiento")
                estado = EstadoEnriquecimiento.NoDisponible
                return@LaunchedEffect
            }
            // Sondeo corto: si Ollama todavia esta generando el analisis, el
            // primer intento puede dar 404 legitimo. Se reintenta cada 5s
            // hasta 30s en total (7 intentos) antes de rendirse; la pantalla
            // se actualiza sola en cuanto llega, sin que haga falta reabrirla.
            val maxIntentos = 7
            for (intento in 1..maxIntentos) {
                val resultado = withContext(Dispatchers.IO) {
                    try {
                        val stored = CredentialStore.load(context)
                        if (!stored.isComplete) return@withContext null
                        val config = ArgosConfig(stored.baseUrl, stored.username, stored.password, stored.useDashboardProxy)
                        EnrichmentSourceFactory.create(config).fetchEnrichment(sourceId)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (resultado != null) {
                    estado = EstadoEnriquecimiento.Disponible(resultado)
                    return@LaunchedEffect
                }
                if (intento < maxIntentos) delay(5_000L)
            }
            estado = EstadoEnriquecimiento.NoDisponible
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(colorSeveridad(alerta.severidad), CircleShape)
                )
                Spacer(Modifier.padding(start = 8.dp))
                Text(alerta.ruleDescription, style = MaterialTheme.typography.titleLarge)
            }

            Spacer(Modifier.height(12.dp))

            val agente = alerta.agentName ?: alerta.agentId ?: "?"
            val fechaHora = alerta.timestampMillis?.let { FORMATO_FECHA_HORA.format(Date(it)) } ?: alerta.timestamp
            InfoFila("Fecha", fechaHora)
            InfoFila("Agente", agente)
            InfoFila("Nivel", "${alerta.ruleLevel} (${alerta.severidad.name.lowercase().replaceFirstChar { it.uppercase() }})")
            InfoFila("Regla", alerta.ruleId)
            alerta.srcIp?.let { InfoFila("IP origen", it) }
            alerta.srcUser?.let { InfoFila("Usuario", it) }

            val srcIpBloqueo = alerta.srcIp
            val agentIdBloqueo = alerta.agentId
            if (srcIpBloqueo != null && agentIdBloqueo != null) {
                Spacer(Modifier.height(16.dp))
                if (alerta.isOt) {
                    Text(
                        "⚠ Activo OT: esta acción puede afectar a producción.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HermesRed
                    )
                    Spacer(Modifier.height(4.dp))
                }
                when (val eb = estadoBloqueo) {
                    is EstadoBloqueo.Idle -> {
                        Button(
                            onClick = { mostrarConfirmacionBloqueo = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = HermesAmber,
                                contentColor = HermesBg
                            )
                        ) {
                            Text("Bloquear IP")
                        }
                    }
                    is EstadoBloqueo.Bloqueando -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.padding(start = 10.dp))
                            Text("Bloqueando $srcIpBloqueo…", style = MaterialTheme.typography.bodySmall, color = HermesInkDim)
                        }
                    }
                    is EstadoBloqueo.Bloqueada -> {
                        Text("IP $srcIpBloqueo bloqueada en el agente $agentIdBloqueo.", color = HermesGreen)
                    }
                    is EstadoBloqueo.Error -> {
                        Column {
                            Text("Error al bloquear: ${eb.mensaje}", color = HermesRed, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { mostrarConfirmacionBloqueo = true }) {
                                Text("Reintentar")
                            }
                        }
                    }
                }
            }

            val srcUserDeshabilitar = alerta.srcUser
            val agentIdDeshabilitar = alerta.agentId
            if (srcUserDeshabilitar != null && agentIdDeshabilitar != null) {
                Spacer(Modifier.height(12.dp))
                if (alerta.isOt) {
                    Text(
                        "⚠ Activo OT: esta acción puede afectar a producción.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HermesRed
                    )
                    Spacer(Modifier.height(4.dp))
                }
                when (val ed = estadoDeshabilitar) {
                    is EstadoDeshabilitar.Idle -> {
                        Button(
                            onClick = { mostrarConfirmacionDeshabilitar = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("Deshabilitar cuenta")
                        }
                    }
                    is EstadoDeshabilitar.Deshabilitando -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.padding(start = 10.dp))
                            Text("Deshabilitando $srcUserDeshabilitar…", style = MaterialTheme.typography.bodySmall, color = HermesInkDim)
                        }
                    }
                    is EstadoDeshabilitar.Deshabilitada -> {
                        Text("Cuenta $srcUserDeshabilitar deshabilitada en el agente $agentIdDeshabilitar.", color = HermesGreen)
                    }
                    is EstadoDeshabilitar.Error -> {
                        Column {
                            Text("Error al deshabilitar: ${ed.mensaje}", color = HermesRed, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { mostrarConfirmacionDeshabilitar = true }) {
                                Text("Reintentar")
                            }
                        }
                    }
                }
            }

            when (val e = estado) {
                is EstadoEnriquecimiento.NoAplica -> Unit // nada que mostrar para severidad Baja
                is EstadoEnriquecimiento.Cargando -> {
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.padding(start = 10.dp))
                        Text("Consultando análisis IA…", style = MaterialTheme.typography.bodySmall, color = HermesInkDim)
                    }
                }
                is EstadoEnriquecimiento.NoDisponible -> {
                    Spacer(Modifier.height(20.dp))
                    Text("Análisis IA", style = MaterialTheme.typography.titleMedium, color = HermesBlue)
                    Text(
                        "Análisis no disponible todavía (puede seguir generándose).",
                        style = MaterialTheme.typography.bodySmall,
                        color = HermesInkDim
                    )
                }
                is EstadoEnriquecimiento.Disponible -> {
                    Spacer(Modifier.height(20.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(HermesSurface, RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Text("Análisis IA", style = MaterialTheme.typography.titleMedium, color = HermesBlue)
                        Spacer(Modifier.height(8.dp))
                        Text(e.datos.aiAnalysis, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        e.datos.aiSeverityLabel?.let { InfoFila("Gravedad IA", it) }
                        e.datos.aiMitre?.let { InfoFila("MITRE", it) }
                    }
                }
            }
        }

        val srcIpDialogo = alerta.srcIp
        val agentIdDialogo = alerta.agentId
        if (mostrarConfirmacionBloqueo && srcIpDialogo != null && agentIdDialogo != null) {
            AlertDialog(
                onDismissRequest = { mostrarConfirmacionBloqueo = false },
                title = {
                    Text(
                        if (alerta.isOt) "⚠ Confirmar bloqueo de IP (Activo OT)" else "Confirmar bloqueo de IP",
                        color = if (alerta.isOt) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column {
                        if (alerta.isOt) {
                            Text(
                                "⚠ Activo OT — esta acción puede afectar a producción. ¿Continuar?",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            "Se bloqueará la IP $srcIpDialogo en el agente $agentIdDialogo mediante Active " +
                                "Response (firewall-drop). La regla se aplica de inmediato en ese equipo."
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        mostrarConfirmacionBloqueo = false
                        estadoBloqueo = EstadoBloqueo.Bloqueando
                        scope.launch {
                            val resultado = withContext(Dispatchers.IO) {
                                try {
                                    val stored = CredentialStore.load(context)
                                    if (!stored.isActiveResponseComplete) {
                                        EstadoBloqueo.Error("Faltan credenciales de Respuesta activa en Ajustes")
                                    } else {
                                        val arConfig = ActiveResponseConfig(stored.wazuhApiUrl, stored.arUsername, stored.arPassword)
                                        ActiveResponseSource(arConfig).blockIp(agentIdDialogo, srcIpDialogo)
                                        EstadoBloqueo.Bloqueada
                                    }
                                } catch (e: Exception) {
                                    EstadoBloqueo.Error(e.message ?: "error desconocido")
                                }
                            }
                            estadoBloqueo = resultado
                        }
                    }) {
                        Text("Bloquear", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { mostrarConfirmacionBloqueo = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }

        val srcUserDialogo = alerta.srcUser
        val agentIdDeshabilitarDialogo = alerta.agentId
        if (mostrarConfirmacionDeshabilitar && srcUserDialogo != null && agentIdDeshabilitarDialogo != null) {
            AlertDialog(
                onDismissRequest = { mostrarConfirmacionDeshabilitar = false },
                title = {
                    Text(
                        if (alerta.isOt) "⚠ Confirmar deshabilitación de cuenta (Activo OT)" else "Confirmar deshabilitación de cuenta",
                        color = if (alerta.isOt) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column {
                        if (alerta.isOt) {
                            Text(
                                "⚠ Activo OT — esta acción puede afectar a producción. ¿Continuar?",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            "Se deshabilitará la cuenta $srcUserDialogo en el agente $agentIdDeshabilitarDialogo " +
                                "mediante Active Response (disable-account). La cuenta queda bloqueada de inmediato en ese equipo."
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        mostrarConfirmacionDeshabilitar = false
                        estadoDeshabilitar = EstadoDeshabilitar.Deshabilitando
                        scope.launch {
                            val resultado = withContext(Dispatchers.IO) {
                                try {
                                    val stored = CredentialStore.load(context)
                                    if (!stored.isActiveResponseComplete) {
                                        EstadoDeshabilitar.Error("Faltan credenciales de Respuesta activa en Ajustes")
                                    } else {
                                        val arConfig = ActiveResponseConfig(stored.wazuhApiUrl, stored.arUsername, stored.arPassword)
                                        ActiveResponseSource(arConfig).disableAccount(agentIdDeshabilitarDialogo, srcUserDialogo)
                                        EstadoDeshabilitar.Deshabilitada
                                    }
                                } catch (e: Exception) {
                                    EstadoDeshabilitar.Error(e.message ?: "error desconocido")
                                }
                            }
                            estadoDeshabilitar = resultado
                        }
                    }) {
                        Text("Deshabilitar", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { mostrarConfirmacionDeshabilitar = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}

@Composable
private fun InfoFila(etiqueta: String, valor: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(etiqueta, style = MaterialTheme.typography.bodySmall, color = HermesInkDim)
        Text(valor, style = MaterialTheme.typography.bodySmall)
    }
}
