package com.maurogasta.pizarra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
// pizarra-80 (29-oct-2026, Mauro: «yo le llamé 15 y 13C, A15 es el de Florencia…
// los nombres no se actualizan»). Un mensaje lleva escrito el nombre del teléfono
// que tenía al mandarlo (`dispositivo`) y a quién iba (`para` = «uid|teléfono»).
// Hasta pizarra-79, ponerle un nombre nuevo a un teléfono dejaba las pestañas con
// el viejo y, peor, lo que otro le mandaba al nombre viejo ya no era «para mí».
//
// Ahora el cambio de nombre se PUBLICA en `familia/telefonos` de Tiempos —un mapa
// `nombres` «uid|viejo» → «nuevo», sin regla nueva: `familia/` ya lo escriben los
// miembros— y cada Pizarra lo aplica a los mensajes al leerlos. Nada se reescribe
// en la base: el mensaje sigue diciendo lo que dijo; la pantalla lo traduce.
// ─────────────────────────────────────────────────────────────────────────────

/** «uid|nombre» siguiendo los cambios de nombre, de viejo a nuevo. Un círculo no cuelga: corta a los 10. */
fun nombreAlDia(clave: String, renombres: Map<String, String>): String {
    if (!clave.contains('|') || clave.endsWith("|*")) return clave
    val uid = clave.substringBefore('|')
    var k = clave
    repeat(10) { val nuevo = renombres[k] ?: return k; val sig = claveDe(uid, nuevo); if (sig == k) return k; k = sig }
    return k
}

/** Los mensajes con los nombres de hoy (de quién y para quién). Sin cambios de nombre, los mismos. */
fun aplicarRenombres(ms: List<Mensaje>, renombres: Map<String, String>): List<Mensaje> =
    if (renombres.isEmpty()) ms else ms.map { m ->
        val disp = if (m.dispositivo.isBlank()) m.dispositivo else nombreAlDia(claveDe(m.de, m.dispositivo), renombres).substringAfter('|')
        val para = if (m.para.isBlank()) m.para else nombreAlDia(m.para, renombres)
        if (disp == m.dispositivo && para == m.para) m else m.copy(dispositivo = disp, para = para)
    }

/** El mapa `nombres` de `familia/telefonos` en la respuesta de un batchGet (o vacío). */
fun renombresDeRespuesta(r: JSONArray): Map<String, String> {
    val f = r.optJSONObject(0)?.optJSONObject("found")?.optJSONObject("fields")
        ?.optJSONObject("nombres")?.optJSONObject("mapValue")?.optJSONObject("fields") ?: return emptyMap()
    return f.keys().asSequence().mapNotNull { k -> f.optJSONObject(k)?.optString("stringValue")?.takeIf { it.isNotBlank() && k.contains('|') }?.let { k to it } }.toMap()
}

/** Un segmento de ruta de campo de Firestore entre acentos graves (el nombre lleva «|», espacios, puntos). */
private fun segmento(s: String) = "`" + s.replace("\\", "\\\\").replace("`", "\\`") + "`"

/**
 * El cambio de nombre de ESTE teléfono: cada nombre viejo apunta al nuevo, y si el nuevo había sido
 * antes un nombre viejo de este mismo teléfono, esa flecha se borra (si no, haría un círculo).
 * Se escribe con máscara: no pisa los de los demás teléfonos.
 */
fun cuerpoRenombre(docs: String, uid: String, viejos: Collection<String>, nuevo: String): JSONObject {
    val vs = viejos.map { it.trim() }.filter { it.isNotEmpty() && it != nuevo }.distinct()
    val campos = JSONObject()
    vs.forEach { campos.put(claveDe(uid, it), JSONObject().put("stringValue", nuevo)) }
    val mascara = JSONArray()
    (vs + nuevo).forEach { mascara.put("nombres." + segmento(claveDe(uid, it))) }
    return JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/familia/telefonos")
            .put("fields", JSONObject().put("nombres", JSONObject().put("mapValue", JSONObject().put("fields", campos)))))
        .put("updateMask", JSONObject().put("fieldPaths", mascara))))
}

object Telefonos {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("telefono", Context.MODE_PRIVATE)

    /** Los cambios de nombre conocidos (de la base, más los propios aunque todavía no hayan subido). */
    fun renombres(c: Context): Map<String, String> = try {
        val o = JSONObject(prefs(c).getString("renombres", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotBlank() }
    } catch (e: Exception) { emptyMap() }

    fun guardar(c: Context, m: Map<String, String>) =
        prefs(c).edit().putString("renombres", JSONObject(m).toString()).apply()

    /** Los nombres que tuvo este teléfono: el del sistema y los que se le pusieron antes. */
    fun anteriores(c: Context): Set<String> = prefs(c).getStringSet("anteriores", emptySet()).orEmpty()

    /** Anota el nombre que deja de usar (se llama ANTES de cambiarlo). */
    fun recordar(c: Context, viejo: String) {
        if (viejo.isBlank()) return
        prefs(c).edit().putStringSet("anteriores", anteriores(c) + viejo).remove("publicado").apply()
    }

    /** ¿Hay que publicar el nombre de este teléfono? (nunca publicado, o publicado otro). */
    fun faltaPublicar(c: Context, nombre: String): Boolean = prefs(c).getString("publicado", null) != nombre
    fun publicado(c: Context, nombre: String) = prefs(c).edit().putString("publicado", nombre).apply()
}
