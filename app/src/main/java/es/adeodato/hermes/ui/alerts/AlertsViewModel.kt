package es.adeodato.hermes.ui.alerts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import es.adeodato.hermes.data.AlertsFeed
import es.adeodato.hermes.data.AlertsRepository
import es.adeodato.hermes.data.model.AlertaCruda
import es.adeodato.hermes.data.network.ArgosAlertsSourceFactory
import es.adeodato.hermes.data.network.ArgosConfig
import es.adeodato.hermes.data.network.FiltrosOpenSearch
import es.adeodato.hermes.notify.AlertNotificationGate
import es.adeodato.hermes.notify.AlertNotifier
import es.adeodato.hermes.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ventana y limite de las queries independientes de OT/Criticas (ver
 * AlertsViewModel.consultarFiltroRemoto) -- deliberadamente mas amplios que
 * los VENTANA_RECIENTE_MIN/30 del feed principal, para que una alerta critica
 * no desaparezca solo porque un aluvion de ruido IT la empuje fuera del lote
 * de 200/30min (caso real: alerta OT 100201 enterrada por el escaneo FIM
 * inicial de un agente nuevo).
 */
private const val VENTANA_FILTRO_REMOTO_MIN = 24 * 60
private const val LIMITE_FILTRO_REMOTO = 200

/** Mismo umbral que Severidad.ALTA (rule.level > 9, ver AlertaCruda) -- el punto rojo de colorSeveridad. */
private const val NIVEL_CRITICO = 10

/** Filtro de la pestaña Alertas: convergencia IT/OT en un solo feed, sin pestaña aparte -- se distingue por filtro. */
enum class AlertFiltro(val etiqueta: String) {
    TODAS("Todas"), OT("OT"), IT("IT"), CRITICAS("Críticas")
}

data class AlertsUiState(
    val cargando: Boolean = false,
    val alertas: List<AlertaCruda> = emptyList(),
    val error: String? = null,
    val configurado: Boolean = false,
    val ultimaActualizacion: Long? = null,
    val filtro: AlertFiltro = AlertFiltro.TODAS,
    /** Resultado de la query remota de OT/Criticas (independiente del feed principal, ver consultarFiltroRemoto). */
    val cargandoFiltro: Boolean = false,
    val alertasFiltroRemoto: List<AlertaCruda> = emptyList(),
    val errorFiltro: String? = null
)

/**
 * PASO (latencia push vs. lista): la lista YA NO hace su propio polling HTTP
 * cada 30s. Observa [AlertsFeed], que publica AlertMonitorService al final de
 * cada ciclo con exito -- una sola llamada de red por ciclo, y la lista se
 * actualiza en el mismo instante en que se dispara el push, sin depender de
 * dos temporizadores distintos.
 *
 * El refresco manual (boton) sigue haciendo su propia peticion puntual e
 * independiente: si el servicio de fondo no esta activo (toggle apagado),
 * sigue siendo la unica forma de refrescar la lista.
 *
 * Los filtros OT/Criticas NO se aplican sobre [AlertsUiState.alertas] (el
 * lote del feed principal): consultan aparte al indexer con su propia
 * ventana/limite (ver [consultarFiltroRemoto]) para no depender de que la
 * alerta relevante siga estando en ese lote. Todas/IT si son client-side
 * sobre el feed principal -- no necesitan proteccion contra ruido porque no
 * prometen "nunca se pierde una critica".
 */
