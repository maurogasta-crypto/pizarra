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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// PizarraWidget.kt — La pizarra en la pantalla de inicio. Sello: pizarra-11
//
// Pedido de Mauro, 2-oct-2026: «una pizarra de pendientes en una de mis
// pantallas de Android, como widget, que cargue de mis listas de tareas de
// tiempos los puntos que yo quiera colocar, para marcar o tachar como ya
// realizado; cuando hago eso ya lo señala en mi sitio y queda informado para
// todos con los que comparta esa tarea».
//
// ── CÓMO SE MANTIENE AL DÍA ─────────────────────────────────────────────────
// Un widget de Android no puede escuchar la base en vivo como el sitio. Se
// pone al día: al tocar ⟳, al tachar algo, al abrir la app, y solo cada media
// hora (lo mínimo que deja Android sin gastar batería). Lo que se ve al
// instante es lo que tachás vos; lo que tachó el otro llega con la próxima
// puesta al día, y la hora de la última está escrita en el encabezado.
//
// ── TACHAR NO ESPERA A LA RED ───────────────────────────────────────────────
// La fila se tacha ya y la escritura sale atrás. Si la base dice que no, la
// fila vuelve como estaba y el encabezado dice por qué. Nunca queda tachado
// algo que la base no tiene tachado.
// ─────────────────────────────────────────────────────────────────────────────

const val ACCION_TACHAR = "com.maurogasta.pizarra.TACHAR"
const val ACCION_ACTUALIZAR = "com.maurogasta.pizarra.ACTUALIZAR"

class PizarraWidget : AppWidgetProvider() {

    override fun onUpdate(contexto: Context, manager: AppWidgetManager, ids: IntArray) {
        dibujar(contexto)
        actualizarDesdeLaRed(contexto, null)
        // pizarra-2: de paso, las alarmas de Tiempos (Alarmas.kt).
        Alarmas.sincronizar(contexto)
    }

    override fun onReceive(contexto: Context, intent: Intent) {
        super.onReceive(contexto, intent)
        when (intent.action) {
            ACCION_ACTUALIZAR -> {
                Cache(contexto).estado = "Actualizando…"
                dibujar(contexto)
                actualizarDesdeLaRed(contexto, goAsync())
                Alarmas.sincronizar(contexto)
            }
            ACCION_TACHAR -> {
                val id = intent.getStringExtra("id") ?: return
                val hecho = intent.getBooleanExtra("hecho", true)
                tachar(contexto, id, hecho, goAsync())
            }
        }
    }

