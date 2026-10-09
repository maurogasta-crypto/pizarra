package com.maurogasta.pizarra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
// Avisos.kt — Los avisos del ecosistema, en el teléfono. Sello: pizarra-14
//
// tiempos:V10 (9-oct-2026). Mauro: «La configuración de los avisos para cada
// usuario estará concentrada en la APK… un aviso que pueda concentrar de manera
// centralizada los avisos del ecosistema» — y después: «Pizarra para todos por
// igual, para que cada quien tenga la opción de instalar los avisos en su
// teléfono directamente».
//
// ── CÓMO LLEGA UN AVISO ─────────────────────────────────────────────────────
// Claude (herramientas/avisos.mjs de `datos`) deja cada aviso en `avisos/` de
// la base de la PERSONA —Tiempos para Mauro y Florencia; Casa Verde, CasaYourte
// o remate para los demás—: {uid, sitio, tema, texto, creadoEn, leido}. La regla
// de cada base deja leer sólo los propios y marcar `leido`, nada más. Esta app
// los trae al abrir la app y con el trabajo de cada hora, y muestra
// los nuevos como notificación. No hay un servidor que empuje: el atraso es,
// como mucho, una hora.
//
// ── LA CONFIGURACIÓN ─────────────────────────────────────────────────────────
// Un CANAL de Android por sitio («Avisos · Casa Verde», «Avisos · remate»…):
// cada uno lo silencia o lo deja sonar desde los ajustes del teléfono, y eso es
// la configuración concentrada en la app. WhatsApp sigue siendo de cada sitio
// («Mis avisos por WhatsApp»).
// ─────────────────────────────────────────────────────────────────────────────

/** Los sitios del ecosistema: de dónde puede venir un aviso. Con `cuenta`
 *  (proyecto, clave web pública y la dirección desde donde se la usa) los que
 *  tienen gente que entra; los otros sólo dan nombre a un canal. */
data class Sitio(val id: String, val nombre: String, val proyecto: String = "", val clave: String = "",
                 val referer: String = "", val panel: String = "") {
    val cuenta: Boolean get() = proyecto.isNotEmpty()
    val docs: String get() = "projects/$proyecto/databases/(default)/documents"
}

// Las claves web son públicas por diseño (están en el código de cada sitio):
// identifican el proyecto, no dan permisos. Lo que abre o cierra son las reglas.
val SITIOS = listOf(
    Sitio("tiempos", "Tiempos (Mauro y Florencia)", "tiempos-71d42", CLAVE_WEB,
        "https://maurogasta-crypto.github.io/tiempos/", "https://maurogasta-crypto.github.io/tiempos/"),
    Sitio("casaverde", "Casa Verde", "casaverde-20", "AIzaSyDG12FsMYyGVzkodq07N1SSWQfMcTJ-3yM",
        "https://casaverdecanas.com.br/interno/", "https://casaverdecanas.com.br/interno/"),
    Sitio("casayourte", "CasaYourte", "casayourte-mauro", "AIzaSyDDD_xvpC4I_ec2OZymyDqVsm1K0ISTr4Q",
        "https://casayourte.com/admin.html", "https://casayourte.com/admin.html"),
    Sitio("remate", "remateTaller", "remate-acbc9", "AIzaSyB2ZT8nLzhcejyqdOA1Ipuwaipm3KTAaRU",
        "https://rematetaller.github.io/remate/interno/", "https://rematetaller.github.io/remate/interno/panel.html"),
    Sitio("panel", "El panel"),
    Sitio("hilux", "La Hilux"),
)

fun sitio(id: String?): Sitio = SITIOS.firstOrNull { it.id == id && it.cuenta } ?: SITIOS[0]
fun nombreSitio(id: String?): String = SITIOS.firstOrNull { it.id == id }?.nombre ?: (id ?: "").ifEmpty { "Claude" }

data class Aviso(val id: String, val sitio: String, val tema: String, val texto: String, val creado: String, val leido: Boolean)

private val ICONO = mapOf("urgente" to "⚠️", "llegada" to "🏡", "novedades" to "🔔", "pedido" to "💬",
    "resumen" to "📋", "recordatorio" to "⏰", "prueba" to "🔧")
fun iconoTema(t: String) = ICONO[t] ?: "🔔"

/** Mis avisos: los que tienen MI uid (la regla no deja ver otros). */
fun consultaAvisos(uid: String): JSONObject = JSONObject().put("structuredQuery", JSONObject()
    .put("from", JSONArray().put(JSONObject().put("collectionId", "avisos")))
    .put("where", JSONObject().put("fieldFilter", JSONObject()
        .put("field", JSONObject().put("fieldPath", "uid"))
        .put("op", "EQUAL")
        .put("value", JSONObject().put("stringValue", uid))))
    .put("limit", 200))

private fun cadena(f: JSONObject, k: String): String = f.optJSONObject(k)?.let {
    it.optString("stringValue", "").ifEmpty { it.optString("timestampValue", "") } } ?: ""

