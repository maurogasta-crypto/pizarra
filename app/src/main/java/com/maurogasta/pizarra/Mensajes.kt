package com.maurogasta.pizarra

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Mensajes.kt — Los mensajes entre las personas, y las novedades de todo el
// ecosistema en un widget. Sello: pizarra-69
//
// Mauro, 18-oct-2026: «los mensajes entre los usuarios de pizarra. Por el
// momento Florencia y Mauro, pero pudiendo diferenciar cuál dispositivo. Un
// widget de mensajes en el que se muestren todas las noticias de cada
// aplicación y mensajes como los chats que se establecían entre usuarios en
// Casa Verde, pero ahora para todo el ecosistema».
//
// ── LOS MENSAJES ─────────────────────────────────────────────────────────────
// Viven en `mensajes/` de Tiempos (reglas v15): una charla de los dos, cada
// mensaje con `de` (el uid), `nombre`, `dispositivo` —el nombre del teléfono
// del que salió, porque Mauro tiene la Pizarra en más de uno— y `texto`. No se
// editan; cada uno borra los suyos. El agente los lee y no escribe.
//
// ── LAS NOVEDADES ────────────────────────────────────────────────────────────
// El widget junta, por fecha, tres cosas que ya existían por separado: los
// mensajes, los avisos de Claude (el buzón, Avisos.kt) y lo que espera en Casa
// Verde —pedidos de alquiler y cambios de reservas para confirmar—, si este
// teléfono entró a Casa Verde. Nada se copia: cada cosa se lee de donde vive.
//
// ── LAS CONVERSACIONES (pizarra-61, reglas v17 de Tiempos) ─────────────────
// Mauro, 24-oct: «una interfaz como de chat… en pestañas dentro del mismo
// widget las conversaciones con cada uno… con el teléfono de Florencia, con
// el Redmi 15, el Redmi 13… además de una vista general». Un mensaje puede
// llevar `para` = «<uid>|<teléfono>»: va a ESE teléfono. Sin `para` es para
// todos, como siempre. Una conversación es con UN teléfono: con el de otra
// persona, lo que ella le escribe a cualquiera de mis teléfonos y lo que yo
// le escribo desde cualquiera de los míos; con otro teléfono MÍO, lo que se
// escriben ese y éste (`enConversacion`). Un teléfono nuevo aparece en cuanto
// escribe su primer mensaje. El widget muestra «Todo» y hasta tres pestañas
// (las conversaciones más recientes); la charla, todas.
// Desde pizarra-62 (Mauro: «que pueda aparecer la persona con la que quiero
// ver comunicaciones») la conversación con OTRA persona es UNA, con todos sus
// teléfonos: «<uid>|*» (lo que le escribo le llega a todos los suyos); mis
// otros teléfonos siguen siendo uno por uno. Y un mensaje puede ser de VOZ
// (Handy.kt): `audio` es el id en `audios/` y `dur`, los segundos.
//
// ── CADA CUÁNTO ──────────────────────────────────────────────────────────────
// No hay un servidor que empuje: de 8 a 23 se pregunta cada 10 minutos (la
// misma cuenta que Tibio.kt), y con el trabajo de cada hora, al abrir la app y
// con ⟳. Un mensaje del otro —o tuyo desde OTRO teléfono— avisa con una
// notificación; los que escribís acá no.
// ─────────────────────────────────────────────────────────────────────────────

const val COLECCION_MENSAJES = "mensajes"
const val CANAL_MENSAJES = "mensajes"
const val ACCION_MENSAJES = "com.maurogasta.pizarra.MENSAJES_ACTUALIZAR"
private const val ACCION_CHARLA = "com.maurogasta.pizarra.CHARLA"
const val URL_PEDIDOS_CV = "https://casaverdecanas.com.br/interno/pedidos-alquiler.html"
const val URL_RESERVAS_CV = "https://casaverdecanas.com.br/interno/reservas.html"

data class Mensaje(val id: String, val de: String, val nombre: String, val dispositivo: String, val texto: String, val creado: String,
                   val para: String = "", val audio: String = "", val dur: Int = 0,
                   val archivo: String = "", val nombreArchivo: String = "", val partes: Int = 0,
                   /** pizarra-75 (reglas v22): un audio del HANDY, que el otro teléfono escucha solo al llegar. */
                   val handy: Boolean = false)

/** La conversación con TODOS los teléfonos de una persona (pizarra-62). */
fun claveDePersona(uid: String) = "$uid|*"

/** La dirección de un teléfono: «<uid>|<nombre del teléfono>». Es lo que va en `para`. */
fun claveDe(uid: String, dispositivo: String) = "$uid|$dispositivo"

/** Una conversación con UN teléfono (pizarra-61). */
data class Conversacion(val clave: String, val uid: String, val dispositivo: String, val nombre: String, val ultima: String)

/** Una línea del widget: un mensaje, un aviso de Claude o lo que espera en Casa Verde. */
data class Novedad(val id: String, val cuando: String, val titulo: String, val texto: String, val tipo: String, val mio: Boolean = false,
                   val url: String = "", val aviso: String = "")

/** Los últimos mensajes, del más nuevo al más viejo. Sin filtro: no pide índice compuesto. */
fun consultaMensajes(cuantos: Int = 150, desde: String = ""): JSONObject = JSONObject().put("structuredQuery", JSONObject()
    .put("from", JSONArray().put(JSONObject().put("collectionId", COLECCION_MENSAJES)))
    // pizarra-62: después de la primera vez, sólo lo NUEVO (un filtro y un orden del mismo campo: sin índice compuesto).
    // Cada pasada sin novedades cuesta UNA lectura, no 150: con 10 teléfonos cada 10 minutos entra holgado en el cupo gratis.
    .apply { if (desde.isNotEmpty()) put("where", JSONObject().put("fieldFilter", JSONObject()
        .put("field", JSONObject().put("fieldPath", "creadoEn")).put("op", "GREATER_THAN")
        .put("value", JSONObject().put("timestampValue", desde)))) }
    .put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "creadoEn")).put("direction", "DESCENDING")))
    .put("limit", cuantos))

/** Lo que contesta la base, con desconfianza: sin texto o sin quién, no entra. */
fun mensajesDeConsulta(r: JSONArray): List<Mensaje> = (0 until r.length()).mapNotNull { i ->
    val d = r.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
    val f = d.optJSONObject("fields") ?: return@mapNotNull null
    fun s(k: String) = f.optJSONObject(k)?.let { it.optString("stringValue", "").ifEmpty { it.optString("timestampValue", "") } }.orEmpty()
    val id = d.optString("name").substringAfterLast('/')
    if (id.isEmpty() || s("texto").isBlank() || s("de").isEmpty()) null
    else Mensaje(id, s("de"), s("nombre").take(40), s("dispositivo").take(60), s("texto").take(2000), s("creadoEn"), s("para").take(140),
        s("audio").take(40), (f.optJSONObject("dur")?.optString("integerValue")?.toIntOrNull() ?: 0).coerceIn(0, 60),
        s("archivo").take(40), s("nombreArchivo").take(120), (f.optJSONObject("partes")?.optString("integerValue")?.toIntOrNull() ?: 0).coerceIn(0, PARTES_MAX),
        f.optJSONObject("handy")?.optBoolean("booleanValue", false) == true && s("audio").isNotEmpty())
}.sortedByDescending { it.creado }

