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
// ecosistema en un widget. Sello: pizarra-61
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
                   val para: String = "")

/** La dirección de un teléfono: «<uid>|<nombre del teléfono>». Es lo que va en `para`. */
fun claveDe(uid: String, dispositivo: String) = "$uid|$dispositivo"

/** Una conversación con UN teléfono (pizarra-61). */
data class Conversacion(val clave: String, val uid: String, val dispositivo: String, val nombre: String, val ultima: String)

/** Una línea del widget: un mensaje, un aviso de Claude o lo que espera en Casa Verde. */
data class Novedad(val id: String, val cuando: String, val titulo: String, val texto: String, val tipo: String, val mio: Boolean = false,
                   val url: String = "", val aviso: String = "")

/** Los últimos mensajes, del más nuevo al más viejo. Sin filtro: no pide índice compuesto. */
fun consultaMensajes(cuantos: Int = 150): JSONObject = JSONObject().put("structuredQuery", JSONObject()
    .put("from", JSONArray().put(JSONObject().put("collectionId", COLECCION_MENSAJES)))
    .put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "creadoEn")).put("direction", "DESCENDING")))
    .put("limit", cuantos))

/** Lo que contesta la base, con desconfianza: sin texto o sin quién, no entra. */
fun mensajesDeConsulta(r: JSONArray): List<Mensaje> = (0 until r.length()).mapNotNull { i ->
    val d = r.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
    val f = d.optJSONObject("fields") ?: return@mapNotNull null
    fun s(k: String) = f.optJSONObject(k)?.let { it.optString("stringValue", "").ifEmpty { it.optString("timestampValue", "") } }.orEmpty()
    val id = d.optString("name").substringAfterLast('/')
    if (id.isEmpty() || s("texto").isBlank() || s("de").isEmpty()) null
    else Mensaje(id, s("de"), s("nombre").take(40), s("dispositivo").take(60), s("texto").take(2000), s("creadoEn"), s("para").take(140))
}.sortedByDescending { it.creado }

/** El mensaje nuevo, con la forma EXACTA que deja crear la regla de Tiempos: la v15 sin `para`, la v17 con él. */
fun cuerpoMensaje(docs: String, id: String, uid: String, nombre: String, dispositivo: String, texto: String, para: String = ""): JSONObject {
    fun s(v: String) = JSONObject().put("stringValue", v)
    val campos = JSONObject().put("de", s(uid)).put("nombre", s(nombre.take(40))).put("dispositivo", s(dispositivo.take(60)))
        .put("texto", s(texto.trim().take(2000)))
    if (para.isNotBlank()) campos.put("para", s(para.take(140)))
    return JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_MENSAJES/$id").put("fields", campos))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))
}

/** Quién y desde dónde: «Florencia · Redmi 13C»; lo tuyo, «Yo», y el teléfono sólo si es OTRO. */
fun firmaMensaje(m: Mensaje, yo: String, miTelefono: String): String {
    val quien = if (m.de == yo) "Yo" else m.nombre.ifBlank { "Alguien" }
    val tel = m.dispositivo.ifBlank { "" }
    val base = if (tel.isEmpty() || (m.de == yo && tel == miTelefono)) quien else "$quien · $tel"
    if (m.para.isEmpty()) return base
    val (u, d) = m.para.substringBefore('|') to m.para.substringAfter('|')
    val a = if (u == yo && d == miTelefono) "este teléfono" else d
    return "$base → $a"
}

/** ¿Es para mí? Para todos, para este teléfono, o de otra persona a cualquiera de mis teléfonos. */
fun esParaMi(m: Mensaje, yo: String, miTelefono: String): Boolean =
    m.para.isEmpty() || m.para == claveDe(yo, miTelefono) || (m.para.startsWith("$yo|") && m.de != yo)

/** ¿Este mensaje es de la conversación con ese teléfono, vista desde este? */
fun enConversacion(m: Mensaje, c: Conversacion, yo: String, miTelefono: String): Boolean =
    if (c.uid != yo) (m.de == c.uid && m.dispositivo == c.dispositivo && m.para.startsWith("$yo|")) || (m.de == yo && m.para == c.clave)
    else (m.de == yo && m.dispositivo == c.dispositivo && m.para == claveDe(yo, miTelefono)) ||
        (m.de == yo && m.dispositivo == miTelefono && m.para == c.clave)