    companion object {
        /** Redibuja todos los widgets con lo que hay en la caché. */
        fun dibujar(contexto: Context) {
            val manager = AppWidgetManager.getInstance(contexto)
            val ids = manager.getAppWidgetIds(ComponentName(contexto, PizarraWidget::class.java))
            if (ids.isEmpty()) return
            val cache = Cache(contexto)
            for (id in ids) {
                val v = RemoteViews(contexto.packageName, R.layout.widget)
                val lista = Intent(contexto, FilasServicio::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                lista.data = Uri.parse(lista.toUri(Intent.URI_INTENT_SCHEME))
                v.setRemoteAdapter(R.id.lista, lista)
                v.setEmptyView(R.id.lista, R.id.vacia)
                v.setTextViewText(R.id.vacia,
                    if (Nube(contexto).conSesion) "Nada en la pizarra.\nTocá ✎ para elegir tareas."
                    else "Tocá ✎ y entrá con tu cuenta de Tiempos.")
                v.setTextViewText(R.id.estado, cache.estado)
                v.setOnClickPendingIntent(R.id.actualizar, difusion(contexto, ACCION_ACTUALIZAR, 1))
                v.setOnClickPendingIntent(R.id.editar, PendingIntent.getActivity(contexto, 2,
                    Intent(contexto, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                // pizarra-2: 🎙 abre la app directo en el dictado.
                v.setOnClickPendingIntent(R.id.dictar, PendingIntent.getActivity(contexto, 4,
                    Intent(contexto, MainActivity::class.java).putExtra(EXTRA_DICTAR, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                // La plantilla de cada fila: MUTABLE porque la fila le agrega
                // qué tarea es y si se tacha o se destacha.
                v.setPendingIntentTemplate(R.id.lista, PendingIntent.getBroadcast(contexto, 3,
                    Intent(contexto, PizarraWidget::class.java).setAction(ACCION_TACHAR),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
                manager.updateAppWidget(id, v)
                manager.notifyAppWidgetViewDataChanged(id, R.id.lista)
            }
        }

        private fun difusion(contexto: Context, accion: String, codigo: Int) = PendingIntent.getBroadcast(
            contexto, codigo, Intent(contexto, PizarraWidget::class.java).setAction(accion),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** Trae las tareas, guarda la caché y redibuja. Nunca tira. */
        fun actualizarDesdeLaRed(contexto: Context, pendiente: PendingResult?) {
            thread {
                val cache = Cache(contexto)
                try {
                    val nube = Nube(contexto)
                    if (nube.conSesion) {
                        cache.filas = paraLaPizarra(nube.tareas())
                        cache.estado = "al día · " + hora()
                    } else {
                        cache.filas = emptyList()
                        cache.estado = "sin sesión"
                    }
                } catch (e: Exception) {
                    cache.estado = (e.message ?: "no se pudo actualizar") + " · " + hora()
                } finally {
                    dibujar(contexto)
                    pendiente?.finish()
                }
            }
        }

        private fun tachar(contexto: Context, id: String, hecho: Boolean, pendiente: PendingResult) {
            val cache = Cache(contexto)
            val antes = cache.filas
            cache.filas = conTachada(antes, id, hecho)
            cache.estado = "guardando…"
            dibujar(contexto)
            thread {
                try {
                    Nube(contexto).tachar(id, hecho)
                    cache.filas = paraLaPizarra(Nube(contexto).tareas())
                    cache.estado = "al día · " + hora()
                } catch (e: Exception) {
                    // La base no lo tiene: la fila vuelve como estaba.
                    cache.filas = antes
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

/** Lo último que se supo, para dibujar sin esperar a la red. */
class Cache(contexto: Context) {
    private val p = contexto.getSharedPreferences("pizarra", Context.MODE_PRIVATE)
    var filas: List<Fila>
        get() = filasDeJson(p.getString("filas", null))
        set(v) = p.edit().putString("filas", filasAJson(v)).apply()
    var estado: String
        get() = p.getString("estado", "") ?: ""
        set(v) = p.edit().putString("estado", v).apply()
}

class FilasServicio : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Filas(applicationContext)
}

class Filas(private val contexto: Context) : RemoteViewsService.RemoteViewsFactory {
    private var filas: List<Fila> = emptyList()
    override fun onCreate() {}
    override fun onDataSetChanged() { filas = Cache(contexto).filas }
    override fun onDestroy() {}
    override fun getCount() = filas.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(posicion: Int) = filas.getOrNull(posicion)?.id?.hashCode()?.toLong() ?: posicion.toLong()
    override fun hasStableIds() = true

    override fun getViewAt(posicion: Int): RemoteViews {
        val v = RemoteViews(contexto.packageName, R.layout.widget_fila)
        val f = filas.getOrNull(posicion) ?: return v
        val texto = SpannableString(f.texto)
        if (f.hecho) texto.setSpan(StrikethroughSpan(), 0, texto.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.setTextViewText(R.id.marca, if (f.hecho) "☑" else "☐")
        v.setTextViewText(R.id.texto, texto)
        v.setTextColor(R.id.texto, if (f.hecho) Color.parseColor("#8A8F98") else Color.parseColor("#F2F2F2"))
        // Tocar una pendiente la tacha; tocar una tachada la vuelve a pendiente,
        // por si fue un dedo de más.
        v.setOnClickFillInIntent(R.id.fila, Intent().putExtra("id", f.id).putExtra("hecho", !f.hecho))
        return v
    }
}

