package es.adeodato.hermes.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import es.adeodato.hermes.data.model.AlertaCruda
import es.adeodato.hermes.ui.components.EtiquetaOt
import es.adeodato.hermes.ui.theme.HermesAmber
import es.adeodato.hermes.ui.theme.HermesGreen
import es.adeodato.hermes.ui.theme.HermesInkDim
import es.adeodato.hermes.ui.theme.HermesRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun colorSeveridad(severidad: AlertaCruda.Severidad): Color = when (severidad) {
    AlertaCruda.Severidad.BAJA -> HermesGreen
    AlertaCruda.Severidad.MEDIA -> HermesAmber
    AlertaCruda.Severidad.ALTA, AlertaCruda.Severidad.CRITICA -> HermesRed
}

private val FORMATO_HORA = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
internal val FORMATO_FECHA_HORA = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

@Composable
fun AlertsScreen(viewModel: AlertsViewModel = viewModel(), onAlertaClick: (AlertaCruda) -> Unit = {}) {
    val ui by viewModel.ui.collectAsState()
    // OT/Criticas son query propia al indexer (ver AlertsViewModel.consultarFiltroRemoto),
    // NO un filtro sobre ui.alertas -- una alerta critica no puede perderse
    // porque el lote de 200/30min del feed principal se llene de ruido IT.
    val esFiltroRemoto = ui.filtro == AlertFiltro.OT || ui.filtro == AlertFiltro.CRITICAS
    val alertasMostradas = when (ui.filtro) {
        AlertFiltro.TODAS -> ui.alertas
        AlertFiltro.IT -> ui.alertas.filterNot { it.isOt }
        AlertFiltro.OT, AlertFiltro.CRITICAS -> ui.alertasFiltroRemoto
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Alertas", style = MaterialTheme.typography.titleLarge)
                ui.ultimaActualizacion?.let {
                    Text(
                        "Actualizado ${FORMATO_HORA.format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = HermesInkDim
                    )
                }
            }
            IconButton(onClick = viewModel::refrescarManual) {
                Icon(Icons.Filled.Refresh, contentDescription = "Actualizar")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            AlertFiltro.entries.forEach { opcion ->
                FilterChip(
                    modifier = Modifier.padding(end = 8.dp),
                    selected = ui.filtro == opcion,
                    onClick = { viewModel.seleccionarFiltro(opcion) },
                    label = { Text(opcion.etiqueta) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        when {
            !ui.configurado -> MensajeCentrado("Configura la conexión con ARGOS en la pestaña Ajustes.")
            esFiltroRemoto && ui.cargandoFiltro && alertasMostradas.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            esFiltroRemoto && ui.errorFiltro != null -> MensajeCentrado("Error: ${ui.errorFiltro}", esError = true)
            !esFiltroRemoto && ui.error != null -> MensajeCentrado("Error: ${ui.error}", esError = true)
            !esFiltroRemoto && ui.cargando && ui.alertas.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            alertasMostradas.isEmpty() -> MensajeCentrado(
                if (esFiltroRemoto) "Sin alertas para este filtro en las últimas 24h." else "Sin alertas para este filtro."
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(alertasMostradas, key = { it.docId }) { alerta ->
                    FilaAlerta(alerta, onClick = { onAlertaClick(alerta) })
                }
            }
        }
    }
}

@Composable
private fun MensajeCentrado(texto: String, esError: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(texto, color = if (esError) HermesRed else HermesInkDim)
    }
}

@Composable
private fun FilaAlerta(alerta: AlertaCruda, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(colorSeveridad(alerta.severidad), CircleShape)
        )
        Column(modifier = Modifier.padding(start = 12.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // weight(fill=false) reserva primero el hueco del badge (tamaño natural) y
                // solo entonces le da el resto a Text -- sin esto, un titulo largo ocupa
                // toda la fila antes de que se mida el badge, y "OT" se parte en dos lineas
                // apretado contra el borde derecho.
                Text(
                    alerta.ruleDescription,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (alerta.isOt) {
                    Spacer(Modifier.padding(start = 6.dp))
                    EtiquetaOt()
                }
            }
            val agente = alerta.agentName ?: alerta.agentId ?: "?"
            val fechaHora = alerta.timestampMillis?.let { FORMATO_FECHA_HORA.format(Date(it)) } ?: alerta.timestamp
            Text(
                "$fechaHora · nivel ${alerta.ruleLevel} · $agente · regla ${alerta.ruleId}",
                style = MaterialTheme.typography.bodySmall,
                color = HermesInkDim
            )
        }
    }
}
