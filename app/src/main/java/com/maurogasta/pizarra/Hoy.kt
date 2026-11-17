package com.maurogasta.pizarra

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Hoy.kt — El widget «Hoy» de Timón. Sello: pizarra-84
//
// Plan «Timón», etapa 5 (17-nov-2026, Mauro: «que todo diga Timón, avanzá con
// la etapa siguiente»). Es el widget del prototipo que Mauro aprobó el 16-nov,
// y cumple la regla 3 de ARQUITECTURA.md de `datos` al pie de la letra:
//
//   · LEE UN SOLO DOCUMENTO, `hoy/{uid}` de la base de Timón, que arma la app
//     web de la persona (`materializarHoy` de nucleo.js de `tiempos`). Una
//     lectura por puesta al día: el cupo.
//   · NO CALCULA NADA. No sabe qué es un ítem, no ordena, no decide qué va
//     hoy: pinta los renglones en el orden en que vienen y sólo elige el día
//     comparando fechas (lo de hoy y lo de antes, por si el documento es de
//     ayer y Timón todavía no se abrió).
//   · TILDAR escribe la acción que viaja DENTRO del renglón (`accion.campos`)
//     tal cual, y destildar escribe `accion.deshacer`: la forma de un ítem la
//     conoce Timón y nadie más. En la misma escritura saca el renglón de
//     `hoy/` (o lo devuelve), así los otros teléfonos de la misma persona no
//     lo siguen mostrando.
//   · TOCAR EL TEXTO abre Timón en ese ítem (`?solapa=inicio&abrir=<clave>`).
//
// Lo que tilda otra persona (un ítem de la familia) no rearma MI `hoy/`
// hasta que yo abra Timón: es lo que la regla 3 acepta («el renglón completo
// aparece al abrir Timón»). Por eso el encabezado dice cuándo se armó.
//
// Va EN PARALELO con el widget de las tareas fijadas (PizarraWidget.kt): la
// regla 8 no deja apagar nada hasta que lo nuevo se usó una semana.
// ─────────────────────────────────────────────────────────────────────────────

const val ACCION_HOY = "com.maurogasta.pizarra.HOY_ACTUALIZAR"
const val ACCION_HOY_TILDAR = "com.maurogasta.pizarra.HOY_TILDAR"
/** Timón en «Hoy». */
const val URL_HOY = TIEMPOS_URL + "?solapa=inicio"

/** Timón en «Hoy» con ese ítem abierto (app-43 / hoy-2 de `tiempos`). */
fun urlItem(clave: String): String =
    URL_HOY + (if (clave.isEmpty()) "" else "&abrir=" + java.net.URLEncoder.encode(clave, "UTF-8"))

/** Un renglón de `hoy/{uid}`, como lo armó Timón. `crudo` es el valor tal cual
 *  vino de la base: es lo que se saca o se devuelve a la lista al tildar. */
data class RenglonHoy(
    val dia: String, val texto: String, val espacio: String, val hora: String, val clave: String,
    val coleccion: String, val doc: String, val campos: JSONObject?, val deshacer: JSONObject?,
    val crudo: JSONObject,
)

data class DocHoy(val desde: String, val hasta: String, val armado: String, val renglones: List<RenglonHoy>)

/** Lo que el widget dibuja: un renglón y si está tildado en este teléfono. */
data class FilaHoy(val clave: String, val texto: String, val hora: String, val espacio: String, val hecho: Boolean)

private fun texto(f: JSONObject?, k: String): String = f?.optJSONObject(k)?.optString("stringValue").orEmpty()
private fun mapa(f: JSONObject?, k: String): JSONObject? = f?.optJSONObject(k)?.optJSONObject("mapValue")?.optJSONObject("fields")

/** Un nombre de colección o de documento que se puede escribir: sin barras ni
 *  rarezas. El documento lo escribe sólo su dueño, pero igual se mira. */
private val NOMBRE_SEGURO = Regex("^[A-Za-z0-9_-]{1,100}$")
private val CAMPO_SEGURO = Regex("^[A-Za-z_][A-Za-z0-9_]{0,60}$")

fun renglonDeValor(v: JSONObject): RenglonHoy? {
    val f = v.optJSONObject("mapValue")?.optJSONObject("fields") ?: return null
    val a = mapa(f, "accion")
    val r = RenglonHoy(
        dia = texto(f, "dia"), texto = texto(f, "texto"), espacio = texto(f, "espacio"),
        hora = texto(f, "hora"), clave = texto(f, "clave"),
        coleccion = texto(a, "coleccion"), doc = texto(a, "doc"),
        campos = mapa(a, "campos"), deshacer = mapa(a, "deshacer"), crudo = v,
    )
    return if (r.clave.isEmpty() || r.texto.isEmpty()) null else r
}

