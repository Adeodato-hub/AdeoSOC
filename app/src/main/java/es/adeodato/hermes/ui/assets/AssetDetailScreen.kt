package es.adeodato.hermes.ui.assets

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import es.adeodato.hermes.data.model.ActivoOt
import es.adeodato.hermes.data.model.AlertaCruda
import es.adeodato.hermes.ui.alerts.FORMATO_FECHA_HORA
import es.adeodato.hermes.ui.alerts.colorSeveridad
import es.adeodato.hermes.ui.components.EtiquetaOt
import es.adeodato.hermes.ui.theme.HermesBlue
import es.adeodato.hermes.ui.theme.HermesInkDim
import java.util.Date

/**
 * Detalle de un activo OT (p.ej. la impresora): exposicion, estado,
 * temperaturas y las alertas OT relacionadas -- solo existe para OT (los
 * activos IT no tienen detalle propio en esta fase, ver docs).
 */
@Composable
fun AssetDetailScreen(activo: ActivoOt?, alertasRelacionadas: List<AlertaCruda>, onVolver: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onVolver) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
            Text("Detalle de activo", style = MaterialTheme.typography.titleMedium)
        }

        if (activo == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Activo no encontrado.", color = HermesInkDim)
            }
            return
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
                        .background(colorSemaforo(activo.semaforo), CircleShape)
                )
                Spacer(Modifier.padding(start = 8.dp))
                Text(activo.assetId, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.padding(start = 8.dp))
                EtiquetaOt()
            }

            Spacer(Modifier.height(12.dp))

            activo.ip?.let { InfoFila("IP", it) }
            activo.estado?.let { InfoFila("Estado", it) }
            InfoFila("Nivel máx.", activo.maxLevel.toString())
            InfoFila("Alertas recientes", activo.numAlertas.toString())
            activo.nozzleTemp?.let { InfoFila("Temp. nozzle", "$it °C") }
            activo.bedTemp?.let { InfoFila("Temp. cama", "$it °C") }

            Spacer(Modifier.height(16.dp))
            Text("Exposición", style = MaterialTheme.typography.titleSmall, color = HermesBlue)
            Spacer(Modifier.height(4.dp))
            InfoFila("Control sin auth.", if (activo.ctrlUnauthAbierto) "Abierta" else "Cerrada")
            InfoFila("Cámara expuesta", if (activo.camOpenAbierto) "Sí" else "No")
            InfoFila("WebSocket sin auth.", if (activo.wsUnauthAbierto) "Abierta" else "Cerrada")

            Spacer(Modifier.height(20.dp))
            Text("Alertas relacionadas", style = MaterialTheme.typography.titleSmall, color = HermesBlue)
            Spacer(Modifier.height(8.dp))
            if (alertasRelacionadas.isEmpty()) {
                Text("Sin alertas relacionadas.", style = MaterialTheme.typography.bodySmall, color = HermesInkDim)
            } else {
                alertasRelacionadas
                    .sortedByDescending { it.timestampMillis ?: 0L }
                    .forEach { FilaAlertaRelacionada(it) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FilaAlertaRelacionada(alerta: AlertaCruda) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(colorSeveridad(alerta.severidad), CircleShape)
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(alerta.ruleDescription, style = MaterialTheme.typography.bodyMedium)
            val fechaHora = alerta.timestampMillis?.let { FORMATO_FECHA_HORA.format(Date(it)) } ?: alerta.timestamp
            Text(
                "$fechaHora · nivel ${alerta.ruleLevel} · regla ${alerta.ruleId}",
                style = MaterialTheme.typography.bodySmall,
                color = HermesInkDim
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
