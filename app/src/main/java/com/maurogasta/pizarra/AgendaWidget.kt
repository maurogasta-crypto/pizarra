package com.maurogasta.pizarra

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// AgendaWidget.kt — La agenda de la semana en la pantalla de inicio. Sello: pizarra-20
//
// El segundo widget (Mauro, 12-oct-2026: «uno para la pizarra y otro para la
// agenda semanal de ese usuario»). Es de LECTURA: muestra los siete días que
// vienen, cada uno con su encabezado, y tocar cualquier renglón abre Tiempos en
// la Agenda (`?solapa=agenda`, app-32), donde se mueve y se edita. Editar desde
// el widget sería un segundo lugar donde se cambia la agenda.
//
// Se pone al día igual que la pizarra: al tocar ⟳, al abrir la app, cada media
// hora (lo que deja Android) y con el trabajo de cada hora (SincroTrabajo).
// Lo que muestra es de la persona con la que entró la app: su agenda es suya
// y la regla de `agendas/` no deja leer la del otro.
// ─────────────────────────────────────────────────────────────────────────────

const val ACCION_AGENDA = "com.maurogasta.pizarra.AGENDA_ACTUALIZAR"
/** Tiempos abierto en la Agenda (app-32 lee `?solapa=`). */
const val URL_AGENDA = TIEMPOS_URL + "?solapa=agenda"

class AgendaWidget : AppWidgetProvider() {

    override fun onUpdate(contexto: Context, manager: AppWidgetManager, ids: IntArray) {
        dibujar(contexto)
        actualizar(contexto, null)
    }

    override fun onReceive(contexto: Context, intent: Intent) {
        super.onReceive(contexto, intent)
        if (intent.action == ACCION_AGENDA) {
            CacheAgenda(contexto).estado = "Actualizando…"
            dibujar(contexto)
            actualizar(contexto, goAsync())
        }
    }

    companion object {
        fun dibujar(contexto: Context) {
            val manager = AppWidgetManager.getInstance(contexto)
            val ids = manager.getAppWidgetIds(ComponentName(contexto, AgendaWidget::class.java))
            if (ids.isEmpty()) return
            val cache = CacheAgenda(contexto)
            val conSesion = Nube(contexto).conSesion
            val abrir = PendingIntent.getActivity(contexto, 11, intentAbrir(contexto, conSesion),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            for (id in ids) {
                val v = RemoteViews(contexto.packageName, R.layout.widget_agenda)
                val lista = Intent(contexto, AgendaServicio::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                lista.data = Uri.parse(lista.toUri(Intent.URI_INTENT_SCHEME))
                v.setRemoteAdapter(R.id.lista_agenda, lista)
                v.setEmptyView(R.id.lista_agenda, R.id.vacia_agenda)
                v.setTextViewText(R.id.vacia_agenda, if (conSesion) "Tocá ⟳ para traer tu semana." else "Tocá y entrá con tu cuenta de Tiempos.")
                v.setTextViewText(R.id.estado_agenda, cache.estado)
                v.setOnClickPendingIntent(R.id.actualizar_agenda, PendingIntent.getBroadcast(contexto, 10,
                    Intent(contexto, AgendaWidget::class.java).setAction(ACCION_AGENDA),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                v.setOnClickPendingIntent(R.id.titulo_agenda, abrir)
                v.setOnClickPendingIntent(R.id.vacia_agenda, abrir)
                // Cada renglón abre lo mismo: la Agenda de Tiempos. La plantilla de
                // una lista tiene que ser MUTABLE para que el renglón la complete.
                v.setPendingIntentTemplate(R.id.lista_agenda, PendingIntent.getActivity(contexto, 12, intentAbrir(contexto, conSesion),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
                manager.updateAppWidget(id, v)
                manager.notifyAppWidgetViewDataChanged(id, R.id.lista_agenda)
            }
        }

        private fun intentAbrir(contexto: Context, conSesion: Boolean): Intent =
            if (conSesion) Intent(contexto, TiemposActivity::class.java).putExtra(EXTRA_URL, URL_AGENDA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            else Intent(contexto, MainActivity::class.java)

        /** Trae la semana, la guarda y redibuja. Nunca tira. Sin widget puesto, no gasta red. */
        fun actualizar(contexto: Context, pendiente: PendingResult?) {
            val ids = AppWidgetManager.getInstance(contexto).getAppWidgetIds(ComponentName(contexto, AgendaWidget::class.java))
            if (ids.isEmpty()) { pendiente?.finish(); return }
            thread {
                val cache = CacheAgenda(contexto)
                try {
                    val nube = Nube(contexto)
                    if (!nube.conSesion) { cache.filas = emptyList(); cache.estado = "sin sesión" }
                    else if (!nube.esTiempos) { cache.filas = emptyList(); cache.estado = "la agenda es de Tiempos" }
                    else {
                        cache.filas = nube.agendaSemana(LocalDate.now().toString())
                        cache.estado = "al día · " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    }
                } catch (e: Throwable) {
                    cache.estado = (e.message ?: "no se pudo actualizar") + " · " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                } finally {
                    dibujar(contexto)
                    pendiente?.finish()
                }
            }
        }
    }
}

class CacheAgenda(contexto: Context) {
    private val p = contexto.getSharedPreferences("agenda", Context.MODE_PRIVATE)
    var filas: List<FilaAgenda>
        get() = filasAgendaDeJson(p.getString("filas", null))
        set(v) = p.edit().putString("filas", filasAgendaAJson(v)).apply()
    var estado: String
        get() = p.getString("estado", "") ?: ""
        set(v) = p.edit().putString("estado", v).apply()
}

class AgendaServicio : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = FilasAgenda(applicationContext)
}

class FilasAgenda(private val contexto: Context) : RemoteViewsService.RemoteViewsFactory {
    private var filas: List<FilaAgenda> = emptyList()
    override fun onCreate() {}
    override fun onDataSetChanged() {
        // La semana empieza HOY: si la caché es de ayer, lo de ayer no se muestra.
        val hoy = LocalDate.now().toString()
        filas = CacheAgenda(contexto).filas.filter { it.dia >= hoy }
    }
    override fun onDestroy() {}
    override fun getCount() = filas.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 2
    override fun getItemId(posicion: Int) = posicion.toLong()
    override fun hasStableIds() = false

    override fun getViewAt(posicion: Int): RemoteViews {
        val f = filas.getOrNull(posicion) ?: return RemoteViews(contexto.packageName, R.layout.widget_agenda_fila)
        if (f.clase == "dia") {
            val v = RemoteViews(contexto.packageName, R.layout.widget_agenda_dia)
            v.setTextViewText(R.id.dia_texto, f.titulo)
            v.setTextColor(R.id.dia_texto, Color.parseColor(if (f.hoy) "#E8B85C" else "#AEB3BA"))
            v.setOnClickFillInIntent(R.id.dia_fila, Intent())
            return v
        }
        val v = RemoteViews(contexto.packageName, R.layout.widget_agenda_fila)
        v.setTextViewText(R.id.agenda_hora, f.hora)
        v.setTextViewText(R.id.agenda_titulo, f.titulo)
        v.setTextViewText(R.id.agenda_sub, f.sub)
        // El color de la clase, como en la agenda de Tiempos.
        v.setInt(R.id.agenda_barra, "setBackgroundColor", Color.parseColor(when (f.clase) {
            "chicos" -> "#C89BD8"; "tarea" -> "#7FB4BF"; else -> "#D8A657" }))
        v.setOnClickFillInIntent(R.id.agenda_fila, Intent())
        return v
    }
}
