package es.adeodato.hermes.ui.resumen

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import es.adeodato.hermes.data.model.ActivoCount
import es.adeodato.hermes.data.model.OtResumen
import es.adeodato.hermes.data.model.ReglaCount
import es.adeodato.hermes.data.model.ShiftSummary
import es.adeodato.hermes.ui.components.EtiquetaOt
import es.adeodato.hermes.ui.theme.HermesAmber
import es.adeodato.hermes.ui.theme.HermesBlue
import es.adeodato.hermes.ui.theme.HermesInkDim
import es.adeodato.hermes.ui.theme.HermesRed
import es.adeodato.hermes.ui.theme.HermesSurface

@Composable
fun ResumenScreen(viewModel: ResumenViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Resumen", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = viewModel::refrescar) {
                Icon(Icons.Filled.Refresh, contentDescription = "Actualizar")
            }
        }

        if (ui.configurado) {
            ui.otResumen?.let {
                CardOt(it, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
        }

        when {
            !ui.configurado -> MensajeCentrado("Configura la conexión con ARGOS en la pestaña Ajustes.")
            ui.cargando && ui.resumen == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.error != null && ui.resumen == null -> MensajeCentrado("Error: ${ui.error}", esError = true)
            ui.resumen == null -> MensajeCentrado("Sin resumen todavía. ARGOS genera uno nuevo cada día a las 08:00.")
            else -> ContenidoResumen(ui.resumen!!)
        }
    }
}

@Composable
private fun CardOt(resumen: OtResumen, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(HermesSurface, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OT", style = MaterialTheme.typography.titleMedium, color = HermesBlue)
            Spacer(Modifier.padding(start = 8.dp))
            EtiquetaOt()
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Contador("Activos", resumen.numActivos.toString(), HermesBlue)
            Contador("Exposiciones", resumen.numExposicionesAbiertas.toString(), HermesAmber)
        }
        Spacer(Modifier.height(10.dp))
        resumen.estado?.let {
            Text("Estado: $it", style = MaterialTheme.typography.bodySmall, color = HermesInkDim)
        }
        Text(
            "Última: ${resumen.ultimaDescripcion}",
            style = MaterialTheme.typography.bodySmall,
            color = HermesInkDim
        )
    }
}

@Composable
private fun MensajeCentrado(texto: String, esError: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(texto, color = if (esError) HermesRed else HermesInkDim)
    }
}

@Composable
private fun ContenidoResumen(resumen: ShiftSummary) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            "${resumen.fecha} · últimas ${resumen.ventana}",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Contador("Total", resumen.total.toString(), HermesInkDim)
            Contador("Ámbar", resumen.ambar.toString(), HermesAmber)
            Contador("Rojo", resumen.rojo.toString(), HermesRed)
        }

        Spacer(Modifier.height(24.dp))
        Text("Reglas más frecuentes", style = MaterialTheme.typography.titleSmall, color = HermesBlue)
        Spacer(Modifier.height(8.dp))
        resumen.topReglas.forEach { FilaRegla(it) }

        Spacer(Modifier.height(20.dp))
        Text("Activos más afectados", style = MaterialTheme.typography.titleSmall, color = HermesBlue)
        Spacer(Modifier.height(8.dp))
        resumen.topActivos.forEach { FilaActivo(it) }

        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HermesSurface, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text("Análisis IA", style = MaterialTheme.typography.titleMedium, color = HermesBlue)
            Spacer(Modifier.height(8.dp))
            Text(
                resumen.textoIa.ifBlank { "Análisis no disponible." },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Contador(etiqueta: String, valor: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(valor, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(etiqueta, style = MaterialTheme.typography.bodySmall, color = HermesInkDim)
    }
}

@Composable
private fun FilaRegla(regla: ReglaCount) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(regla.desc, style = MaterialTheme.typography.bodyMedium)
            Text("Regla ${regla.id}", style = MaterialTheme.typography.bodySmall, color = HermesInkDim)
        }
        Spacer(Modifier.width(8.dp))
        Text(regla.n.toString(), style = MaterialTheme.typography.bodyMedium, color = HermesInkDim)
    }
}

@Composable
private fun FilaActivo(activo: ActivoCount) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(activo.nombre, style = MaterialTheme.typography.bodyMedium)
        Text(activo.n.toString(), style = MaterialTheme.typography.bodyMedium, color = HermesInkDim)
    }
}
