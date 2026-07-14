package es.adeodato.hermes.data.model

import org.json.JSONObject

data class ReglaCount(val id: String, val desc: String, val n: Int)
data class ActivoCount(val nombre: String, val n: Int)

/** Resumen de turno de 24h generado por ARGOS (script soc-resumen + Ollama), indice argos-shift-summary. */
data class ShiftSummary(
    val fecha: String,
    val ventana: String,
    val total: Int,
    val porNivel: Map<String, Int>,
    val ambar: Int,
    val rojo: Int,
    val topReglas: List<ReglaCount>,
    val topActivos: List<ActivoCount>,
    val textoIa: String,
    val generadoEn: String
) {
    companion object {
        /** Devuelve null (en vez de lanzar) si falta el campo minimo "fecha". */
        fun fromSource(source: JSONObject): ShiftSummary? {
            val fecha = source.optStringOrNull("fecha") ?: return null

            val porNivel = linkedMapOf<String, Int>()
            source.optJSONObject("por_nivel")?.let { obj ->
                obj.keys().forEach { k -> porNivel[k] = obj.optInt(k, 0) }
            }

            val topReglas = mutableListOf<ReglaCount>()
            source.optJSONArray("top_reglas")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    topReglas.add(
                        ReglaCount(
                            id = o.optStringOrNull("id") ?: "?",
                            desc = o.optStringOrNull("desc") ?: "?",
                            n = o.optInt("n", 0)
                        )
                    )
                }
            }

            val topActivos = mutableListOf<ActivoCount>()
            source.optJSONArray("top_activos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    topActivos.add(
                        ActivoCount(
                            nombre = o.optStringOrNull("nombre") ?: "?",
                            n = o.optInt("n", 0)
                        )
                    )
                }
            }

            return ShiftSummary(
                fecha = fecha,
                ventana = source.optStringOrNull("ventana") ?: "24h",
                total = source.optInt("total", 0),
                porNivel = porNivel,
                ambar = source.optInt("ambar", 0),
                rojo = source.optInt("rojo", 0),
                topReglas = topReglas,
                topActivos = topActivos,
                textoIa = source.optStringOrNull("texto_ia") ?: "",
                generadoEn = source.optStringOrNull("generado_en") ?: ""
            )
        }
    }
}