/** La respuesta de `batchGet` del documento `hoy/{uid}`; null si no existe todavía. */
fun hoyDeRespuesta(r: JSONArray): DocHoy? {
    val f = r.optJSONObject(0)?.optJSONObject("found")?.optJSONObject("fields") ?: return null
    val lista = f.optJSONObject("renglones")?.optJSONObject("arrayValue")?.optJSONArray("values") ?: JSONArray()
    val renglones = (0 until lista.length()).mapNotNull { lista.optJSONObject(it)?.let(::renglonDeValor) }
    return DocHoy(texto(f, "desde"), texto(f, "hasta"),
        f.optJSONObject("actualizadoEn")?.optString("timestampValue").orEmpty(), renglones)
}

/**
 * Lo que se ve hoy: los renglones de hoy y de antes (un documento de ayer
 * todavía no rearmado sigue sirviendo), en el orden de Timón; los tildados en
 * este teléfono, tachados en su lugar, y los que ya salieron del documento,
 * tachados al final (así se pueden destildar si fue un dedo de más).
 */
fun filasDeHoy(doc: DocHoy?, tildados: Map<String, RenglonHoy>, hoy: String): List<FilaHoy> {
    val delDia = (doc?.renglones ?: emptyList()).filter { it.dia.isNotEmpty() && it.dia <= hoy }
    val filas = delDia.map { FilaHoy(it.clave, it.texto, it.hora, it.espacio, it.clave in tildados) }
    val enLista = delDia.map { it.clave }.toSet()
    return filas + tildados.values.filter { it.clave !in enLista }
        .map { FilaHoy(it.clave, it.texto, it.hora, it.espacio, true) }
}

/**
 * La escritura de un toque, en UN commit: la acción del renglón sobre su
 * documento (sólo si existe: un ítem borrado no se resucita) y, en `hoy/{uid}`,
 * sacar ese renglón de la lista —o devolverlo, al destildar—. Devuelve null si
 * el renglón no trae una acción que se pueda escribir tal cual.
 */
fun cuerpoTildar(r: RenglonHoy, tildar: Boolean, uid: String): JSONObject? {
    val campos = (if (tildar) r.campos else r.deshacer) ?: return null
    if (!NOMBRE_SEGURO.matches(r.coleccion) || !NOMBRE_SEGURO.matches(r.doc) || !NOMBRE_SEGURO.matches(uid)) return null
    val mascara = JSONArray()
    for (k in campos.keys()) { if (!CAMPO_SEGURO.matches(k)) return null; mascara.put(k) }
    if (mascara.length() == 0) return null
    val accion = JSONObject()
        .put("update", JSONObject().put("name", "$BASE_DOCS/${r.coleccion}/${r.doc}").put("fields", campos))
        .put("updateMask", JSONObject().put("fieldPaths", mascara))
        .put("currentDocument", JSONObject().put("exists", true))
    val lista = JSONObject().put("fieldPath", "renglones")
        .put(if (tildar) "removeAllFromArray" else "appendMissingElements", JSONObject().put("values", JSONArray().put(r.crudo)))
    val enHoy = JSONObject()
        .put("transform", JSONObject().put("document", "$BASE_DOCS/hoy/$uid").put("fieldTransforms", JSONArray().put(lista)))
        .put("currentDocument", JSONObject().put("exists", true))
    return JSONObject().put("writes", JSONArray().put(accion).put(enHoy))
}

/** El encabezado: cuándo se puso al día y, si el documento no es de hoy, que se rearma al abrir Timón. */
fun estadoDeHoy(doc: DocHoy?, hoy: String, hora: String): String = when {
    doc == null -> "todavía no se armó: abrí Timón una vez"
    doc.desde.isNotEmpty() && doc.desde < hoy -> "al día · $hora · armado el ${doc.desde.drop(8)}/${doc.desde.drop(5).take(2)}: abrí Timón"
    else -> "al día · $hora"
}

