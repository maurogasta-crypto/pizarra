package com.maurogasta.pizarra

import android.app.Activity
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Responder.kt — Del pedido de Facebook a la respuesta, con un toque.
// Sello: pizarra-55
//
// 18-oct-2026, Mauro: «llegar lo más directamente posible al mensaje en
// cuestión a responder, sin el riesgo de que pueda ser denunciada y bloqueada
// la cuenta… el camino que tenga menor intervención del humano». El 13C es el
// teléfono más corto: tiene la acción de Facebook que abre ESA publicación.
//
// Un aviso por pedido, en la barra, mientras esté sin atender:
//   · llega el pedido → «🏠 Pedido de alquiler» (como pizarra-34);
//   · la ronda dejó la respuesta → el MISMO aviso pasa a «✍️ Respuesta lista»,
//     con la respuesta a la vista y «Copiar y abrir»: copia la respuesta, abre
//     la publicación en Facebook y lo marca respondido en Casa Verde. Queda
//     tocar «Comentar», mantener apretado, Pegar y mandar.
//   · si alguien lo atendió desde otro lado (la lista de Casa Verde), el aviso
//     se va solo en la próxima vuelta.
//
// LA ACCIÓN DE FACEBOOK no se puede guardar en un archivo: vive en el aviso.
// Por eso va como acción «Ver publicación» del aviso propio, y se la vuelve a
// buscar ahí (`accionDeFacebook`) cada vez que el aviso cambia. Si el aviso se
// borró o el teléfono se reinició, «Copiar y abrir» usa el enlace de la lista
// (`enlace`, o la búsqueda con las palabras de la publicación).
//
// LO QUE NO HACE, a propósito: escribir en Facebook. Mandar lo hace una
// persona, desde su cuenta: una app que comenta sola es lo que hace que
// Facebook bloquee la cuenta. Y lo que escribió un tercero no va a la bitácora.
// ─────────────────────────────────────────────────────────────────────────────

const val TAG_PEDIDO = "fb:"
private const val ID_PEDIDO = 3701
const val CANAL_RESPUESTAS = "facebook-respuestas"
private const val EXTRA_FACEBOOK = "pizarra.facebook"

object Responder {
    private fun prefs(c: Context) = c.getSharedPreferences("pedidosfb", Context.MODE_PRIVATE)