/** Lo guardado más lo nuevo, sin repetir, del más nuevo al más viejo. */
fun mezclarMensajes(guardados: List<Mensaje>, nuevos: List<Mensaje>, tope: Int = 150): List<Mensaje> =
    (nuevos + guardados).distinctBy { it.id }.sortedByDescending { it.creado }.take(tope)

/** El mensaje nuevo, con la forma EXACTA que deja crear la regla de Tiempos: la v15 sin `para`, la v17 con él. */
fun cuerpoMensaje(docs: String, id: String, uid: String, nombre: String, dispositivo: String, texto: String, para: String = "",
                  audio: String = "", dur: Int = 0, archivo: String = "", nombreArchivo: String = "", partes: Int = 0,
                  handy: Boolean = false): JSONObject {
    fun s(v: String) = JSONObject().put("stringValue", v)
    val campos = JSONObject().put("de", s(uid)).put("nombre", s(nombre.take(40))).put("dispositivo", s(dispositivo.take(60)))
        .put("texto", s(texto.trim().take(2000)))
    if (para.isNotBlank()) campos.put("para", s(para.take(140)))
    // pizarra-62 (reglas v18): un mensaje de voz lleva el id del audio y cuántos segundos dura.
    if (audio.isNotBlank()) campos.put("audio", s(audio.take(40))).put("dur", JSONObject().put("integerValue", dur.coerceIn(1, 60).toString()))
    // pizarra-75 (reglas v22): del handy, para que suene solo del otro lado. Sólo con audio: la regla lo pide.
    if (handy && audio.isNotBlank()) campos.put("handy", JSONObject().put("booleanValue", true))
    // pizarra-64 (reglas v20): un archivo, por su id, su nombre y en cuántas partes está.
    if (archivo.isNotBlank()) campos.put("archivo", s(archivo.take(40))).put("nombreArchivo", s(nombreArchivo.take(120)))
        .put("partes", JSONObject().put("integerValue", partes.coerceIn(1, PARTES_MAX).toString()))
    return JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_MENSAJES/$id").put("fields", campos))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))
}

/** Quién y desde dónde: «Florencia · Redmi 13C»; lo tuyo, «Yo», y el teléfono sólo si es OTRO. */
fun firmaMensaje(m: Mensaje, yo: String, miTelefono: String, nombres: Map<String, String> = emptyMap()): String {
    val quien = if (m.de == yo) "Yo" else m.nombre.ifBlank { "Alguien" }
    val tel = m.dispositivo.ifBlank { "" }
    val base = if (tel.isEmpty() || (m.de == yo && tel == miTelefono)) quien else "$quien · $tel"
    if (m.para.isEmpty()) return base
    val (u, d) = m.para.substringBefore('|') to m.para.substringAfter('|')
    // A una persona entera: si soy yo, no hace falta decirlo; si es otra, su nombre.
    if (d == "*") return if (u == yo) base else "$base → ${nombres[u] ?: "su persona"}"
    val a = if (u == yo && d == miTelefono) "este teléfono" else d
    return "$base → $a"
}

/** ¿Es para mí? Para todos, para este teléfono, o de otra persona a cualquiera de mis teléfonos. */
fun esParaMi(m: Mensaje, yo: String, miTelefono: String): Boolean =
    m.para.isEmpty() || m.para == claveDe(yo, miTelefono) || (m.para.startsWith("$yo|") && m.de != yo)

/** ¿Este mensaje es de la conversación con ese teléfono, vista desde este? */
fun enConversacion(m: Mensaje, c: Conversacion, yo: String, miTelefono: String): Boolean =
    if (c.uid != yo) (m.de == c.uid && m.para.startsWith("$yo|")) || (m.de == yo && m.para.startsWith("${c.uid}|"))
    else (m.de == yo && m.dispositivo == c.dispositivo && m.para == claveDe(yo, miTelefono)) ||
        (m.de == yo && m.dispositivo == miTelefono && m.para == c.clave)

/** Los teléfonos con los que se puede hablar, la conversación más reciente primero. Este teléfono no. */
fun conversaciones(ms: List<Mensaje>, yo: String, miTelefono: String): List<Conversacion> {
    val nombres = mutableMapOf<String, String>()
    val claves = LinkedHashSet<String>()
    for (m in ms) {
        if (m.dispositivo.isBlank()) continue
        if (m.nombre.isNotBlank()) nombres.putIfAbsent(m.de, m.nombre)
        // La otra persona, una sola vez con todos sus teléfonos; mis otros teléfonos, uno por uno.
        val k = if (m.de == yo) claveDe(yo, m.dispositivo) else claveDePersona(m.de)
        if (k != claveDe(yo, miTelefono)) claves.add(k)
        if (m.para.contains('|')) {
            val u = m.para.substringBefore('|')
            val kp = if (u == yo) m.para else claveDePersona(u)
            if (kp != claveDe(yo, miTelefono) && kp != claveDePersona(yo)) claves.add(kp)
        }
    }
    return claves.map { k ->
        val u = k.substringBefore('|'); val d = k.substringAfter('|')
        val c0 = Conversacion(k, u, d, if (u == yo) "Yo" else nombres[u] ?: "Alguien", "")
        c0.copy(ultima = ms.filter { enConversacion(it, c0, yo, miTelefono) }.maxOfOrNull { it.creado }
            ?: ms.filter { (if (it.de == yo) claveDe(yo, it.dispositivo) else claveDePersona(it.de)) == k }.maxOfOrNull { it.creado }.orEmpty())
    }.sortedByDescending { it.ultima }
}

/** El nombre de la pestaña: la persona; con el teléfono si tiene más de uno; mis otros teléfonos, por su nombre. */
fun etiquetaConversacion(c: Conversacion, todas: List<Conversacion>, yo: String): String =
    if (c.uid == yo) "📱 " + c.dispositivo.take(16) else c.nombre.take(16)

/** Las pestañas del widget: hasta tres conversaciones, y la elegida siempre está. */
fun pestanasDelWidget(cs: List<Conversacion>, elegida: String, cuantas: Int = 3): List<Conversacion> {
    val l = cs.take(cuantas).toMutableList()
    val e = cs.firstOrNull { it.clave == elegida }
    if (e != null && e !in l) { if (l.size >= cuantas) l[cuantas - 1] = e else l.add(e) }
    return l
}

/** Lo que avisa: del otro, o mío desde otro teléfono; que este teléfono no mostró; de las últimas 24 h. */
fun mensajesParaAvisar(l: List<Mensaje>, yo: String, miTelefono: String, mostrados: Set<String>, ahora: Long): List<Mensaje> = l.filter { m ->
    m.id !in mostrados && (m.de != yo || m.dispositivo != miTelefono) && esParaMi(m, yo, miTelefono) &&
        (instante(m.creado)?.let { ahora - it < 24 * 3600_000L } ?: false)
}