class AlertsViewModel(application: Application) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(AlertsUiState())
    val ui: StateFlow<AlertsUiState> = _ui

    // La deduplicacion "alerta nueva vs. ya vista" vive en AlertNotificationGate,
    // compartida con AlertMonitorService. Este booleano es propio de ESTA
    // instancia: el primer lote que procese (venga del feed o de un refresco
    // manual) solo siembra, sin notificar (si no, cada apertura de la app
    // bombardearia con todo el historial de alertas ambar/rojas recientes).
    private var primerProcesoHecho = false
    private var filtroJob: Job? = null

    init {
        val stored = CredentialStore.load(application)
        _ui.update { it.copy(configurado = stored.isComplete, cargando = stored.isComplete) }
        observarFeedCompartido()
    }

    private fun observarFeedCompartido() {
        viewModelScope.launch {
            AlertsFeed.snapshot.collect { snapshot ->
                if (snapshot == null) return@collect
                procesarNuevaLista(snapshot.alertas, snapshot.timestampMillis)
            }
        }
    }

    fun refrescarManual() {
        if (_ui.value.filtro == AlertFiltro.OT || _ui.value.filtro == AlertFiltro.CRITICAS) {
            consultarFiltroRemoto(_ui.value.filtro)
            return
        }
        val stored = CredentialStore.load(getApplication())
        if (!stored.isComplete) return
        _ui.update { it.copy(cargando = true) }
        viewModelScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                try {
                    val config = ArgosConfig(stored.baseUrl, stored.username, stored.password, stored.useDashboardProxy)
                    val repo = AlertsRepository(ArgosAlertsSourceFactory.create(config))
                    Result.success(repo.fetchRecentAlerts(size = 200))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            resultado.fold(
                onSuccess = { alertas ->
                    val ordenadas = alertas.sortedByDescending { a -> a.timestampMillis ?: 0L }
                    procesarNuevaLista(ordenadas, System.currentTimeMillis())
                },
                onFailure = { e ->
                    _ui.update { it.copy(cargando = false, configurado = true, error = e.message ?: "Error desconocido") }
                }
            )
        }
    }

    /** Cambia el chip activo; si es OT/Criticas dispara su propia query remota (ver consultarFiltroRemoto). */
    fun seleccionarFiltro(filtro: AlertFiltro) {
        if (_ui.value.filtro == filtro) return
        _ui.update { it.copy(filtro = filtro) }
        when (filtro) {
            AlertFiltro.TODAS, AlertFiltro.IT -> filtroJob?.cancel()
            AlertFiltro.OT, AlertFiltro.CRITICAS -> consultarFiltroRemoto(filtro)
        }
    }

    private fun consultarFiltroRemoto(filtro: AlertFiltro) {
        val stored = CredentialStore.load(getApplication())
        if (!stored.isComplete) return
        filtroJob?.cancel()
        _ui.update { it.copy(cargandoFiltro = true, errorFiltro = null) }
        filtroJob = viewModelScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                try {
                    val config = ArgosConfig(stored.baseUrl, stored.username, stored.password, stored.useDashboardProxy)
                    val repo = AlertsRepository(ArgosAlertsSourceFactory.create(config))
                    val filtroExtra = when (filtro) {
                        AlertFiltro.OT -> FiltrosOpenSearch.grupoOt()
                        AlertFiltro.CRITICAS -> FiltrosOpenSearch.nivelMinimo(NIVEL_CRITICO)
                        else -> null
                    }
                    Result.success(repo.fetchAlerts(LIMITE_FILTRO_REMOTO, VENTANA_FILTRO_REMOTO_MIN, filtroExtra))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            resultado.fold(
                onSuccess = { alertas ->
                    _ui.update {
                        it.copy(
                            cargandoFiltro = false,
                            alertasFiltroRemoto = alertas.sortedByDescending { a -> a.timestampMillis ?: 0L },
                            errorFiltro = null
                        )
                    }
                },
                onFailure = { e ->
                    _ui.update { it.copy(cargandoFiltro = false, errorFiltro = e.message ?: "Error desconocido") }
                }
            )
        }
    }

    /**
     * PASO 2 (ampliado): notificacion LOCAL para cualquier alerta NUEVA de
     * severidad Media, Alta o Critica -- semaforo ambar o rojo, ver
     * colorSeveridad en AlertsScreen.kt -- y actualizacion del estado de UI.
     * Comun al feed compartido y al refresco manual.
     */
    private fun procesarNuevaLista(ordenadas: List<AlertaCruda>, momento: Long) {
        val nuevas = AlertNotificationGate.filtrarNuevasNotificables(ordenadas, !primerProcesoHecho)
        primerProcesoHecho = true
        nuevas.forEach { AlertNotifier.notificarAlerta(getApplication(), it) }

        _ui.update {
            it.copy(
                cargando = false,
                configurado = true,
                alertas = ordenadas,
                error = null,
                ultimaActualizacion = momento
            )
        }
    }

    override fun onCleared() {
        filtroJob?.cancel()
        super.onCleared()
    }
}
