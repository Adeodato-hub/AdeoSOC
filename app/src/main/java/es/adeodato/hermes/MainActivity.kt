package es.adeodato.hermes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import es.adeodato.hermes.data.model.ActivoOt
import es.adeodato.hermes.ui.alerts.AlertDetailScreen
import es.adeodato.hermes.ui.alerts.AlertsScreen
import es.adeodato.hermes.ui.alerts.AlertsViewModel
import es.adeodato.hermes.ui.assets.AssetDetailScreen
import es.adeodato.hermes.ui.assets.AssetsScreen
import es.adeodato.hermes.ui.assets.AssetsViewModel
import es.adeodato.hermes.ui.config.ConfigScreen
import es.adeodato.hermes.ui.resumen.ResumenScreen
import es.adeodato.hermes.ui.theme.HermesBlue
import es.adeodato.hermes.ui.theme.HermesSurface
import es.adeodato.hermes.ui.theme.HermesTheme

private sealed class Destino(val ruta: String, val etiqueta: String) {
    data object Alertas : Destino("alertas", "Alertas")
    data object Activos : Destino("activos", "Activos")
    data object Resumen : Destino("resumen", "Resumen")
    data object Ajustes : Destino("ajustes", "Ajustes")
}

private val destinos = listOf(Destino.Alertas, Destino.Activos, Destino.Resumen, Destino.Ajustes)

class MainActivity : ComponentActivity() {
    private val pedirPermisoNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* si se deniega, la app sigue funcionando sin notificaciones */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        solicitarPermisoNotificacionesSiHaceFalta()
        setContent {
            HermesTheme {
                HermesRoot()
            }
        }
    }

    // PASO 2: en Android 13+ (API 33) POST_NOTIFICATIONS es un permiso en
    // tiempo de ejecucion. Se pide una vez al abrir la app; si se deniega, la
    // app sigue siendo utilizable (solo se pierden las notificaciones).
    private fun solicitarPermisoNotificacionesSiHaceFalta() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val concedido = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!concedido) {
            pedirPermisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun HermesRoot() {
    val navController = rememberNavController()
    // Se comparte la misma instancia entre la lista y el detalle para poder
    // buscar la alerta por id sin volver a pedirla a ARGOS ni duplicar el
    // polling (ver AlertDetailScreen).
    val alertsViewModel: AlertsViewModel = viewModel()
    // Misma razon que alertsViewModel: compartir instancia para poder buscar
    // el activo OT por id en el detalle sin duplicar el fetch (ver AssetDetailScreen).
    val assetsViewModel: AssetsViewModel = viewModel()

    Scaffold(
        topBar = { BrandTopBar() },
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination

                destinos.forEach { destino ->
                    val seleccionado = currentDestination?.hierarchy?.any { it.route == destino.ruta } == true
                    NavigationBarItem(
                        selected = seleccionado,
                        onClick = {
                            navController.navigate(destino.ruta) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(iconoPara(destino), contentDescription = destino.etiqueta) },
                        label = { Text(destino.etiqueta) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destino.Alertas.ruta,
            modifier = Modifier.padding(padding)
        ) {
            composable(Destino.Alertas.ruta) {
                AlertsScreen(
                    viewModel = alertsViewModel,
                    onAlertaClick = { alerta -> navController.navigate("detalle/${alerta.docId}") }
                )
            }
            composable(Destino.Activos.ruta) {
                AssetsScreen(
                    viewModel = assetsViewModel,
                    onOtClick = { activo -> navController.navigate("activo-detalle/${activo.assetId}") }
                )
            }
            composable(Destino.Resumen.ruta) { ResumenScreen() }
            composable(Destino.Ajustes.ruta) { ConfigScreen() }
            composable(
                route = "detalle/{alertId}",
                arguments = listOf(navArgument("alertId") { type = NavType.StringType })
            ) { backStackEntry ->
                val alertId = backStackEntry.arguments?.getString("alertId")
                val estadoAlertas = alertsViewModel.ui.collectAsState().value
                // Buscar tambien en alertasFiltroRemoto: las alertas de los chips OT/Criticas
                // vienen de su propia query (ver AlertsViewModel.consultarFiltroRemoto), no
                // del feed principal -- si solo se busca en "alertas" el detalle de una
                // alerta tocada bajo esos chips no se encuentra nunca.
                val alertaSeleccionada = estadoAlertas.alertas.find { it.docId == alertId }
                    ?: estadoAlertas.alertasFiltroRemoto.find { it.docId == alertId }
                AlertDetailScreen(alerta = alertaSeleccionada, onVolver = { navController.popBackStack() })
            }
            composable(
                route = "activo-detalle/{assetId}",
                arguments = listOf(navArgument("assetId") { type = NavType.StringType })
            ) { backStackEntry ->
                val assetId = backStackEntry.arguments?.getString("assetId")
                val estado = assetsViewModel.ui.collectAsState().value
                val activoSeleccionado = estado.activos.filterIsInstance<ActivoOt>()
                    .find { it.assetId == assetId }
                val relacionadas = estado.alertasCrudas.filter { it.isOt && (it.otAsset ?: it.otIp) == assetId }
                AssetDetailScreen(
                    activo = activoSeleccionado,
                    alertasRelacionadas = relacionadas,
                    onVolver = { navController.popBackStack() }
                )
            }
        }
    }
}

// Cabecera de marca persistente, encima de las pestañas. Reutiliza el mismo
// vector del icono del launcher (radar de 3 círculos concéntricos) para no
// duplicar el dibujo del logo en dos sitios.
@Composable
private fun BrandTopBar() {
    val lineaSutil = Color(0xFF223240)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HermesSurface)
            .statusBarsPadding()
            .drawBehind {
                drawLine(
                    color = lineaSutil,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "AdeoSOC",
            color = HermesBlue,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp
        )
    }
}

private fun iconoPara(destino: Destino) = when (destino) {
    Destino.Alertas -> Icons.Filled.NotificationsActive
    Destino.Activos -> Icons.Filled.Dns
    Destino.Resumen -> Icons.Filled.Summarize
    Destino.Ajustes -> Icons.Filled.Settings
}