/** Las novedades del widget, todas juntas y por fecha. */
fun armarNovedades(mensajes: List<Mensaje>, avisos: List<Aviso>, pedidosCV: Int, cambiosCV: Int, cuandoCV: String,
                   yo: String, miTelefono: String, tope: Int = 40): List<Novedad> {
    val l = mutableListOf<Novedad>()
    val nombres = mensajes.filter { it.nombre.isNotBlank() }.associate { it.de to it.nombre }
    for (m in mensajes) l.add(Novedad("m:" + m.id, m.creado, "💬 " + firmaMensaje(m, yo, miTelefono, nombres), m.texto, "mensaje", m.de == yo))
    // pizarra-58: cada renglón sabe adónde lleva (Abrir.kt): el aviso, a lo que dice; Casa Verde, a lo que espera.
    for (a in avisos) l.add(Novedad("a:" + a.id, a.creado, iconoTema(a.tema) + " " + nombreSitio(a.sitio), a.texto, "aviso",
        url = destinoDeAviso(a), aviso = a.id))
    if (pedidosCV > 0 || cambiosCV > 0) l.add(Novedad("cv", cuandoCV, "🏠 Casa Verde", listOfNotNull(
        // pizarra-57 (Mauro, 21-oct: «no debería decir pedidos de alquiler para contestar, debería decir
        // búsquedas de Facebook que coinciden»): son publicaciones que cuadran con «Qué busco ahora».
        if (pedidosCV > 0) "$pedidosCV búsqueda${if (pedidosCV == 1) "" else "s"} de Facebook que coincide${if (pedidosCV == 1) "" else "n"}" else null,
        if (cambiosCV > 0) "$cambiosCV cambio${if (cambiosCV == 1) "" else "s"} de reservas para confirmar" else null).joinToString(" · "), "casaverde",
        url = if (cambiosCV > 0) URL_RESERVAS_CV else URL_PEDIDOS_CV))
    return l.sortedByDescending { it.cuando }.take(tope)
}

/** El nombre del teléfono como lo ve la persona («Redmi 13C»); si no, marca y modelo. */
fun nombreDelTelefono(c: Context): String {
    // pizarra-75 (Mauro: «agregar un nombre más distintivo de cada dispositivo»): el que se le ponga en la
    // Pizarra manda; si no, el del sistema.
    aliasDelTelefono(c)?.let { return it }
    val n = try { android.provider.Settings.Global.getString(c.contentResolver, android.provider.Settings.Global.DEVICE_NAME) } catch (e: Throwable) { null }
    return (n?.trim()?.takeIf { it.isNotEmpty() } ?: "${Build.MANUFACTURER} ${Build.MODEL}").take(60)
}

/** pizarra-75: el nombre que se le puso a este teléfono en la Pizarra, o null. Fuera de las copias (prefs «telefono»). */
fun aliasDelTelefono(c: Context): String? =
    c.applicationContext.getSharedPreferences("telefono", Context.MODE_PRIVATE).getString("alias", null)?.trim()?.takeIf { it.isNotEmpty() }

/** Un nombre de teléfono que se puede usar: sin «|» (separa uid y teléfono en `para`), sin saltos, de 2 a 40. */
fun aliasValido(t: String): String? {
    val n = t.replace(Regex("[|\\n\\r\\t]"), " ").replace(Regex(" +"), " ").trim()
    return n.takeIf { it.length in 2..40 }
}

fun ponerAlias(c: Context, t: String?) {
    val p = c.applicationContext.getSharedPreferences("telefono", Context.MODE_PRIVATE).edit()
    if (t == null) p.remove("alias") else p.putString("alias", t)
    p.apply()
}

/**
 * pizarra-75 (Mauro: «un color diferente para cada uno»): el color de un teléfono. Se reparte en orden
 * alfabético entre los teléfonos que esta Pizarra conoce, así dos no comparten color mientras haya colores,
 * y todas las Pizarras que conocen los mismos teléfonos los pintan igual.
 */
val COLORES_TELEFONO = listOf("#4FC3F7", "#81C784", "#FFB74D", "#F06292", "#BA68C8", "#FFD54F", "#4DB6AC", "#E57373")
fun colorDeTelefono(nombre: String, conocidos: Collection<String>): String {
    val todos = (conocidos + nombre).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()
    val i = todos.indexOf(nombre.trim().lowercase()).coerceAtLeast(0)
    return COLORES_TELEFONO[i % COLORES_TELEFONO.size]
}