class CacheHoy(contexto: Context) {
    private val p = contexto.getSharedPreferences("hoy", Context.MODE_PRIVATE)
    /** El documento tal cual vino (la respuesta de batchGet). */
    var respuesta: String
        get() = p.getString("respuesta", "") ?: ""
        set(v) = p.edit().putString("respuesta", v).apply()
    var estado: String
        get() = p.getString("estado", "") ?: ""
        set(v) = p.edit().putString("estado", v).apply()
    /** Lo tildado en este teléfono desde el último armado de Timón: clave → renglón crudo. */
    var tildados: Map<String, RenglonHoy>
        get() = try {
            val o = JSONObject(p.getString("tildados", "{}") ?: "{}")
            o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let(::renglonDeValor)?.let { k to it } }.toMap()
        } catch (e: Throwable) { emptyMap() }
        set(v) = p.edit().putString("tildados", JSONObject().apply { for ((k, r) in v) put(k, r.crudo) }.toString()).apply()
    /** Con qué armado de Timón valen los tildados: si Timón rearmó, él ya sabe. */
    var armadoDeLosTildados: String
        get() = p.getString("armado", "") ?: ""
        set(v) = p.edit().putString("armado", v).apply()
    val doc: DocHoy? get() = try { respuesta.takeIf { it.isNotEmpty() }?.let { hoyDeRespuesta(JSONArray(it)) } } catch (e: Throwable) { null }
}

class HoyWidget : AppWidgetProvider() {

    override fun onUpdate(contexto: Context, manager: AppWidgetManager, ids: IntArray) {
        dibujar(contexto)
        actualizar(contexto, null)
    }

    override fun onReceive(contexto: Context, intent: Intent) {
        super.onReceive(contexto, intent)
        when (intent.action) {
            ACCION_HOY -> {
                CacheHoy(contexto).estado = "Actualizando…"
                dibujar(contexto)
                actualizar(contexto, goAsync())
            }
            ACCION_HOY_TILDAR -> {
                val clave = intent.getStringExtra("clave") ?: return
                tildar(contexto, clave, intent.getBooleanExtra("tildar", true), goAsync())
            }
        }
    }

