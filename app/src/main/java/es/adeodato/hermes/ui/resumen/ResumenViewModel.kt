package es.adeodato.hermes.ui.resumen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import es.adeodato.hermes.data.AlertsRepository
import es.adeodato.hermes.data.model.OtResumen
import es.adeodato.hermes.data.model.ShiftSummary
import es.adeodato.hermes.data.model.aOtResumen
import es.adeodato.hermes.data.network.ArgosAlertsSourceFactory
import es.adeodato.hermes.data.network.ArgosConfig
import es.adeodato.hermes.data.network.FiltrosOpenSearch
import es.adeodato.hermes.data.network.ShiftSummarySourceFactory
import es.adeodato.hermes.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Igual que AssetsViewModel: consulta OT dedicada (24h, propio limite), NO el feed IT. */
private const val VENTANA_OT_MIN = 24 * 60
private const val LIMITE_OT = 200

data class ResumenUiState(
    val cargando: Boolean = false,
    val resumen: ShiftSummary? = null,
    val otResumen: OtResumen? = null,
    val error: String? = null,
    val configurado: Boolean = false
)

/**
 * Pestaña "Resumen": ultimo resumen de 24h de argos-shift-summary + card OT,
 * cada uno con su propio refresco independiente (uno no bloquea al otro si
 * falla). La card OT usa una query dedicada (rule.groups="ot"/data.integration,
 * 24h -- ver FiltrosOpenSearch.grupoOt()), NO el feed IT de 200/24h: ese feed
 * se llena de ruido IT y la ultima alerta OT queda fuera del lote (mismo
 * "enterramiento" que ya se arreglo en el chip OT de Alertas y en Activos).
 */
class ResumenViewModel(application: Application) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(ResumenUiState())
    val ui: StateFlow<ResumenUiState> = _ui

    init {
        refrescar()
    }

    fun refrescar() {
        val stored = CredentialStore.load(getApplication())
        if (!stored.isComplete) {
            _ui.update { it.copy(configurado = false) }
            return
        }
        _ui.update { it.copy(cargando = true, configurado = true, error = null) }
        val config = ArgosConfig(stored.baseUrl, stored.username, stored.password, stored.useDashboardProxy)

        viewModelScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                try {
                    Result.success(ShiftSummarySourceFactory.create(config).fetchLatest())
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            resultado.fold(
                onSuccess = { resumen -> _ui.update { it.copy(cargando = false, resumen = resumen, error = null) } },
                onFailure = { e -> _ui.update { it.copy(cargando = false, error = e.message ?: "Error desconocido") } }
            )
        }

        viewModelScope.launch {
            val otResumen = withContext(Dispatchers.IO) {
                try {
                    val repo = AlertsRepository(ArgosAlertsSourceFactory.create(config))
                    repo.fetchAlerts(LIMITE_OT, VENTANA_OT_MIN, FiltrosOpenSearch.grupoOt()).aOtResumen()
                } catch (e: Exception) {
                    null
                }
            }
            _ui.update { it.copy(otResumen = otResumen) }
        }
    }
}