private val HORA_CORTA = DateTimeFormatter.ofPattern("dd/MM HH:mm")
fun horaCorta(iso: String): String = instante(iso)?.let { HORA_CORTA.format(java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: ""

object Charla {
    private fun prefs(c: Context) = c.getSharedPreferences("charla", Context.MODE_PRIVATE)

    var Context.mensajesGuardados: List<Mensaje>
        get() = try { JSONArray(prefs(this).getString("lista", "[]")).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Mensaje(it.optString("id"), it.optString("de"), it.optString("nombre"), it.optString("dispositivo"), it.optString("texto"), it.optString("creado"),
                it.optString("para"), it.optString("audio"), it.optInt("dur"), it.optString("archivo"), it.optString("nombreArchivo"),
                it.optInt("partes"), it.optBoolean("handy")) } } } }
            catch (e: Exception) { emptyList() }
        set(v) = prefs(this).edit().putString("lista", JSONArray().apply { v.take(150).forEach { put(JSONObject().put("id", it.id).put("de", it.de)
            .put("nombre", it.nombre).put("dispositivo", it.dispositivo).put("texto", it.texto).put("creado", it.creado).put("para", it.para)
            .put("audio", it.audio).put("dur", it.dur).put("archivo", it.archivo).put("nombreArchivo", it.nombreArchivo)
            .put("partes", it.partes).put("handy", it.handy)) } }.toString()).apply()

    /** La pestaña elegida en cada widget («» = Todo). */
    fun pestana(c: Context, widget: Int): String = prefs(c).getString("pestana_$widget", "").orEmpty()
    fun elegirPestana(c: Context, widget: Int, clave: String) = prefs(c).edit().putString("pestana_$widget", clave).apply()

    var Context.novedades: List<Novedad>
        get() = try { JSONArray(prefs(this).getString("novedades", "[]")).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Novedad(it.optString("id"), it.optString("cuando"), it.optString("titulo"), it.optString("texto"), it.optString("tipo"), it.optBoolean("mio")) } } } }
            catch (e: Exception) { emptyList() }
        set(v) = prefs(this).edit().putString("novedades", JSONArray().apply { v.forEach { put(JSONObject().put("id", it.id).put("cuando", it.cuando)
            .put("titulo", it.titulo).put("texto", it.texto).put("tipo", it.tipo).put("mio", it.mio)) } }.toString()).apply()

    var Context.estadoCharla: String
        get() = prefs(this).getString("estado", "").orEmpty()
        set(v) = prefs(this).edit().putString("estado", v).apply()

    /** Trae todo, avisa lo nuevo y redibuja el widget. Lo llaman el reloj, el trabajo de cada hora, la app y ⟳. Nunca tira. */
    fun ponerAlDia(c: Context) {
        val nube = Nube(c)
        try {
            if (!nube.conSesion || !nube.esTiempos) { c.estadoCharla = "los mensajes son de Tiempos: entrá con tu cuenta"; return }
            val yo = nube.uid.orEmpty(); val tel = nombreDelTelefono(c)
            val p = prefs(c)
            // pizarra-62: completo cada 6 horas (por si alguien borró uno); en el medio, sólo lo nuevo.
            val guardados = c.mensajesGuardados
            val completo = guardados.isEmpty() || System.currentTimeMillis() - p.getLong("completo", 0) > 6 * 3600_000L
            val ms = if (completo) nube.mensajes() else mezclarMensajes(guardados, nube.mensajes(guardados.maxOf { it.creado }))
            if (completo) p.edit().putLong("completo", System.currentTimeMillis()).apply()
            c.mensajesGuardados = ms
            val vistos = p.getStringSet("mostrados", emptySet()) ?: emptySet()
            val nuevos = mensajesParaAvisar(ms, yo, tel, vistos, System.currentTimeMillis())
            nuevos.forEach { avisar(c, it, yo, tel) }
            // pizarra-62: un audio para mí, recién llegado, se escucha solo si así se eligió (Handy.kt).
            try { Handy.alLlegar(c, nube, nuevos) } catch (e: Throwable) { Bitacora.anotar(c, "handy: ${e.javaClass.simpleName}") }
            try { Handy.limpiar(c, nube) } catch (e: Throwable) { }
            try { Archivos.limpiar(c, nube) } catch (e: Throwable) { }
            p.edit().putStringSet("mostrados", (vistos intersect ms.map { it.id }.toSet()) + nuevos.map { it.id }).apply()
            // Lo de Casa Verde, sólo si este teléfono entró ahí. Si falla, no tapa lo demás.
            val cv = CuentaCasaVerde(c)
            var pedidos = 0; var cambios = 0
            if (cv.conSesion) try { pedidos = cv.nuevos().size; cambios = cv.cambiosPendientes() } catch (e: Throwable) {}
            val ahora = isoUtc(System.currentTimeMillis())
            c.novedades = armarNovedades(ms, with(Buzon) { c.avisosGuardados }, pedidos, cambios, ahora, yo, tel)
            c.estadoCharla = "al día · " + horaCorta(ahora)
            if (nuevos.isNotEmpty()) Bitacora.anotar(c, "mensajes: ${nuevos.size} nuevo(s)")
        } catch (e: Throwable) {
            c.estadoCharla = (e.message ?: "no se pudo") + " · " + horaCorta(isoUtc(System.currentTimeMillis()))
        } finally {
            MensajesWidget.dibujar(c)
        }
    }

    fun mandar(c: Context, texto: String, para: String = "") {
        val nube = Nube(c)
        try { nube.mandarMensaje(texto.trim(), nombreDelTelefono(c), para) }
        catch (e: ErrorNube) {
            // Sin las reglas v17 la base no acepta `para`: se dice qué falta, no «no se pudo».
            if (para.isNotEmpty() && (e.message ?: "").startsWith("La base no deja"))
                throw ErrorNube("Para escribirle a un teléfono hay que publicar las reglas v17 de Tiempos. Mientras, escribí en «Todos».")
            throw e
        }
        ponerAlDia(c)
    }

    private fun avisar(c: Context, m: Mensaje, yo: String, tel: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CANAL_MENSAJES, "Mensajes", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Lo que te escriben desde otra Pizarra." })
        // Abre la charla en SU conversación (la de quien escribió), o en «Todos».
        val clave = if (m.para.isEmpty()) "" else claveDe(m.de, m.dispositivo)
        val abrir = PendingIntent.getActivity(c, ("abrir:" + clave).hashCode(), Intent(c, CharlaActivity::class.java).putExtra("clave", clave),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CANAL_MENSAJES)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("💬 " + firmaMensaje(m, yo, tel))
            .setContentText(m.texto)
            .setStyle(Notification.BigTextStyle().bigText(m.texto))
            .setContentIntent(abrir).setAutoCancel(true).build()
        try { nm.notify(("msj:" + m.id).hashCode(), n) } catch (e: SecurityException) { }
    }

    /** De 8 a 23, cada 10 minutos (proximoTibio); sin sesión de Tiempos, nada. */
    fun agendar(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 40, Intent(c, CharlaReceptor::class.java).setAction(ACCION_CHARLA),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Nube(c)
        if (!n.conSesion || !n.esTiempos) { am.cancel(pi); return }
        val cuando = proximoTibio(LocalDateTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setWindow(AlarmManager.RTC_WAKEUP, cuando, 2 * 60_000L, pi)
    }
}

class CharlaReceptor : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pendiente = goAsync()
        thread {
            // pizarra-59: cada 10 minutos se pone al día por Despertar; desde pizarra-74, lo barato
            // cada vez y todo cada 6 h (el cupo: Despertar.kt).
            try { if (enHorario(LocalDateTime.now())) Despertar.periodico(c) } catch (e: Throwable) { }
            finally { try { Charla.agendar(c) } catch (e: Throwable) { }; pendiente.finish() }
        }
    }
}

