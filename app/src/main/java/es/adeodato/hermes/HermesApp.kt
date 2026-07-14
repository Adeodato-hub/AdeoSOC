package es.adeodato.hermes

import android.app.Application
import es.adeodato.hermes.monitor.AlertMonitorService
import es.adeodato.hermes.monitor.MonitorPrefs
import es.adeodato.hermes.notify.AlertNotifier

/**
 * Fase 1 -- SOC de bolsillo de solo lectura sobre ARGOS (Wazuh). Sin acciones
 * destructivas ni de escritura contra el manager: solo lee alertas y deriva
 * el estado de activos.
 */
class HermesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AlertNotifier.crearCanales(this)

        // Autocorreccion del servicio de vigilancia: aqui (Application.onCreate,
        // no en un ViewModel de una pantalla concreta) se ejecuta SIEMPRE que
        // arranca el proceso, sin depender de que el usuario visite Ajustes.
        // Un reinstall (adb install -r) mata el servicio pero no toca la
        // preferencia -- sin esto, el toggle queda en "true" con el servicio
        // realmente muerto hasta que alguien lo note y lo toque a mano.
        if (MonitorPrefs.isEnabled(this) && !AlertMonitorService.estaCorriendo(this)) {
            AlertMonitorService.start(this)
        }
    }
}
