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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import es.adeodato.hermes.data.model.AlertaCruda
import es.adeodato.hermes.data.network.ArgosConfig
import es.adeodato.hermes.data.network.EnrichmentData
import es.adeodato.hermes.data.network.EnrichmentSourceFactory
import es.adeodato.hermes.security.CredentialStore
import es.adeodato.hermes.ui.theme.HermesBlue
import es.adeodato.hermes.ui.theme.HermesInkDim
import es.adeodato.hermes.ui.theme.HermesSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

private sealed class EstadoEnriquecimiento {
    data object Cargando : EstadoEnriquecimiento()
    data object NoAplica : EstadoEnriquecimiento()      // severidad Baja: no se consulta ni se muestra
    data object NoDisponible : EstadoEnriquecimiento()  // ambar/rojo pero sin doc aun, o error
    data class Disponible(val datos: EnrichmentData) : EstadoEnriquecimiento()
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
        val context = LocalContext.current

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