    /** Lo que hace falta para responder sin red: la respuesta y adónde ir. */
    private fun guardar(c: Context, id: String, respuesta: String, buscar: String, enlace: String) {
        prefs(c).edit().putString("p:$id", JSONObject().put("respuesta", respuesta).put("buscar", buscar).put("enlace", enlace).toString()).apply()
    }
    private fun leido(c: Context, id: String): JSONObject =
        try { JSONObject(prefs(c).getString("p:$id", "{}")!!) } catch (e: Exception) { JSONObject() }
    fun respuesta(c: Context, id: String): String = leido(c, id).optString("respuesta")
    private fun url(c: Context, id: String): String = leido(c, id).let { it.optString("enlace").ifBlank { it.optString("buscar") } }
    private fun olvidar(c: Context, id: String) { prefs(c).edit().remove("p:$id").apply() }

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    private fun canales(c: Context) {
        nm(c)?.createNotificationChannel(NotificationChannel(CANAL_FACEBOOK, "Pedidos de alquiler (Facebook)",
            NotificationManager.IMPORTANCE_HIGH).apply { description = "Alguien pide alquilar en Canasvieiras en un grupo." })
        nm(c)?.createNotificationChannel(NotificationChannel(CANAL_RESPUESTAS, "Respuestas listas (Facebook)",
            NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Claude dejó la respuesta a un pedido de alquiler: copiar y abrir." })
    }

    /** La acción «Ver publicación» de Facebook que guarda el aviso de ese pedido, si sigue en la barra. */
    fun accionDeFacebook(c: Context, id: String): PendingIntent? = try {
        nm(c)?.activeNotifications?.firstOrNull { it.tag == TAG_PEDIDO + id }?.notification?.actions
            ?.firstOrNull { it.extras?.getBoolean(EXTRA_FACEBOOK) == true }?.actionIntent
    } catch (e: Throwable) { null }

    private fun aActividad(c: Context, id: String, que: String) = PendingIntent.getActivity(c, (que + id).hashCode(),
        Intent(c, ResponderActivity::class.java).putExtra("id", id).putExtra("que", que),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun conVer(c: Context, b: Notification.Builder, id: String, facebook: PendingIntent?) {
        if (facebook != null) {
            b.addAction(Notification.Action.Builder(null, "Ver publicación", facebook)
                .addExtras(Bundle().apply { putBoolean(EXTRA_FACEBOOK, true) }).build())
        } else url(c, id).takeIf { it.isNotBlank() }?.let { u ->
            b.addAction(Notification.Action.Builder(null, "Ver publicación", PendingIntent.getActivity(c, ("ver$id").hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(u)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
    }

    /** Llegó el pedido (desde el lector): el aviso con la acción de Facebook adentro. */
    fun avisarLlegada(c: Context, id: String, grupo: String, texto: String, facebook: PendingIntent?) {
        try {
            canales(c)
            if (respuesta(c, id).isEmpty()) guardar(c, id, "", enlaceBusqueda(texto), "")
            val b = Notification.Builder(c, CANAL_FACEBOOK)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("🏠 Pedido de alquiler en Canasvieiras")
                .setContentText(grupo)
                .setStyle(Notification.BigTextStyle().bigText("$grupo\n$texto"))
                .setContentIntent(aActividad(c, id, "copiar"))
                .setAutoCancel(false)
            conVer(c, b, id, facebook)
            nm(c)?.notify(TAG_PEDIDO + id, ID_PEDIDO, b.build())
        } catch (e: Throwable) {
            Bitacora.anotar(c, "pedidos: no se pudo avisar la llegada: ${e.javaClass.simpleName}")
        }
    }

    /** pizarra-51 (Mauro: «al abrir el comentario… debería saltar una ventana aviso que me digas
     *  si hay disponibilidad o no en Casa Verde»): sale UNA vez, al elegir responder —no mientras
     *  se recorre—, con la respuesta que quedó copiada a la vista. */
    fun avisarEleccion(c: Context, p: PedidoPantalla, ya: YaRespondido? = null) {
        try {
            canales(c)
            val b = Notification.Builder(c, CANAL_FACEBOOK)
                .setSmallIcon(if (ya != null || p.lugar is Lugar.Lleno || p.lugar is Lugar.MesLleno) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_email)
                .setContentTitle(tituloDeEleccion(p, ya))
                .setContentText(if (ya?.enPantalla == true) "Abajo ya hay un comentario con casaverdecanas.com.br. Si igual querés contestar, la respuesta está en Pedidos de alquiler." else p.respuesta)
                .setStyle(Notification.BigTextStyle().bigText(if (ya?.enPantalla == true)
                    "Abajo ya hay un comentario con casaverdecanas.com.br: no copié nada para no repetir. En Casa Verde → Pedidos de alquiler quedó como respondido."
                    else p.respuesta + "\n\nPegala: mantené apretado el cuadro → Pegar (o tocala arriba del teclado)."))
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
            nm(c)?.notify(TAG_PEDIDO + p.m.id, ID_PEDIDO, b.build())
        } catch (e: Throwable) {
            Bitacora.anotar(c, "pedidos: no se pudo avisar la disponibilidad: ${e.javaClass.simpleName}")
        }
    }

    /** pizarra-53: lo que se eligió no parece un pedido (un perfil, un botón): no se guarda nada. */
    fun noEsPedido(c: Context) {
        try {
            canales(c)
            nm(c)?.notify("fb:nada", ID_PEDIDO, Notification.Builder(c, CANAL_RESPUESTAS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("No reconocí un pedido de alojamiento acá")
                .setContentText("No guardé ni copié nada. Abrí la publicación y tocá su cuadro de comentario.")
                .setAutoCancel(true).setTimeoutAfter(60_000L).build())
        } catch (e: Throwable) {}
    }

    /** La respuesta está lista: el mismo aviso, con la respuesta y «Copiar y abrir». */
    private fun avisarLista(c: Context, p: PedidoCV) {
        canales(c)
        val facebook = accionDeFacebook(c, p.id)
        val copiar = aActividad(c, p.id, "copiar")
        val b = Notification.Builder(c, CANAL_RESPUESTAS)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("✍️ Respuesta lista · ${p.grupo.ifBlank { "Facebook" }}")
            .setContentText(p.respuesta)
            .setStyle(Notification.BigTextStyle().bigText("${p.respuesta}\n\n— Pedido: ${p.texto.take(300)}"))
            .setContentIntent(copiar)
            .setAutoCancel(false)
            .addAction(Notification.Action.Builder(null, "Copiar y abrir", copiar).build())
        conVer(c, b, p.id, facebook)
        b.addAction(Notification.Action.Builder(null, "Descartar", aActividad(c, p.id, "descartar")).build())
        nm(c)?.notify(TAG_PEDIDO + p.id, ID_PEDIDO, b.build())
    }

    /** Cada hora (y con «Traer respuestas»): avisa las respuestas nuevas y saca lo ya atendido. */
    fun sincronizar(c: Context, ahora: Long = System.currentTimeMillis()): Int {
        val cv = CuentaCasaVerde(c)
        if (!cv.conSesion) return 0
        val lista = cv.nuevos()
        val ids = lista.map { it.id }.toHashSet()
        // Lo que alguien atendió desde otro lado se va. Con media hora de gracia: un
        // pedido recién llegado puede no estar todavía en la lista.
        try {
            nm(c)?.activeNotifications?.filter { it.tag?.startsWith(TAG_PEDIDO) == true && ahora - it.postTime > 30 * 60_000L }
                ?.forEach { val id = it.tag.removePrefix(TAG_PEDIDO); if (id !in ids) { nm(c)?.cancel(it.tag, ID_PEDIDO); olvidar(c, id) } }
        } catch (e: Throwable) {}
        val p = prefs(c)
        val avisados = (p.getString("avisados", "[]")).let { s -> try { JSONArray(s).let { a -> MutableList(a.length()) { a.optString(it) } } } catch (e: Exception) { mutableListOf() } }
        var n = 0
        for (x in lista) {
            if (x.respuesta.isEmpty()) continue
            // pizarra-47: lo leído en pantalla ya se copió mientras se recorría; su aviso no abriría nada.
            if (x.fuente == "pantalla") continue
            guardar(c, x.id, x.respuesta, x.buscar, x.enlace)
            val marca = x.id + ":" + x.respuesta.hashCode()
            if (marca in avisados) continue
            avisarLista(c, x); avisados += marca; n++
        }
        p.edit().putString("avisados", JSONArray(avisados.takeLast(500)).toString()).apply()
        cv.respuestas = isoUtc(ahora).take(16).replace('T', ' ') + " · ${lista.size} sin atender · $n avisadas"
        return n
    }

    /** Desde el trabajo de cada hora. Nunca tira. */
    fun alDia(c: Context) {
        Buscador.traer(c)   // pizarra-46: lo que se busca puede haber cambiado en Casa Verde
        try {
            val n = sincronizar(c)
            if (n > 0) Bitacora.anotar(c, "pedidos: $n respuestas listas avisadas")
        } catch (e: Throwable) {
            Bitacora.anotar(c, "pedidos: no se pudieron traer las respuestas: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Abre la publicación: la acción de Facebook si sigue, si no el enlace de la lista. */
    fun abrir(a: Activity, id: String): Boolean {
        accionDeFacebook(a, id)?.let { pi ->
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    @Suppress("DEPRECATION")
                    val op = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(a, 0, null, null, null, null, op.toBundle())
                } else pi.send()
                return true
            } catch (e: Throwable) {}
        }
        val u = url(a, id)
        if (u.isBlank()) return false
        return try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Throwable) { false }
    }

    /** Marca en Casa Verde (en fondo) y saca el aviso. */
    fun cerrar(c: Context, id: String, estado: String) {
        val app = c.applicationContext
        nm(app)?.cancel(TAG_PEDIDO + id, ID_PEDIDO)
        olvidar(app, id)
        thread(name = "pedido") {
            try { CuentaCasaVerde(app).marcar(id, estado); Bitacora.anotar(app, "pedidos: uno marcado $estado") }
            catch (e: Throwable) { Bitacora.anotar(app, "pedidos: no se pudo marcar $estado: ${e.message ?: e.javaClass.simpleName}") }
        }
    }
}

/** El toque en el aviso: invisible, hace y se cierra (Android no deja abrir pantallas desde una difusión). */
class ResponderActivity : Activity() {
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        try {
            val id = intent.getStringExtra("id").orEmpty()
            if (id.isNotEmpty()) when (intent.getStringExtra("que")) {
                "descartar" -> { Responder.cerrar(this, id, "descartado"); Toast.makeText(this, "Pedido descartado.", Toast.LENGTH_SHORT).show() }

                else -> {
                    val r = Responder.respuesta(this, id)
                    if (r.isNotEmpty()) {
                        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Respuesta", r))
                        Toast.makeText(this, "Respuesta copiada. En la publicación: Comentar → mantené apretado → Pegar.", Toast.LENGTH_LONG).show()
                    }
                    val abrio = Responder.abrir(this, id)
                    if (!abrio) Toast.makeText(this, "No se pudo abrir la publicación: buscala en el grupo.", Toast.LENGTH_LONG).show()
                    // Con la respuesta en la mano, queda respondido (en la lista se puede volver atrás).
                    if (r.isNotEmpty()) Responder.cerrar(this, id, "respondido")
                }
            }
        } catch (e: Throwable) {
            Bitacora.anotar(this, "pedidos: " + (e.message ?: e.javaClass.simpleName))
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
