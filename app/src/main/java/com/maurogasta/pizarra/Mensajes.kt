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
// ecosistema en un widget. Sello: pizarra-58
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

data class Mensaje(val id: String, val de: String, val nombre: String, val dispositivo: String, val texto: String, val creado: String)

/** Una línea del widget: un mensaje, un aviso de Claude o lo que espera en Casa Verde. */
data class Novedad(val id: String, val cuando: String, val titulo: String, val texto: String, val tipo: String, val mio: Boolean = false,
                   val url: String = "", val aviso: String = "")

/** Los últimos mensajes, del más nuevo al más viejo. Sin filtro: no pide índice compuesto. */
fun consultaMensajes(cuantos: Int = 60): JSONObject = JSONObject().put("structuredQuery", JSONObject()
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
    else Mensaje(id, s("de"), s("nombre").take(40), s("dispositivo").take(60), s("texto").take(2000), s("creadoEn"))
}.sortedByDescending { it.creado }

/** El mensaje nuevo, con la forma EXACTA que deja crear la regla v15 de Tiempos. */
fun cuerpoMensaje(docs: String, id: String, uid: String, nombre: String, dispositivo: String, texto: String): JSONObject {
    fun s(v: String) = JSONObject().put("stringValue", v)
    return JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_MENSAJES/$id").put("fields", JSONObject()
            .put("de", s(uid)).put("nombre", s(nombre.take(40))).put("dispositivo", s(dispositivo.take(60)))
            .put("texto", s(texto.trim().take(2000)))))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))
}

/** Quién y desde dónde: «Florencia · Redmi 13C»; lo tuyo, «Yo», y el teléfono sólo si es OTRO. */
fun firmaMensaje(m: Mensaje, yo: String, miTelefono: String): String {
    val quien = if (m.de == yo) "Yo" else m.nombre.ifBlank { "Alguien" }
    val tel = m.dispositivo.ifBlank { "" }
    return if (tel.isEmpty() || (m.de == yo && tel == miTelefono)) quien else "$quien · $tel"
}

/** Lo que avisa: del otro, o mío desde otro teléfono; que este teléfono no mostró; de las últimas 24 h. */
fun mensajesParaAvisar(l: List<Mensaje>, yo: String, miTelefono: String, mostrados: Set<String>, ahora: Long): List<Mensaje> = l.filter { m ->
    m.id !in mostrados && (m.de != yo || m.dispositivo != miTelefono) &&
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
            Mensaje(it.optString("id"), it.optString("de"), it.optString("nombre"), it.optString("dispositivo"), it.optString("texto"), it.optString("creado")) } } } }
            catch (e: Exception) { emptyList() }
        set(v) = prefs(this).edit().putString("lista", JSONArray().apply { v.take(60).forEach { put(JSONObject().put("id", it.id).put("de", it.de)
            .put("nombre", it.nombre).put("dispositivo", it.dispositivo).put("texto", it.texto).put("creado", it.creado)) } }.toString()).apply()

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

    fun mandar(c: Context, texto: String) {
        val nube = Nube(c)
        nube.mandarMensaje(texto.trim(), nombreDelTelefono(c))
        ponerAlDia(c)
    }

    private fun avisar(c: Context, m: Mensaje, yo: String, tel: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CANAL_MENSAJES, "Mensajes", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Lo que te escriben desde otra Pizarra." })
        val abrir = PendingIntent.getActivity(c, 41, Intent(c, CharlaActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
            try { if (enHorario(LocalDateTime.now())) Charla.ponerAlDia(c) } catch (e: Throwable) { }
            finally { try { Charla.agendar(c) } catch (e: Throwable) { }; pendiente.finish() }
        }
    }
}

/** La charla: los últimos mensajes y el campo para escribir. También recibe los toques del widget. */
class CharlaActivity : Activity() {
    private lateinit var lista: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var dice: TextView

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val n = Nube(this)
        if (!n.conSesion || !n.esTiempos) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); return
        }
        val d = resources.displayMetrics.density
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (10 * d).toInt()) }
        caja.addView(TextView(this).apply { text = "💬 Mensajes"; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        caja.addView(TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#8A8F98")); text = "Desde este teléfono: ${nombreDelTelefono(this@CharlaActivity)}" })
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(lista) }
        caja.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.45).toInt()))
        val campo = EditText(this).apply { hint = "Escribí un mensaje"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE; maxLines = 4 }
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
            thread {
                val error = try { Charla.mandar(this, t); null } catch (e: Throwable) { Bitacora.anotar(this, "mensajes: no salió: " + (e.message ?: e.javaClass.simpleName)); e.message ?: "no se pudo" }
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
        lista.removeAllViews()
        val ms = with(Charla) { mensajesGuardados }.take(30).reversed()
        if (ms.isEmpty()) lista.addView(TextView(this).apply { text = "Todavía no hay mensajes."; setTextColor(Color.parseColor("#8A8F98")) })
        for (m in ms) lista.addView(TextView(this).apply {
            text = "${firmaMensaje(m, yo, tel)} · ${horaCorta(m.creado)}\n${m.texto}"
            textSize = 14f; setPadding(0, 8, 0, 8)
            gravity = if (m.de == yo) Gravity.END else Gravity.START
        })
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}

/** El widget: mensajes, avisos de Claude y lo que espera en Casa Verde, por fecha. */
class MensajesWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) {
        dibujar(c)
        val p = goAsync(); thread { try { Charla.ponerAlDia(c) } finally { p.finish() } }
        Charla.agendar(c)
    }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        if (intent.action == ACCION_MENSAJES) {
            val p = goAsync(); thread { try { Charla.ponerAlDia(c) } finally { p.finish() } }
        }
    }

    companion object {
        fun dibujar(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, MensajesWidget::class.java))
            if (ids.isEmpty()) return
            val escribir = PendingIntent.getActivity(c, 42, Intent(c, CharlaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            for (id in ids) {
                val v = RemoteViews(c.packageName, R.layout.widget_mensajes)
                val lista = Intent(c, MensajesServicio::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                lista.data = Uri.parse(lista.toUri(Intent.URI_INTENT_SCHEME))
                v.setRemoteAdapter(R.id.lista_mensajes, lista)
                v.setEmptyView(R.id.lista_mensajes, R.id.vacia_mensajes)
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
                manager.notifyAppWidgetViewDataChanged(id, R.id.lista_mensajes)
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
