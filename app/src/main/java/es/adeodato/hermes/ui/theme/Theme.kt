package es.adeodato.hermes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val HermesBlue = Color(0xFF4D9DE0)
val HermesRed = Color(0xFFEF6A3D)
val HermesGreen = Color(0xFF34D17E)
val HermesAmber = Color(0xFFE0A94D)
val HermesBg = Color(0xFF0B1218)
val HermesSurface = Color(0xFF141F28)
val HermesInkDim = Color(0xFF9DB0BD)
val HermesOt = Color(0xFFB07CE0)

private val HermesColors = darkColorScheme(
    primary = HermesBlue,
    secondary = HermesBlue,
    background = HermesBg,
    surface = HermesSurface,
    error = HermesRed
)

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HermesColors,
        content = content
    )
}