/** La charla: pestañas (Todos y una por teléfono), las burbujas y el campo para escribir (pizarra-61). */
class CharlaActivity : Activity() {
    private lateinit var lista: LinearLayout
    private lateinit var permisoLlamadas: TextView
    private lateinit var scroll: ScrollView
    private lateinit var pestanas: LinearLayout
    private lateinit var dice: TextView
    private lateinit var campo: TextView
    private var llamar: TextView? = null
    private lateinit var handy: Button
    private lateinit var estoyEn: TextView
    private var clave = ""

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val n = Nube(this)
        if (!n.conSesion || !n.esTiempos) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); return
        }
        clave = intent.getStringExtra("clave").orEmpty()
        val d = resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        // pizarra-65 (Mauro, 24-oct: «cuando aparece el teclado no se reajusta la pantalla, se pierden los
        // botones, y Mandar quedó deformado»): como cualquier chat. La lista toma lo que sobra (peso 1) y se
        // achica con el teclado; abajo los botones, de alto fijo y que nunca se deforman; Cerrar es la ✕ de arriba.
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(8)) }
        val arriba = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        arriba.addView(TextView(this).apply { text = "💬 Mensajes"; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        arriba.addView(TextView(this).apply { text = "✕"; textSize = 20f; gravity = Gravity.CENTER; contentDescription = "Cerrar"
            setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(44), dp(44)))
        caja.addView(arriba)
        // pizarra-75 (Mauro: «la identificación de cada dispositivo tiene que ser más clara… no se puede saber
        // cuando estoy en uno o en otro aparato»): EN QUÉ TELÉFONO estás, grande y con su color. Tocar, renombrar.
        estoyEn = TextView(this).apply { textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(12), dp(8), dp(12), dp(8))
            contentDescription = "Este teléfono: tocá para cambiarle el nombre"
            setOnClickListener { renombrar() } }
        caja.addView(estoyEn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        caja.addView(TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#8A8F98")); text = AVISO_CLAUDE_LEE })
        // pizarra-68: sin este permiso (Android 14 o más) la llamada entrante es un aviso y no la pantalla de llamada.
        permisoLlamadas = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#B26A00")); setPadding(0, dp(6), 0, dp(2))
            text = "⚠️ Para que una llamada te aparezca en pantalla completa, tocá acá y permití «Pantalla completa»."
            setOnClickListener { Llamadas.pedirPantallaCompleta(this@CharlaActivity) } }
        caja.addView(permisoLlamadas)
        pestanas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        caja.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pestanas) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(lista) }
        caja.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        dice = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#E8D3A2")) }
        caja.addView(dice)
        // pizarra-62: escuchar los audios solos, como un handy.
        caja.addView(android.widget.CheckBox(this).apply {
            text = "Escuchar los audios solos (los del 📻 handy suenan siempre)"; textSize = 12f; isChecked = Handy.soloActivado(this@CharlaActivity)
            setOnCheckedChangeListener { _, v -> Handy.elegirSolo(this@CharlaActivity, v) }
        })
        // pizarra-75 (Mauro: «el botón de handy debe ser diferente al de envío de un audio, del texto o de un
        // archivo»): el HANDY va solo, grande y de otro color; abajo, cuatro botones iguales con su nombre.
        handy = botonPulsador()
        caja.addView(handy, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(4) })
        fun tecla(icono: String, nombre: String, que: String) = TextView(this).apply {
            text = "$icono\n$nombre"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#E8EAED"))
            contentDescription = que; maxLines = 2
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * d; setColor(Color.parseColor("#2A2E35")) }
            layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(4); topMargin = dp(6) }
        }
        val linea = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        linea.addView(botonAudio(tecla("🎙", "Audio", "Mantener apretado para grabar un mensaje de voz")))
        // pizarra-64: una foto o un documento, a la conversación elegida (o a todos).
        linea.addView(tecla("📎", "Foto o archivo", "Mandar una foto o un documento").apply { setOnClickListener {
            val elegir = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf", "text/*", "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/zip"))
            try { startActivityForResult(Intent.createChooser(elegir, "Mandar"), 64) } catch (e: Throwable) { dice.text = "No hay dónde elegir un archivo." }
        } })
        // pizarra-63: llamar a la conversación elegida (en «Todos» no hay a quién).
        llamar = tecla("📞", "Llamar", "Llamar").apply { setOnClickListener {
            if (clave.isEmpty()) { dice.text = "Elegí arriba a quién llamar."; return@setOnClickListener }
            val yo = Nube(this@CharlaActivity).uid.orEmpty()
            val cs = conversaciones(with(Charla) { mensajesGuardados }, yo, nombreDelTelefono(this@CharlaActivity))
            val nombre = cs.firstOrNull { it.clave == clave }?.let { etiquetaConversacion(it, cs, yo).removePrefix("📱 ") }.orEmpty()
            startActivity(Intent(this@CharlaActivity, LlamadaActivity::class.java).putExtra("para", clave).putExtra("nombre", nombre))
        } }
        linea.addView(llamar)
        // pizarra-67 (Mauro: «cuando se toca para escribir, una ventana flotante bien arriba… con un pequeño botón
        // que sea el enviar»): acá no se escribe. Tocar abre EscribirActivity en la mitad de ARRIBA de la
        // pantalla, lejos del teclado: ningún Android lo puede tapar ahí.
        campo = tecla("✎", "Escribir", "Escribir un mensaje").apply { setOnClickListener { escribir() } }
        linea.addView(campo)
        caja.addView(linea)
        // pizarra-66 (Mauro: «el teclado sigue tapando el texto que se escribe»): desde Android 15 (targetSdk 35)
        // adjustResize ya no achica la ventana: el teclado se dibuja ENCIMA y la app tiene que correrse sola. Se
        // mide cuánto tapa el teclado (`ime`) y ese alto va de relleno abajo: la línea sube y la lista se achica.
        caja.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(dp(14), dp(12), dp(14), dp(8) + tapaAbajo(ins))
            ins
        }
        setContentView(caja)
        // Alto fijo de ventana (no «lo que mida»): así, con adjustResize, el teclado achica la LISTA y la línea de abajo queda a la vista.
        window.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.heightPixels * 0.82).toInt())
        @Suppress("DEPRECATION") window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        pintar()
        intent.getStringExtra("abrirArchivo")?.takeIf { it.isNotEmpty() }?.let { id ->
            dice.text = "Abriendo…"
            Archivos.abrir(this, id, intent.getIntExtra("partes", 1), intent.getStringExtra("nombreArchivo").orEmpty()) { t -> runOnUiThread { dice.text = t } }
        }
        intent.getStringExtra("reproducir")?.takeIf { it.isNotEmpty() }?.let { id ->
            dice.text = "▶ Escuchando…"; Handy.escuchar(this, id, intent.getIntExtra("dur", 30)) { t -> runOnUiThread { dice.text = t } }
        }
        thread { Charla.ponerAlDia(this); runOnUiThread { pintar() } }
        // pizarra-75: al abrir la charla se busca a los de cerca, así el handy sabe enseguida si va directo.
        if (EnlaceAjustes.prendido(this)) EnlaceServicio.buscarYa(this)
        reloj.postDelayed(object : Runnable { override fun run() { if (!isFinishing) { if (Pulsador.modo.isEmpty()) pintarHandy(); reloj.postDelayed(this, 2_000) } } }, 2_000)
    }

    private val reloj = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onDestroy() { reloj.removeCallbacksAndMessages(null); if (Pulsador.modo.isNotEmpty()) Pulsador.soltar(this, true) { }; super.onDestroy() }

    /** Abre la ventana de escribir, arriba, para la conversación elegida. */
    private fun escribir() {
        val yo = Nube(this).uid.orEmpty()
        val cs = conversaciones(with(Charla) { mensajesGuardados }, yo, nombreDelTelefono(this))
        val a = cs.firstOrNull { it.clave == clave }?.let { etiquetaConversacion(it, cs, yo).removePrefix("📱 ") } ?: "todos"
        startActivity(Intent(this, EscribirActivity::class.java).putExtra("clave", clave).putExtra("a", a))
    }

    override fun onResume() {
        super.onResume()
        if (::permisoLlamadas.isInitialized) permisoLlamadas.visibility = if (Llamadas.faltaPantallaCompleta(this)) android.view.View.VISIBLE else android.view.View.GONE
        if (::lista.isInitialized) pintar()
    }

    /** Cuánto tapa abajo el teclado (o la barra del sistema), en esta ventana. */
    @Suppress("DEPRECATION")
    private fun tapaAbajo(ins: android.view.WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) ins.getInsets(android.view.WindowInsets.Type.ime() or android.view.WindowInsets.Type.systemBars()).bottom
        else ins.systemWindowInsetBottom

    @Deprecated("startActivityForResult de siempre: la app no tiene androidx")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(codigo, resultado, datos)
        val uri = datos?.data
        if (codigo != 64 || resultado != RESULT_OK || uri == null) return
        dice.text = "Mandando…"
        Archivos.mandar(this, uri, clave) { err -> runOnUiThread { dice.text = err ?: "✓ Mandado."; pintar() } }
    }

    /** 🎙 mantener apretado: graba mientras se aprieta y al soltar lo manda a la pestaña elegida (pizarra-62). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun botonAudio(t: TextView): TextView {
        var g: Grabador? = null
        return t.apply {
            setOnTouchListener { v, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        if (!Handy.tienePermiso(this@CharlaActivity)) {
                            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 62)
                            dice.text = "Dale permiso al micrófono y volvé a mantener apretado."
                        } else try {
                            g = Grabador(this@CharlaActivity).also { it.empezar { } }
                            dice.text = "🔴 Grabando un mensaje de voz… soltá para mandar"
                        } catch (x: Throwable) { dice.text = "No se pudo grabar."; g = null }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val gr = g; g = null
                        if (gr != null) {
                            val r = if (e.action == android.view.MotionEvent.ACTION_UP) gr.terminar() else { gr.cancelar(); null }
                            if (r == null) dice.text = "Muy corto: no se mandó."
                            else {
                                dice.text = "Mandando…"
                                Handy.mandar(this@CharlaActivity, r.first, r.second, gr.tipo, clave) { err ->
                                    runOnUiThread { dice.text = err ?: "✓ Audio mandado."; pintar() }
                                }
                            }
                        }
                        v.performClick(); true
                    }
                    else -> true
                }
            }
        }
    }

    /**
     * 📻 el HANDY (pizarra-75): mantener apretado y hablar. Si el otro teléfono está cerca, se oye en vivo;
     * si no, va por internet y le suena solo al llegar (`Pulsador`). El botón dice cuál de los dos va a ser.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun botonPulsador(): Button = Button(this).apply {
        textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor("#FFFFFF")); isAllCaps = false
        contentDescription = "Handy: mantener apretado para hablar"
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val yo = Nube(this@CharlaActivity).uid.orEmpty()
                    val cs = conversaciones(with(Charla) { mensajesGuardados }, yo, nombreDelTelefono(this@CharlaActivity))
                    val nombre = cs.firstOrNull { it.clave == clave }?.let { etiquetaConversacion(it, cs, yo).removePrefix("📱 ") }.orEmpty()
                    val ok = Pulsador.apretar(this@CharlaActivity, clave, nombre) { t -> runOnUiThread { dice.text = t } }
                    if (!ok) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 75); dice.text = "Dale permiso al micrófono y volvé a apretar." }
                    pintarHandy(apretado = ok)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    Pulsador.soltar(this@CharlaActivity, e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) { t -> runOnUiThread { dice.text = t; pintar() } }
                    pintarHandy(apretado = false)
                    v.performClick(); true
                }
                else -> true
            }
        }
        setOnClickListener { }
    }

    /** El botón del handy dice a quién y por dónde va a ir, ANTES de apretar. */
    private fun pintarHandy(apretado: Boolean = false) {
        val yo = Nube(this).uid.orEmpty()
        val ms = with(Charla) { mensajesGuardados }
        val cs = conversaciones(ms, yo, nombreDelTelefono(this))
        val a = cs.firstOrNull { it.clave == clave }?.let { etiquetaConversacion(it, cs, yo).removePrefix("📱 ") }
        val cerca = EnlaceAjustes.prendido(this) && destinoCerca(clave, EnlaceServicio.cercanos()) { telefonosConocidos(ms, it) }
        handy.text = when {
            apretado -> "🔴 HABLANDO… soltá para terminar"
            cerca -> "📻 HANDY con ${a ?: "todos"} · cerca, en vivo"
            a == null -> "📻 HANDY · elegí arriba a quién"
            else -> "📻 HANDY con $a · por internet"
        }
        handy.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * resources.displayMetrics.density
            setColor(Color.parseColor(if (apretado) "#C62828" else if (cerca) "#2E7D32" else "#1565C0"))
        }
    }

    /** pizarra-75: el nombre de este teléfono, el que ven los demás. */
    private fun renombrar() {
        val campoNombre = android.widget.EditText(this).apply { setText(nombreDelTelefono(this@CharlaActivity)); setSelection(text.length) }
        android.app.AlertDialog.Builder(this).setTitle("Nombre de este teléfono")
            .setMessage("Es como lo ven las otras Pizarras (por ejemplo «Note 9 · Hilux» o «Celu de Mauro»). Ojo: con otro nombre, la conversación con este teléfono empieza de nuevo para los demás.")
            .setView(campoNombre)
            .setPositiveButton("Guardar") { _, _ ->
                val n = aliasValido(campoNombre.text.toString())
                if (n == null) dice.text = "Un nombre de 2 a 40 letras, sin «|»." else { ponerAlias(this, n); Bitacora.anotar(this, "teléfono: nombre nuevo"); pintar() }
            }
            .setNeutralButton("El del sistema") { _, _ -> ponerAlias(this, null); pintar() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun pintar() {
        val yo = Nube(this).uid.orEmpty(); val tel = nombreDelTelefono(this)
        val todos = with(Charla) { mensajesGuardados }
        val cs = conversaciones(todos, yo, tel)
        val c = cs.firstOrNull { it.clave == clave }
        if (c == null) clave = ""
        val d = resources.displayMetrics.density
        // pizarra-75: cada teléfono con SU color, el mismo en la franja, las pestañas y las burbujas.
        val conocidos = todos.map { it.dispositivo }.filter { it.isNotEmpty() } + tel
        fun colorDe(disp: String, nombre: String) = Color.parseColor(colorDeTelefono(if (disp == "*") "persona $nombre" else disp, conocidos))
        val mio = colorDe(tel, "")
        estoyEn.text = "📱 Estás en: $tel  ✎"
        estoyEn.setTextColor(Color.parseColor("#14161A"))
        estoyEn.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * d; setColor(mio) }
        // Las pestañas: Todos, y cada teléfono (o persona), con su color.
        pestanas.removeAllViews()
        fun chip(texto: String, k: String, color: Int?) = TextView(this).apply {
            text = (if (color != null) "● " else "") + texto; textSize = 14f; setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            val elegida = k == clave
            setTextColor(if (elegida) Color.parseColor("#14161A") else (color ?: Color.parseColor("#E8EAED")))
            setTypeface(typeface, if (elegida) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * d
                setColor(if (elegida) (color ?: Color.parseColor("#E8B85C")) else Color.parseColor("#2A2E35"))
                if (color != null) setStroke((2 * d).toInt(), color) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (6 * d).toInt() }
            setOnClickListener { clave = k; pintar() }
        }
        pestanas.addView(chip("Todos", "", null))
        for (x in cs) pestanas.addView(chip(etiquetaConversacion(x, cs, yo), x.clave, colorDe(x.dispositivo, x.nombre)))
        pintarHandy()
        // Las burbujas: lo mío a la derecha, lo del otro a la izquierda.
        lista.removeAllViews()
        val ms = (if (c == null) todos else todos.filter { enConversacion(it, c, yo, tel) }).take(40).reversed()
        if (ms.isEmpty()) lista.addView(TextView(this).apply {
            setTextColor(Color.parseColor("#8A8F98"))
            text = if (c == null) "Todavía no hay mensajes. Un teléfono nuevo aparece como pestaña en cuanto escribe su primer mensaje." else "Todavía no se escribieron."
        })
        for (m in ms) {
            val esMio = m.de == yo && m.dispositivo == tel
            val fila = LinearLayout(this).apply { gravity = if (esMio) Gravity.END else Gravity.START; setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt()) }
            fila.addView(TextView(this).apply {
                val cuerpo = if (m.audio.isNotEmpty()) (if (m.handy) "▶ 📻 handy " else "▶ 🎙 ") + duracionTexto(m.dur)
                    else if (m.archivo.isNotEmpty()) m.texto + "\n(tocá para abrir)" else m.texto
                text = (if (esMio && c != null) "" else firmaMensaje(m, yo, tel) + "\n") + cuerpo + "\n" + horaCorta(m.creado)
                if (m.archivo.isNotEmpty()) setOnClickListener {
                    dice.text = "Abriendo…"; Archivos.abrir(this@CharlaActivity, m.archivo, m.partes, m.nombreArchivo) { t -> runOnUiThread { dice.text = t } }
                }
                if (m.audio.isNotEmpty()) setOnClickListener {
                    dice.text = "▶ Escuchando…"; Handy.escuchar(this@CharlaActivity, m.audio, m.dur) { t -> runOnUiThread { dice.text = t } }
                }
                textSize = 14f; setTextColor(Color.parseColor(if (esMio) "#14161A" else "#F2F2F2"))
                maxWidth = (resources.displayMetrics.widthPixels * 0.7).toInt()
                setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
                // pizarra-75: lo mío, del color de este teléfono; lo del otro, con un borde del color del suyo.
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * d
                    if (esMio) setColor(mio) else { setColor(Color.parseColor("#2F333A")); setStroke((3 * d).toInt(), colorDe(m.dispositivo, m.nombre)) } }
            })
            lista.addView(fila)
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}

/** pizarra-62 (Mauro: «quiero que la IA pueda auditar las comunicaciones… siempre se incluirá la IA»): quien escribe o habla lo sabe antes. */
const val AVISO_CLAUDE_LEE = "🤖 Claude lee y escucha esta charla para convertir propuestas en trabajo."

/** La ventana de escribir, en la mitad de ARRIBA de la pantalla (pizarra-67): el texto grande, ✕ y ➤. */
class EscribirActivity : Activity() {
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val clave = intent.getStringExtra("clave").orEmpty()
        val d = resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        val arriba = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        arriba.addView(TextView(this).apply { text = "✎ Para " + intent.getStringExtra("a").orEmpty().ifBlank { "todos" }; textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        arriba.addView(TextView(this).apply { text = "✕"; textSize = 20f; gravity = Gravity.CENTER; contentDescription = "Cerrar"
            setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(44), dp(44)))
        caja.addView(arriba)
        val texto = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 17f; gravity = Gravity.TOP or Gravity.START; hint = "Escribí acá"
        }
        caja.addView(texto, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val abajo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dice = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#8A8F98")) }
        abajo.addView(dice, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val mandar = Button(this).apply { text = "➤"; textSize = 20f; contentDescription = "Mandar"
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0) }
        abajo.addView(mandar, LinearLayout.LayoutParams(dp(52), dp(48)))
        caja.addView(abajo)
        mandar.setOnClickListener {
            val t = texto.text.toString().trim()
            if (t.isEmpty()) { dice.text = "Escribí algo."; return@setOnClickListener }
            mandar.isEnabled = false; dice.text = "Mandando…"
            thread {
                val error = try { Charla.mandar(this, t, clave); null } catch (e: Throwable) { Bitacora.anotar(this, "mensajes: no salió: " + (e.message ?: e.javaClass.simpleName)); e.message ?: "no se pudo" }
                runOnUiThread { if (error == null) finish() else { mandar.isEnabled = true; dice.text = "No salió: $error" } }
            }
        }
        setContentView(caja)
        // Arriba de todo y media pantalla de alto: el teclado sale abajo y no tapa nada.
        window.setGravity(Gravity.TOP)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.45).toInt())
        @Suppress("DEPRECATION") window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        texto.requestFocus()
    }
}