/** Los teléfonos con los que se puede hablar, la conversación más reciente primero. Este teléfono no. */
fun conversaciones(ms: List<Mensaje>, yo: String, miTelefono: String): List<Conversacion> {
    val nombres = mutableMapOf<String, String>()
    val claves = LinkedHashSet<String>()
    for (m in ms) {
        if (m.dispositivo.isBlank()) continue
        val k = claveDe(m.de, m.dispositivo)
        if (m.nombre.isNotBlank()) nombres.putIfAbsent(m.de, m.nombre)
        if (k != claveDe(yo, miTelefono)) claves.add(k)
        if (m.para.contains('|') && m.para != claveDe(yo, miTelefono)) claves.add(m.para)
    }
    return claves.map { k ->
        val u = k.substringBefore('|'); val d = k.substringAfter('|')
        val c0 = Conversacion(k, u, d, if (u == yo) "Yo" else nombres[u] ?: "Alguien", "")
        c0.copy(ultima = ms.filter { enConversacion(it, c0, yo, miTelefono) }.maxOfOrNull { it.creado }
            ?: ms.filter { claveDe(it.de, it.dispositivo) == k }.maxOfOrNull { it.creado }.orEmpty())
    }.sortedByDescending { it.ultima }
}

/** El nombre de la pestaña: la persona; con el teléfono si tiene más de uno; mis otros teléfonos, por su nombre. */
fun etiquetaConversacion(c: Conversacion, todas: List<Conversacion>, yo: String): String =
    if (c.uid == yo) "📱 " + c.dispositivo.take(16)
    else if (todas.count { it.uid == c.uid } > 1) "${c.nombre.take(10)} · ${c.dispositivo.take(10)}"
    else c.nombre.take(16)

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
    for (m in mensajes) l.add(Novedad("m:" + m.id, m.creado, "💬 " + firmaMensaje(m, yo, miTelefono), m.texto, "mensaje", m.de == yo))
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
    val n = try { android.provider.Settings.Global.getString(c.contentResolver, android.provider.Settings.Global.DEVICE_NAME) } catch (e: Throwable) { null }
    return (n?.trim()?.takeIf { it.isNotEmpty() } ?: "${Build.MANUFACTURER} ${Build.MODEL}").take(60)
}

private val HORA_CORTA = DateTimeFormatter.ofPattern("dd/MM HH:mm")
fun horaCorta(iso: String): String = instante(iso)?.let { HORA_CORTA.format(java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: ""

object Charla {
    private fun prefs(c: Context) = c.getSharedPreferences("charla", Context.MODE_PRIVATE)

    var Context.mensajesGuardados: List<Mensaje>
        get() = try { JSONArray(prefs(this).getString("lista", "[]")).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Mensaje(it.optString("id"), it.optString("de"), it.optString("nombre"), it.optString("dispositivo"), it.optString("texto"), it.optString("creado"),
                it.optString("para")) } } } }
            catch (e: Exception) { emptyList() }
        set(v) = prefs(this).edit().putString("lista", JSONArray().apply { v.take(150).forEach { put(JSONObject().put("id", it.id).put("de", it.de)
            .put("nombre", it.nombre).put("dispositivo", it.dispositivo).put("texto", it.texto).put("creado", it.creado).put("para", it.para)) } }.toString()).apply()

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
            val ms = nube.mensajes()
            c.mensajesGuardados = ms
            val p = prefs(c)
            val vistos = p.getStringSet("mostrados", emptySet()) ?: emptySet()
            val nuevos = mensajesParaAvisar(ms, yo, tel, vistos, System.currentTimeMillis())
            nuevos.forEach { avisar(c, it, yo, tel) }
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
            // pizarra-59: cada 10 minutos se pone al día TODO, no sólo los mensajes (Despertar.kt).
            try { if (enHorario(LocalDateTime.now())) Despertar.todo(c) } catch (e: Throwable) { }
            finally { try { Charla.agendar(c) } catch (e: Throwable) { }; pendiente.finish() }
        }
    }
}

