package es.adeodato.hermes.ui.assets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import es.adeodato.hermes.data.model.Activo
import es.adeodato.hermes.data.model.ActivoOt
import es.adeodato.hermes.data.model.ActivoUi
import es.adeodato.hermes.data.model.Semaforo
import es.adeodato.hermes.ui.components.EtiquetaOt
import es.adeodato.hermes.ui.theme.HermesAmber
import es.adeodato.hermes.ui.theme.HermesGreen
import es.adeodato.hermes.ui.theme.HermesInkDim
import es.adeodato.hermes.ui.theme.HermesRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun colorSemaforo(s: Semaforo): Color = when (s) {
    Semaforo.VERDE -> HermesGreen
    Semaforo.AMBAR -> HermesAmber
    Semaforo.ROJO -> HermesRed
}

private val FORMATO_HORA = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun AssetsScreen(viewModel: AssetsViewModel = viewModel(), onOtClick: (ActivoOt) -> Unit = {}) {
    val ui by viewModel.ui.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Activos", style = MaterialTheme.typography.titleLarge)
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

        when {
            !ui.configurado -> MensajeCentrado("Configura la conexión con ARGOS en la pestaña Ajustes.")
            ui.error != null -> MensajeCentrado("Error: ${ui.error}", esError = true)
            ui.cargando && ui.activos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.activos.isEmpty() -> MensajeCentrado("Sin actividad reciente de ningún agente.")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(ui.activos, key = { it.id }) { activo ->
                    FilaActivo(activo, onOtClick = onOtClick)
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
private fun FilaActivo(activo: ActivoUi, onOtClick: (ActivoOt) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (activo is ActivoOt) it.clickable { onOtClick(activo) } else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(colorSemaforo(activo.semaforo), CircleShape)
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(activo.nombre, style = MaterialTheme.typography.bodyLarge)
                if (activo is ActivoOt) {
                    Spacer(Modifier.padding(start = 6.dp))
                    EtiquetaOt()
                }
            }
            val ip = when (activo) {
                is ActivoOt -> activo.ip
                is Activo -> activo.agentIp
            }
            val direccion = ip?.let { " · $it" } ?: ""
            Text(
                "nivel máx. ${activo.maxLevel}$direccion · ${activo.numAlertas} alertas",
                style = MaterialTheme.typography.bodySmall,
                color = HermesInkDim
            )
            Text(
                activo.ultimaDescripcion,
                style = MaterialTheme.typography.bodySmall,
                color = HermesInkDim
            )
        }
    }
}