    companion object {
        fun dibujar(contexto: Context) {
            val manager = AppWidgetManager.getInstance(contexto)
            val ids = manager.getAppWidgetIds(ComponentName(contexto, HoyWidget::class.java))
            if (ids.isEmpty()) return
            val cache = CacheHoy(contexto)
            val conSesion = Nube(contexto).conSesion
            val abrir = PendingIntent.getActivity(contexto, 31, intentAbrir(contexto, conSesion, URL_HOY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            for (id in ids) {
                val v = RemoteViews(contexto.packageName, R.layout.widget_hoy)
                val lista = Intent(contexto, HoyServicio::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                lista.data = Uri.parse(lista.toUri(Intent.URI_INTENT_SCHEME))
                v.setRemoteAdapter(R.id.lista_hoy, lista)
                v.setEmptyView(R.id.lista_hoy, R.id.vacia_hoy)
                v.setTextViewText(R.id.vacia_hoy, if (conSesion) "Nada para hoy. Tocá el título para abrir Timón." else "Tocá y entrá con tu cuenta de Timón.")
                v.setTextViewText(R.id.estado_hoy, cache.estado)
                v.setOnClickPendingIntent(R.id.actualizar_hoy, PendingIntent.getBroadcast(contexto, 30,
                    Intent(contexto, HoyWidget::class.java).setAction(ACCION_HOY),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                v.setOnClickPendingIntent(R.id.titulo_hoy, abrir)
                v.setOnClickPendingIntent(R.id.vacia_hoy, abrir)
                // Una sola plantilla para todos los renglones (MUTABLE: el renglón
                // le agrega qué se tocó). Va a ToqueActivity, que tilda o abre.
                v.setPendingIntentTemplate(R.id.lista_hoy, PendingIntent.getActivity(contexto, 32,
                    Intent(contexto, ToqueActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
                manager.updateAppWidget(id, v)
                manager.notifyAppWidgetViewDataChanged(id, R.id.lista_hoy)
            }
        }

        fun intentAbrir(contexto: Context, conSesion: Boolean, url: String): Intent =
            (if (conSesion) Intent(contexto, TiemposActivity::class.java).putExtra(EXTRA_URL, url)
             else Intent(contexto, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** Trae `hoy/{uid}` (una lectura), la guarda y redibuja. Nunca tira. Sin widget puesto, no gasta red. */
        fun actualizar(contexto: Context, pendiente: PendingResult?) {
            val ids = AppWidgetManager.getInstance(contexto).getAppWidgetIds(ComponentName(contexto, HoyWidget::class.java))
            if (ids.isEmpty()) { pendiente?.finish(); return }
            thread {
                val cache = CacheHoy(contexto)
                try {
                    val nube = Nube(contexto)
                    if (!nube.conSesion) { cache.respuesta = ""; cache.tildados = emptyMap(); cache.estado = "sin sesión" }
                    else if (!nube.esTiempos) { cache.respuesta = ""; cache.estado = "«Hoy» es de Timón: entrá con tu cuenta de Timón" }
                    else {
                        cache.respuesta = nube.hoyCrudo()
                        val doc = cache.doc
                        // Si Timón rearmó el documento, lo tildado acá ya lo sabe él.
                        if (doc != null && doc.armado != cache.armadoDeLosTildados) cache.tildados = emptyMap()
                        cache.estado = estadoDeHoy(doc, LocalDate.now().toString(), hora())
                    }
                } catch (e: Throwable) {
                    cache.estado = (e.message ?: "no se pudo actualizar") + " · " + hora()
                } finally {
                    dibujar(contexto)
                    pendiente?.finish()
                }
            }
        }

        /** El toque en el ○: se tacha ya y la escritura sale atrás; si la base dice que no, vuelve. */
        private fun tildar(contexto: Context, clave: String, hecho: Boolean, pendiente: PendingResult) {
            val cache = CacheHoy(contexto)
            val antes = cache.tildados
            val doc = cache.doc
            val r = antes[clave] ?: doc?.renglones?.firstOrNull { it.clave == clave }
            if (r == null) { pendiente.finish(); return }
            cache.tildados = if (hecho) antes + (clave to r) else antes - clave
            cache.armadoDeLosTildados = doc?.armado.orEmpty()
            cache.estado = "guardando…"
            dibujar(contexto)
            thread {
                try {
                    Nube(contexto).tildarHoy(r, hecho)
                    cache.respuesta = Nube(contexto).hoyCrudo()
                    cache.estado = estadoDeHoy(cache.doc, LocalDate.now().toString(), hora())
                } catch (e: Throwable) {
                    cache.tildados = antes
                    cache.estado = "No se guardó: " + (e.message ?: "error")
                } finally {
                    dibujar(contexto)
                    pendiente.finish()
                }
            }
        }

        private fun hora() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }
}

class HoyServicio : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = FilasHoy(applicationContext)
}

class FilasHoy(private val contexto: Context) : RemoteViewsService.RemoteViewsFactory {
    private var filas: List<FilaHoy> = emptyList()
    override fun onCreate() {}
    override fun onDataSetChanged() {
        val c = CacheHoy(contexto)
        filas = filasDeHoy(c.doc, c.tildados, LocalDate.now().toString())
    }
    override fun onDestroy() {}
    override fun getCount() = filas.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(posicion: Int) = filas.getOrNull(posicion)?.clave?.hashCode()?.toLong() ?: posicion.toLong()
    override fun hasStableIds() = true

    override fun getViewAt(posicion: Int): RemoteViews {
        val v = RemoteViews(contexto.packageName, R.layout.widget_hoy_fila)
        val f = filas.getOrNull(posicion) ?: return v
        val t = SpannableString(f.texto)
        if (f.hecho) t.setSpan(StrikethroughSpan(), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.setTextViewText(R.id.hoy_marca, if (f.hecho) "✓" else "○")
        v.setTextViewText(R.id.hoy_texto, t)
        v.setTextColor(R.id.hoy_texto, Color.parseColor(if (f.hecho) "#8A8F98" else "#F2F2F2"))
        v.setTextViewText(R.id.hoy_hora, f.hora)
        v.setTextColor(R.id.hoy_hora, Color.parseColor(if (f.hora == "vencido") "#E08B8B" else "#8A8F98"))
        // Los mismos colores que «Hoy» de Timón: dorado es mío, terracota de la familia.
        v.setInt(R.id.hoy_punto, "setBackgroundColor", Color.parseColor(if (f.espacio == "fam") "#C87F5A" else "#D8A657"))
        v.setOnClickFillInIntent(R.id.hoy_marca, Intent().putExtra("que", "hoy").putExtra("clave", f.clave).putExtra("tildar", !f.hecho))
        v.setOnClickFillInIntent(R.id.hoy_texto, Intent().putExtra("que", "abrir_item").putExtra("clave", f.clave))
        return v
    }
}