/** Lo que contesta la base, leído con desconfianza; los más nuevos primero. */
fun avisosDeConsulta(respuesta: JSONArray): List<Aviso> = (0 until respuesta.length()).mapNotNull { i ->
    val d = respuesta.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
    val id = d.optString("name", "").substringAfterLast('/', "")
    val f = d.optJSONObject("fields") ?: return@mapNotNull null
    val texto = cadena(f, "texto").trim()
    if (id.isEmpty() || texto.isEmpty()) null
    else Aviso(id, cadena(f, "sitio"), cadena(f, "tema"), texto.take(600), cadena(f, "creadoEn"),
        f.optJSONObject("leido")?.optBoolean("booleanValue", false) ?: false)
}.sortedByDescending { it.creado }

/** Marcar UNO como leído: sólo el campo `leido` (es lo único que la regla deja). */
fun cuerpoLeido(docs: String, id: String): JSONObject = JSONObject().put("writes", JSONArray().put(JSONObject()
    .put("update", JSONObject().put("name", "$docs/avisos/$id")
        .put("fields", JSONObject().put("leido", JSONObject().put("booleanValue", true))))
    .put("updateMask", JSONObject().put("fieldPaths", JSONArray().put("leido")))
    .put("currentDocument", JSONObject().put("exists", true))))

/** Los que hay que mostrar como notificación: sin leer y que este teléfono
 *  todavía no mostró. Los de más de 3 días no se notifican (quedan en la lista):
 *  una app recién instalada no puede llenar la barra con lo de la semana pasada. */
fun paraNotificar(avisos: List<Aviso>, yaMostrados: Set<String>, ahora: Long): List<Aviso> = avisos.filter { a ->
    !a.leido && a.id !in yaMostrados && (instante(a.creado)?.let { ahora - it < 3 * 24 * 3600 * 1000L } ?: false)
}

fun instante(iso: String): Long? = try { java.time.Instant.parse(iso).toEpochMilli() } catch (e: Exception) { null }

object Buzon {
    private fun prefs(c: Context) = c.getSharedPreferences("avisos", Context.MODE_PRIVATE)
    fun canal(sitio: String) = "avisos-$sitio"

    var Context.avisosGuardados: List<Aviso>
        get() = try {
            val a = JSONArray(prefs(this).getString("lista", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let {
                Aviso(it.optString("id"), it.optString("sitio"), it.optString("tema"), it.optString("texto"), it.optString("creado"), it.optBoolean("leido")) } }
        } catch (e: Exception) { emptyList() }
        set(v) = prefs(this).edit().putString("lista", JSONArray().apply {
            v.take(60).forEach { put(JSONObject().put("id", it.id).put("sitio", it.sitio).put("tema", it.tema)
                .put("texto", it.texto).put("creado", it.creado).put("leido", it.leido)) } }.toString()).apply()

    /** Un canal por sitio: así se silencia uno sin silenciar los demás. */
    fun crearCanales(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        for (s in SITIOS) nm.createNotificationChannel(NotificationChannel(canal(s.id), "Avisos · ${s.nombre}",
            NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Lo que Claude te avisa sobre ${s.nombre}." })
    }

    /** Trae, guarda y muestra lo nuevo. Lo llaman la sincronización y la pantalla. */
    fun ponerAlDia(c: Context, nube: Nube): List<Aviso> {
        val todos = nube.avisos()
        c.avisosGuardados = todos
        val p = prefs(c)
        val vistos = p.getStringSet("mostrados", emptySet()) ?: emptySet()
        val nuevos = paraNotificar(todos, vistos, System.currentTimeMillis())
        nuevos.forEach { mostrar(c, it) }
        // Se recuerdan sólo los que siguen en la base: el conjunto no crece para siempre.
        val enLaBase = todos.map { it.id }.toSet()
        p.edit().putStringSet("mostrados", (vistos intersect enLaBase) + nuevos.map { it.id }).apply()
        if (nuevos.isNotEmpty()) Bitacora.anotar(c, "avisos: ${nuevos.size} nuevo(s)")
        return todos
    }

    private fun mostrar(c: Context, a: Aviso) {
        crearCanales(c)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val abrir = PendingIntent.getActivity(c, 20, Intent(c, MainActivity::class.java).putExtra("avisos", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Un sitio que esta versión no conoce va al canal del panel: sin canal, Android no la muestra.
        val n = Notification.Builder(c, canal(SITIOS.firstOrNull { it.id == a.sitio }?.id ?: "panel"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${iconoTema(a.tema)} ${nombreSitio(a.sitio)}")
            .setContentText(a.texto)
            .setStyle(Notification.BigTextStyle().bigText(a.texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        try { nm.notify(("aviso:" + a.id).hashCode(), n) } catch (e: SecurityException) { /* sin permiso de notificaciones */ }
    }
}