/** La charla: pestañas (Todos y una por teléfono), las burbujas y el campo para escribir (pizarra-61). */
class CharlaActivity : Activity() {
    private lateinit var lista: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pestanas: LinearLayout
    private lateinit var dice: TextView
    private lateinit var campo: EditText
    private var clave = ""

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val n = Nube(this)
        if (!n.conSesion || !n.esTiempos) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); return
        }
        clave = intent.getStringExtra("clave").orEmpty()
        val d = resources.displayMetrics.density
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (10 * d).toInt()) }
        caja.addView(TextView(this).apply { text = "💬 Mensajes"; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        caja.addView(TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#8A8F98")); text = "Desde este teléfono: ${nombreDelTelefono(this@CharlaActivity)}" })
        pestanas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        caja.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pestanas) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(lista) }
        caja.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.45).toInt()))
        campo = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE; maxLines = 4 }
        caja.addView(campo)
        dice = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#8A8F98")) }
        caja.addView(dice)
        val botones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        botones.addView(Button(this).apply { text = "Cerrar"; setOnClickListener { finish() } })
        val mandar = Button(this).apply { text = "Mandar" }
        mandar.setOnClickListener {
            val t = campo.text.toString().trim()
            if (t.isEmpty()) { dice.text = "Escribí algo."; return@setOnClickListener }
            mandar.isEnabled = false; dice.text = "Mandando…"
            val para = clave
            thread {
                val error = try { Charla.mandar(this, t, para); null } catch (e: Throwable) { Bitacora.anotar(this, "mensajes: no salió: " + (e.message ?: e.javaClass.simpleName)); e.message ?: "no se pudo" }
                runOnUiThread {
                    mandar.isEnabled = true
                    if (error == null) { campo.setText(""); dice.text = "✓ Mandado."; pintar() } else dice.text = "No salió: $error"
                }
            }
        }
        botones.addView(mandar)
        caja.addView(botones)
        setContentView(caja)
        window.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        pintar()
        thread { Charla.ponerAlDia(this); runOnUiThread { pintar() } }
    }

    private fun pintar() {
        val yo = Nube(this).uid.orEmpty(); val tel = nombreDelTelefono(this)
        val todos = with(Charla) { mensajesGuardados }
        val cs = conversaciones(todos, yo, tel)
        val c = cs.firstOrNull { it.clave == clave }
        if (c == null) clave = ""
        // Las pestañas: Todos, y cada teléfono.
        val d = resources.displayMetrics.density
        pestanas.removeAllViews()
        fun chip(texto: String, k: String) = TextView(this).apply {
            text = texto; textSize = 13f; setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            val elegida = k == clave
            setTextColor(Color.parseColor(if (elegida) "#1B1D21" else "#E8EAED"))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * d; setColor(Color.parseColor(if (elegida) "#E8B85C" else "#3A3F47")) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (6 * d).toInt() }
            setOnClickListener { clave = k; pintar() }
        }
        pestanas.addView(chip("Todos", ""))
        for (x in cs) pestanas.addView(chip(etiquetaConversacion(x, cs, yo), x.clave))
        campo.hint = if (c == null) "Escribile a todos" else "Escribile a " + etiquetaConversacion(c, cs, yo).removePrefix("📱 ")
        // Las burbujas: lo mío a la derecha, lo del otro a la izquierda.
        lista.removeAllViews()
        val ms = (if (c == null) todos else todos.filter { enConversacion(it, c, yo, tel) }).take(40).reversed()
        if (ms.isEmpty()) lista.addView(TextView(this).apply {
            setTextColor(Color.parseColor("#8A8F98"))
            text = if (c == null) "Todavía no hay mensajes. Un teléfono nuevo aparece como pestaña en cuanto escribe su primer mensaje." else "Todavía no se escribieron."
        })
        for (m in ms) {
            val mio = m.de == yo && m.dispositivo == tel
            val fila = LinearLayout(this).apply { gravity = if (mio) Gravity.END else Gravity.START; setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt()) }
            fila.addView(TextView(this).apply {
                text = (if (mio && c != null) "" else firmaMensaje(m, yo, tel) + "\n") + m.texto + "\n" + horaCorta(m.creado)
                textSize = 14f; setTextColor(Color.parseColor(if (mio) "#1B1D21" else "#F2F2F2"))
                maxWidth = (resources.displayMetrics.widthPixels * 0.7).toInt()
                setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * d; setColor(Color.parseColor(if (mio) "#E8B85C" else "#3A3F47")) }
            })
            lista.addView(fila)
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
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
        v.setTextViewText(R.id.burbuja_texto, m.texto)
        v.setTextViewText(R.id.burbuja_hora, (if (mio) "" else firmaMensaje(m, yo, tel).substringBefore(" → ") + " · ") + horaCorta(m.creado))
        v.setOnClickFillInIntent(R.id.burbuja, Intent().putExtra("clave", clave))
        return v
    }
}