const val ACCION_PESTANA = "com.maurogasta.pizarra.MENSAJES_PESTANA"
private val PESTANAS = intArrayOf(R.id.pestana_0, R.id.pestana_1, R.id.pestana_2, R.id.pestana_3)

/** El widget: «Todo» (mensajes, avisos de Claude y Casa Verde, por fecha) y una pestaña por conversación (pizarra-61). */
class MensajesWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) {
        dibujar(c)
        val p = goAsync(); thread { try { Charla.ponerAlDia(c) } finally { p.finish() } }
        Charla.agendar(c)
    }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        when (intent.action) {
            ACCION_MENSAJES -> { val p = goAsync(); thread { try { Charla.ponerAlDia(c) } finally { p.finish() } } }
            ACCION_PESTANA -> {
                val w = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (w != -1) { Charla.elegirPestana(c, w, intent.getStringExtra("clave").orEmpty()); dibujar(c) }
            }
        }
    }

    companion object {
        fun dibujar(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, MensajesWidget::class.java))
            if (ids.isEmpty()) return
            val yo = Nube(c).uid.orEmpty(); val tel = nombreDelTelefono(c)
            val cs = conversaciones(with(Charla) { c.mensajesGuardados }, yo, tel)
            for (id in ids) {
                val v = RemoteViews(c.packageName, R.layout.widget_mensajes)
                val elegida = Charla.pestana(c, id).takeIf { k -> cs.any { it.clave == k } }.orEmpty()
                val enPestana = pestanasDelWidget(cs, elegida)
                // Las pestañas: Todo y hasta tres conversaciones; la elegida, resaltada.
                val textos = listOf("Todo" to "") + enPestana.map { etiquetaConversacion(it, cs, yo) to it.clave }
                PESTANAS.forEachIndexed { i, r ->
                    val t = textos.getOrNull(i)
                    if (t == null) { v.setViewVisibility(r, android.view.View.GONE); return@forEachIndexed }
                    v.setViewVisibility(r, android.view.View.VISIBLE)
                    v.setTextViewText(r, t.first)
                    v.setTextColor(r, Color.parseColor(if (t.second == elegida) "#1B1D21" else "#E8EAED"))
                    v.setInt(r, "setBackgroundResource", if (t.second == elegida) R.drawable.pestana_elegida else R.drawable.pestana)
                    v.setOnClickPendingIntent(r, PendingIntent.getBroadcast(c, id * 10 + i,
                        Intent(c, MensajesWidget::class.java).setAction(ACCION_PESTANA).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("clave", t.second),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
                val escribir = PendingIntent.getActivity(c, 4200 + id, Intent(c, CharlaActivity::class.java).putExtra("clave", elegida)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val enTodo = elegida.isEmpty()
                v.setViewVisibility(R.id.lista_mensajes, if (enTodo) android.view.View.VISIBLE else android.view.View.GONE)
                v.setViewVisibility(R.id.lista_charla, if (enTodo) android.view.View.GONE else android.view.View.VISIBLE)
                if (enTodo) {
                    val lista = Intent(c, MensajesServicio::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    lista.data = Uri.parse(lista.toUri(Intent.URI_INTENT_SCHEME))
                    v.setRemoteAdapter(R.id.lista_mensajes, lista)
                    v.setEmptyView(R.id.lista_mensajes, R.id.vacia_mensajes)
                } else {
                    v.setViewVisibility(R.id.vacia_mensajes, android.view.View.GONE)
                    val lista = Intent(c, BurbujasServicio::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("clave", elegida)
                    lista.data = Uri.parse(lista.toUri(Intent.URI_INTENT_SCHEME))
                    v.setRemoteAdapter(R.id.lista_charla, lista)
                    v.setPendingIntentTemplate(R.id.lista_charla, PendingIntent.getActivity(c, 4500 + id,
                        Intent(c, CharlaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
                }
                v.setTextViewText(R.id.vacia_mensajes, if (Nube(c).conSesion) "Nada todavía. Tocá ✎ para escribir." else "Tocá y entrá con tu cuenta de Tiempos.")
                v.setTextViewText(R.id.estado_mensajes, with(Charla) { c.estadoCharla })
                v.setOnClickPendingIntent(R.id.escribir_mensaje, escribir)
                // pizarra-63: 📞 sólo con una conversación elegida.
                v.setViewVisibility(R.id.llamar_mensaje, if (elegida.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
                if (elegida.isNotEmpty()) v.setOnClickPendingIntent(R.id.llamar_mensaje, PendingIntent.getActivity(c, 4800 + id,
                    Intent(c, LlamadaActivity::class.java).putExtra("para", elegida)
                        .putExtra("nombre", cs.firstOrNull { it.clave == elegida }?.let { etiquetaConversacion(it, cs, yo).removePrefix("📱 ") }.orEmpty())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                v.setOnClickPendingIntent(R.id.grabar_mensaje, PendingIntent.getActivity(c, 4700 + id, Intent(c, HandyActivity::class.java)
                    .putExtra("clave", elegida).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                v.setOnClickPendingIntent(R.id.titulo_mensajes, escribir)
                v.setOnClickPendingIntent(R.id.vacia_mensajes, escribir)
                v.setOnClickPendingIntent(R.id.actualizar_mensajes, PendingIntent.getBroadcast(c, 43,
                    Intent(c, MensajesWidget::class.java).setAction(ACCION_MENSAJES), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                v.setPendingIntentTemplate(R.id.lista_mensajes, PendingIntent.getActivity(c, 44,
                    Intent(c, AbrirActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
                manager.updateAppWidget(id, v)
                manager.notifyAppWidgetViewDataChanged(id, if (enTodo) R.id.lista_mensajes else R.id.lista_charla)
            }
        }
    }
}

class MensajesServicio : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = FilasMensajes(applicationContext)
}

class FilasMensajes(private val c: Context) : RemoteViewsService.RemoteViewsFactory {
    private var filas: List<Novedad> = emptyList()
    override fun onCreate() {}
    override fun onDataSetChanged() { filas = with(Charla) { c.novedades } }
    override fun onDestroy() {}
    override fun getCount() = filas.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(posicion: Int) = posicion.toLong()
    override fun hasStableIds() = false
    override fun getViewAt(posicion: Int): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_mensaje_fila)
        val f = filas.getOrNull(posicion) ?: return v
        v.setTextViewText(R.id.mensaje_titulo, f.titulo)
        v.setTextViewText(R.id.mensaje_hora, horaCorta(f.cuando))
        v.setTextViewText(R.id.mensaje_texto, f.texto)
        v.setTextColor(R.id.mensaje_titulo, Color.parseColor(when (f.tipo) { "mensaje" -> if (f.mio) "#AEB3BA" else "#E8B85C"; "casaverde" -> "#7FB98A"; else -> "#7FB4BF" }))
        v.setOnClickFillInIntent(R.id.mensaje_fila, if (f.tipo == "mensaje") Intent().putExtra("que", "charla")
            else Intent().putExtra("url", f.url).putExtra("aviso", f.aviso))
        return v
    }
}

/** Las burbujas de UNA conversación en el widget: lo mío a la derecha, lo del otro a la izquierda (pizarra-61). */
class BurbujasServicio : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Burbujas(applicationContext, intent.getStringExtra("clave").orEmpty())
}

class Burbujas(private val c: Context, private val clave: String) : RemoteViewsService.RemoteViewsFactory {
    private var filas: List<Mensaje> = emptyList()
    private var yo = ""; private var tel = ""
    override fun onCreate() {}
    override fun onDataSetChanged() {
        yo = Nube(c).uid.orEmpty(); tel = nombreDelTelefono(c)
        val ms = with(Charla) { c.mensajesGuardados }
        val conv = conversaciones(ms, yo, tel).firstOrNull { it.clave == clave }
        filas = if (conv == null) emptyList() else ms.filter { enConversacion(it, conv, yo, tel) }.take(40).reversed()
    }
    override fun onDestroy() {}
    override fun getCount() = filas.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 2
    override fun getItemId(posicion: Int) = posicion.toLong()
    override fun hasStableIds() = false
    override fun getViewAt(posicion: Int): RemoteViews {
        val m = filas.getOrNull(posicion) ?: return RemoteViews(c.packageName, R.layout.widget_burbuja_otro)
        val mio = m.de == yo && m.dispositivo == tel
        val v = RemoteViews(c.packageName, if (mio) R.layout.widget_burbuja_mia else R.layout.widget_burbuja_otro)
        v.setTextViewText(R.id.burbuja_texto, if (m.audio.isNotEmpty()) "▶ 🎙 " + duracionTexto(m.dur) + " · tocá para escuchar"
            else if (m.archivo.isNotEmpty()) m.texto + " · tocá para abrir" else m.texto)
        v.setTextViewText(R.id.burbuja_hora, (if (mio) "" else firmaMensaje(m, yo, tel).substringBefore(" → ") + " · ") + horaCorta(m.creado))
        v.setOnClickFillInIntent(R.id.burbuja, Intent().putExtra("clave", clave).putExtra("reproducir", m.audio).putExtra("dur", m.dur)
            .putExtra("abrirArchivo", m.archivo).putExtra("partes", m.partes).putExtra("nombreArchivo", m.nombreArchivo))
        return v
    }
}
