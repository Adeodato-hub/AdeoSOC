package es.adeodato.hermes.ui.config

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import es.adeodato.hermes.data.AlertsRepository
import es.adeodato.hermes.data.network.ArgosAlertsSourceFactory
import es.adeodato.hermes.data.network.ArgosConfig
import es.adeodato.hermes.monitor.AlertMonitorService
import es.adeodato.hermes.monitor.MonitorPrefs
import es.adeodato.hermes.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConfigUiState(
    val baseUrl: String = "https://127.0.0.1:10443",
    val username: String = "",
    val password: String = "",
    val useDashboardProxy: Boolean = true,
    val pollSeconds: Int = 30,
    val monitoreoActivo: Boolean = false,
    val intervaloSondeoSegundos: Int = MonitorPrefs.POLL_SECONDS_DEFAULT,
    val probando: Boolean = false,
    val resultadoPrueba: String? = null,
    val guardadoOk: Boolean = false
)

class ConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(ConfigUiState())
    val ui: StateFlow<ConfigUiState> = _ui

    init {
        val saved = CredentialStore.load(application)
        val deberiaEstarActivo = MonitorPrefs.isEnabled(application)
        _ui.update { current ->
            current.copy(
                baseUrl = saved.baseUrl.ifBlank { current.baseUrl },
                username = saved.username,
                password = saved.password,
                useDashboardProxy = saved.useDashboardProxy,
                pollSeconds = if (saved.pollSeconds > 0) saved.pollSeconds else current.pollSeconds,
                monitoreoActivo = deberiaEstarActivo,
                intervaloSondeoSegundos = MonitorPrefs.getPollSeconds(application)
            )
        }
        // Autocorreccion: un reinstall (adb install -r) mata el servicio pero
        // no toca la preferencia -- sin esto, el toggle queda en "true" con
        // el servicio realmente muerto y nada lo relanza salvo un reboot o
        // volver a tocar el interruptor a mano. Se comprueba una sola vez al
        // abrir Ajustes (no hay bucle: esto no es un sondeo periodico).
        if (deberiaEstarActivo && !AlertMonitorService.estaCorriendo(application)) {
            AlertMonitorService.start(application)
        }
    }

    fun onBaseUrlChange(v: String) = _ui.update { it.copy(baseUrl = v, guardadoOk = false) }
    fun onUsernameChange(v: String) = _ui.update { it.copy(username = v, guardadoOk = false) }
    fun onPasswordChange(v: String) = _ui.update { it.copy(password = v, guardadoOk = false) }
    fun onUseDashboardProxyChange(v: Boolean) = _ui.update { it.copy(useDashboardProxy = v, guardadoOk = false) }
    fun onPollSecondsChange(v: Int) = _ui.update { it.copy(pollSeconds = v, guardadoOk = false) }

    /** Toggle "vigilancia en segundo plano": arranca/para el Foreground Service al momento. */
    fun onMonitoreoActivoChange(activo: Boolean) {
        val app: Application = getApplication()
        MonitorPrefs.setEnabled(app, activo)
        _ui.update { it.copy(monitoreoActivo = activo) }
        if (activo) AlertMonitorService.start(app) else AlertMonitorService.stop(app)
    }

    /** Intervalo de sondeo del servicio de fondo (15/30/45s). Se aplica en la siguiente vuelta del bucle, sin reiniciar el servicio. */
    fun onIntervaloSondeoChange(segundos: Int) {
        MonitorPrefs.setPollSeconds(getApplication(), segundos)
        _ui.update { it.copy(intervaloSondeoSegundos = segundos) }
    }

    fun guardar() {
        val s = _ui.value
        CredentialStore.save(
            getApplication(),
            CredentialStore.Config(
                baseUrl = s.baseUrl,
                username = s.username,
                password = s.password,
                useDashboardProxy = s.useDashboardProxy,
                pollSeconds = s.pollSeconds
            )
        )
        _ui.update { it.copy(guardadoOk = true) }
    }

    fun probarConexion() {
        val s = _ui.value
        _ui.update { it.copy(probando = true, resultadoPrueba = null) }
        viewModelScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                try {
                    val config = ArgosConfig(s.baseUrl, s.username, s.password, s.useDashboardProxy)
                    val repo = AlertsRepository(ArgosAlertsSourceFactory.create(config))
                    val alertas = repo.fetchRecentAlerts(size = 3)
                    "OK: ${alertas.size} alertas recibidas"
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            _ui.update { it.copy(probando = false, resultadoPrueba = resultado) }
        }
    }
}
