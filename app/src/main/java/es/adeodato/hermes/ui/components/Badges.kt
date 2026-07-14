package es.adeodato.hermes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.adeodato.hermes.ui.theme.HermesOt

/**
 * Etiqueta "OT" reutilizada en Alertas/Activos/Resumen para marcar lo que
 * viene del poller OT (data.integration="argos-ot" o grupo de regla "ot") --
 * ver AlertaCruda.isOt. Convergencia IT/OT en un solo panel: se distingue por
 * esta etiqueta, no por una pestaña aparte.
 */
@Composable
fun EtiquetaOt(modifier: Modifier = Modifier) {
    Text(
        "OT",
        modifier = modifier
            .background(HermesOt.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = HermesOt,
        fontWeight = FontWeight.Bold
    )
}
