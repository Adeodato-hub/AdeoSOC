package es.adeodato.hermes.ui.assets

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import es.adeodato.hermes.data.AlertsRepository
import es.adeodato.hermes.data.model.ActivoUi
import es.adeodato.hermes.data.model.AlertaCruda
import es.adeodato.hermes.data.model.aActivos
import es.adeodato.hermes.data.model.aActivosOt
import es.adeodato.hermes.data.network.ArgosAlertsSourceFactory
import es.adeodato.hermes.data.network.ArgosConfig
import es.adeodato.hermes.data.network.FiltrosOpenSearch
import es.adeodato.hermes.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Consulta dedicada para OT (24h, propio limite) -- igual que el chip OT de
 * Alertas (ver AlertsViewModel.consultarFiltroRemoto). El activo OT NO puede
 * derivarse del feed IT (top 200/24h): con ruido IT suficiente, la ultima
 * alerta OT queda fuera de ese lote y el activo desaparece de Activos (caso
 * real: Ender3V3KE desaparecio tras ampliar la ventana del feed a 24h).
 */
private const val VENTANA_OT_MIN = 24 * 60
private const val LIMITE_OT = 200

data class AssetsUiState(
    val cargando: Boolean = false,
    val activos: List<ActivoUi> = emptyList(),
    /** Alertas crudas del ultimo lote, para que el detalle OT pueda listar sus alertas relacionadas. */
    val alertasCrudas: List<AlertaCruda> = emptyList(),
    val error: String? = null,
    val configurado: Boolean = false,
    val ultimaActualizacion: Long? = null
)

/**
 * Deriva el semaforo de cada activo a partir del mismo lote de alertas
 * recientes (no es un inventario de agentes de Wazuh en si -- Fase 1 es de
 * solo lectura sobre lo que ya ha generado alertas).
 */
class AssetsViewModel(application: Application) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(AssetsUiState())
    val ui: StateFlow<AssetsUiState> = _ui

    private var pollingJob: Job? = null

    init {
        iniciarPolling()
    }

    fun refrescarManual() = cargarUnaVez()

    private fun iniciarPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val stored = CredentialStore.load(getApplication())
                if (!stored.isComplete) {
                    _ui.update { it.copy(configurado = false, error = null) }
                } else {
                    _ui.update { it.copy(configurado = true) }
                    cargarUnaVez()
                }
                delay((if (stored.pollSeconds > 0) stored.pollSeconds else 30) * 1000L)
            }
        }
    }

    private fun cargarUnaVez() {
        val stored = CredentialStore.load(getApplication())
        if (!stored.isComplete) return
        _ui.update { it.copy(cargando = true) }
        viewModelScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                try {
                    val config = ArgosConfig(stored.baseUrl, stored.username, stored.password, stored.useDashboardProxy)
                    val repo = AlertsRepository(ArgosAlertsSourceFactory.create(config))
                    val alertasIt = repo.fetchRecentAlerts(size = 200)
                    val alertasOt = repo.fetchAlerts(LIMITE_OT, VENTANA_OT_MIN, FiltrosOpenSearch.grupoOt())
                    val combinados: List<ActivoUi> = (alertasIt.aActivos() + alertasOt.aActivosOt())
                        .sortedByDescending { it.maxLevel }
                    Result.success(Triple(combinados, alertasIt, alertasOt))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            resultado.fold(
                onSuccess = { (activos, alertasIt, alertasOt) ->
                    _ui.update {
                        it.copy(
                            cargando = false,
                            configurado = true,
                            activos = activos,
                            alertasCrudas = (alertasIt + alertasOt).distinctBy { a -> a.docId },
                            error = null,
                            ultimaActualizacion = System.currentTimeMillis()
                        )
                    }
                },
                onFailure = { e ->
                    _ui.update { it.copy(cargando = false, configurado = true, error = e.message ?: "Error desconocido") }
                }
            )
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}
