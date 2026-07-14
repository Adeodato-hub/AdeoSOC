package es.adeodato.hermes.ui.config

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import es.adeodato.hermes.ui.theme.HermesGreen
import es.adeodato.hermes.ui.theme.HermesRed

@Composable
fun ConfigScreen(viewModel: ConfigViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Conexión con ARGOS", style = MaterialTheme.typography.titleLarge)
        Text(
            "Fase 1 · solo lectura. Las credenciales se guardan cifradas en el dispositivo.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = ui.baseUrl,
            onValueChange = viewModel::onBaseUrlChange,
            label = { Text("URL base de ARGOS") },
            placeholder = { Text("https://127.0.0.1:10443") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = ui.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text("Usuario") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = ui.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Contraseña") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = if (ui.pollSeconds > 0) ui.pollSeconds.toString() else "",
            onValueChange = { texto -> texto.toIntOrNull()?.let(viewModel::onPollSecondsChange) },
            label = { Text("Refresco automático (segundos)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Usar proxy del Dashboard (temporal)")
                Text(
                    "Desactívalo cuando el Indexer (9200) sea alcanzable directamente con Basic Auth.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = ui.useDashboardProxy, onCheckedChange = viewModel::onUseDashboardProxyChange)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Vigilancia en segundo plano")
                Text(
                    "Notifica alertas ámbar/rojas (Media/Alta/Crítica) aunque la app esté cerrada, " +
                        "mediante un servicio en primer plano con notificación persistente.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = ui.monitoreoActivo,
                onCheckedChange = { activo ->
                    viewModel.onMonitoreoActivoChange(activo)
                    // Sin esta exencion, Android/Samsung puede matar el
                    // Foreground Service en cuanto la app deja de estar en
                    // primer plano (comprobado: appop RUN_ANY_IN_BACKGROUND
                    // queda en "ignore" por defecto en toda instalacion nueva).
                    if (activo) solicitarExencionBateriaSiHaceFalta(context)
                }
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Intervalo de sondeo en segundo plano")
            Text(
                "Menos segundos = avisos más rápidos, algo más de batería.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(15, 30, 45).forEach { segundos ->
                    val seleccionado = ui.intervaloSondeoSegundos == segundos
                    if (seleccionado) {
                        Button(onClick = { viewModel.onIntervaloSondeoChange(segundos) }) {
                            Text("${segundos}s")
                        }
                    } else {
                        OutlinedButton(onClick = { viewModel.onIntervaloSondeoChange(segundos) }) {
                            Text("${segundos}s")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = viewModel::probarConexion, enabled = !ui.probando) {
                Text(if (ui.probando) "Probando…" else "Probar conexión")
            }
            Button(onClick = viewModel::guardar) {
                Text("Guardar")
            }
        }

        ui.resultadoPrueba?.let { resultado ->
            Text(
                resultado,
                color = if (resultado.startsWith("OK")) HermesGreen else HermesRed
            )
        }

        if (ui.guardadoOk) {
            Text("Configuración guardada.", color = HermesGreen)
        }
    }
}

/**
 * Pide al usuario, mediante el dialogo del propio sistema, que excluya la app
 * de la optimizacion de bateria -- necesario para que el Foreground Service de
 * vigilancia sobreviva una vez la app deja de estar en primer plano. Si ya
 * esta concedida, no hace nada. Algunos OEMs/ROMs personalizadas no
 * implementan este Intent: si falla, el usuario tendra que concederla a mano
 * desde Ajustes de bateria (no es un fallo bloqueante para el resto de la app).
 */
private fun solicitarExencionBateriaSiHaceFalta(context: Context) {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Sin este Intent disponible, la vigilancia sigue activandose igual;
        // solo queda mas expuesta a que el sistema mate el servicio.
    }
}
