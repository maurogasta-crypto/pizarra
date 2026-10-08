package com.maurogasta.pizarra

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.ZoneId
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Alarmas.kt — Los recordatorios y las alarmas de Tiempos, a su hora. Sello: pizarra-13
//
// Pedido de Mauro, 5-oct-2026: «mañana tengo que ir antes al gimnasio… poner
// una sirena un rato antes». El sitio guarda la alerta en `alertas/` (app-13)
// pero una página no puede sonar con el teléfono bloqueado. Esta app sí:
//
//   · Trae MIS alertas de la base (la regla deja leer sólo las propias), las
//     guarda en el teléfono y las programa con el AlarmManager de Android.
//   · Un RECORDATORIO es una notificación común.
//   · Una ALARMA es la «sirena»: una notificación que suena con el sonido de
//     alarma del teléfono y SE REPITE (FLAG_INSISTENT) hasta que se la toca, y
//     con la pantalla bloqueada abre una pantalla entera con «Apagar».
//
// ── CUÁNDO SE PONE AL DÍA ────────────────────────────────────────────────
// Al abrir la app, cada vez que el widget se actualiza (media hora), con un
// trabajo de Android cada hora aunque no haya widget, y al prender el teléfono
// (Android borra las alarmas al apagarse: se reprograman desde lo guardado,
// sin esperar a la red). Lo que se borró en Tiempos se desprograma.
//
// ── LO QUE NO HACE ───────────────────────────────────────────────────────
// No escribe en la base: apagar una alarma no la borra en Tiempos (el «Listo»
// de Ahora sí). Y no programa lo que ya pasó: sonar a destiempo enseña a no
// hacerle caso.
// ─────────────────────────────────────────────────────────────────────────────

const val ACCION_ALERTA = "com.maurogasta.pizarra.ALERTA"
private const val CANAL_ALARMA = "alarmas-1"
private const val CANAL_RECORDATORIO = "recordatorios-1"
private const val TRABAJO_SINCRO = 7

object Alarmas {
    private fun prefs(c: Context) = c.getSharedPreferences("alarmas", Context.MODE_PRIVATE)

    var Context.alertasGuardadas: List<Alerta>
        get() = alertasDeJson(prefs(this).getString("alertas", null))
        set(v) = prefs(this).edit().putString("alertas", alertasAJson(v)).apply()

    /** Trae de la base, guarda y reprograma. En un hilo; nunca tira. */
    fun sincronizar(c: Context, listo: ((String) -> Unit)? = null) {
        val ctx = c.applicationContext
        thread {
            val msg = try {
                val nube = Nube(ctx)
                if (!nube.conSesion) "sin sesión" else {
                    val a = nube.alertas()
                    ctx.alertasGuardadas = a
                    "${programar(ctx, a)} programada(s)"
                }
            } catch (e: Throwable) {
                // Sin red: queda programado lo último que se supo. Y si
                // programar también falla, se anota: un hilo que tira cierra la app.
                try { programar(ctx, ctx.alertasGuardadas); e.message ?: "no se pudo traer las alertas" }
                catch (x: Throwable) { Bitacora.anotar(ctx, "programar falló: " + Bitacora.pila(x).take(300)); "no se pudieron programar" }
            }
            listo?.invoke(msg)
        }
    }

    /** Deja programadas exactamente éstas: las nuevas se agregan, las que ya no están se sacan. */
    fun programar(c: Context, alertas: List<Alerta>): Int {
        crearCanales(c)
        val am = c.getSystemService(AlarmManager::class.java) ?: return 0
        val lista = paraProgramar(alertas, System.currentTimeMillis(), ZoneId.systemDefault())
        val antes = prefs(c).getStringSet("programadas", emptySet()) ?: emptySet()
        val ahora = lista.map { it.first.id }.toSet()
        val enLaBase = alertas.map { it.id }.toSet()
        // pizarra-8 (tiempos:A13): sólo se desprograma lo que se BORRÓ de la
        // base. Antes se desprogramaba también lo que ya había pasado de hora,
        // y una alarma que Android demoraba unos segundos se cancelaba si en
        // ese momento uno volvía a la app — la de las 7:26 del 5-oct.
        for (id in aCancelar(antes, enLaBase)) intencion(c, id, null, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        val exactas = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        lista.firstOrNull()?.let { (a, t) ->
            Bitacora.anotar(c, "programar: ${lista.size}, la próxima ${a.dia} ${a.hora} (${a.tipo}) · exactas ${if (exactas) "sí" else "NO"}")
        }
        for ((a, t) in lista) {
            val pi = intencion(c, a.id, a, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
            try {
                when {
                    // La alarma se ve en la barra como un despertador, y Android
                    // la respeta aunque el teléfono esté ahorrando batería.
                    a.tipo == "alarma" && exactas -> am.setAlarmClock(AlarmManager.AlarmClockInfo(t, abrirApp(c)), pi)
                    exactas -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                    // Sin permiso de exactas: suena, pero Android puede correrla unos minutos.
                    else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                }
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            }
        }
        prefs(c).edit().putStringSet("programadas", ahora + (antes intersect enLaBase)).apply()
        return lista.size
    }

    fun exactasPermitidas(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** Un trabajo de Android que se pone al día cada hora, con red, aunque no haya widget. */
    fun asegurarTrabajo(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(TRABAJO_SINCRO) != null) return
        // Un permiso que falte acá no puede cerrar la app: las alarmas se
        // ponen al día igual al abrirla y con el widget (pizarra-4).
        try { js.schedule(JobInfo.Builder(TRABAJO_SINCRO, ComponentName(c, SincroTrabajo::class.java))
            .setPeriodic(60 * 60 * 1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()) } catch (e: Throwable) { Bitacora.anotar(c, "trabajo de cada hora: " + (e.message ?: e.toString())) }
    }

    // Cada alerta tiene su intención, distinguida por la dirección: con el
    // mismo número de pedido y sin dirección, Android las confundiría.
    private fun intencion(c: Context, id: String, a: Alerta?, bandera: Int): PendingIntent? {
        val i = Intent(c, AlarmaReceptor::class.java).setAction(ACCION_ALERTA).setData(Uri.parse("tiempos://alerta/$id"))
        if (a != null) i.putExtra("id", a.id).putExtra("tipo", a.tipo).putExtra("texto", a.texto).putExtra("hora", a.hora)
        return PendingIntent.getBroadcast(c, 0, i, bandera or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun abrirApp(c: Context) = PendingIntent.getActivity(c, 10,
        Intent(c, TiemposActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun crearCanales(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val sonido = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        nm.createNotificationChannel(NotificationChannel(CANAL_ALARMA, "Alarmas de Tiempos", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Suenan como un despertador hasta que las apagás."
            setSound(sonido, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_RECORDATORIO, "Recordatorios de Tiempos", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Un aviso a la hora que dijiste."
        })
    }

    fun avisar(c: Context, id: String, tipo: String, texto: String, hora: String) {
        crearCanales(c)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val numero = id.hashCode()
        val alarma = tipo == "alarma"
        // pizarra-6: la alarma suena desde el servicio (Sirena.kt), no desde la
        // notificación, que HyperOS corta a los pocos segundos. Si el servicio
        // no puede arrancar, queda la notificación de antes.
        if (alarma) try { SirenaServicio.arrancar(c, texto, hora); return } catch (e: Throwable) {
            Bitacora.anotar(c, "sirena no arrancó, va la notificación: " + (e.message ?: e.toString()))
        }
        val pantalla = PendingIntent.getActivity(c, numero,
            Intent(c, AlarmaPantalla::class.java).setData(Uri.parse("tiempos://alerta/$id"))
                .putExtra("texto", texto).putExtra("hora", hora).putExtra("numero", numero)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, if (alarma) CANAL_ALARMA else CANAL_RECORDATORIO)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (alarma) "⏰ $hora · $texto" else "🔔 $texto")
            .setContentText(if (alarma) "Tocá para apagar" else "Recordatorio de Tiempos · $hora")
            .setCategory(if (alarma) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setContentIntent(if (alarma) pantalla else abrirApp(c))
            .setAutoCancel(true)
        if (alarma) b.setFullScreenIntent(pantalla, true)
        val n = b.build()
        // La sirena: el sonido se repite hasta que se toca la notificación.
        if (alarma) n.flags = n.flags or Notification.FLAG_INSISTENT
        try { nm.notify(numero, n) } catch (e: SecurityException) { /* sin permiso de notificaciones */ }
    }
}

/** Suena la alerta; y al prender el teléfono o actualizar la app, reprograma. */
class AlarmaReceptor : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = try { recibir(c, i) } catch (e: Throwable) {
        Bitacora.anotar(c, "receptor ${i.action}: " + Bitacora.pila(e).take(300))
    }

    private fun recibir(c: Context, i: Intent) {
        when (i.action) {
            ACCION_ALERTA -> {
                // Con commit: si después algo cierra la app, que quede que llegó.
                Bitacora.anotar(c, "alerta RECIBIDA: ${i.getStringExtra("tipo")} ${i.getStringExtra("hora")}", ya = true)
                Alarmas.avisar(c, i.getStringExtra("id") ?: return, i.getStringExtra("tipo") ?: "recordatorio",
                i.getStringExtra("texto") ?: "", i.getStringExtra("hora") ?: "")
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                with(Alarmas) { programar(c, c.alertasGuardadas) }
                Alarmas.asegurarTrabajo(c)
            }
        }
    }
}

class SincroTrabajo : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        // pizarra-9: de paso, el latido de la bodega si toca (cada 12 h).
        kotlin.concurrent.thread { try { Bodega.alDia(this) } catch (e: Throwable) {} }
        Alarmas.sincronizar(this) { jobFinished(p, false) }
        return true
    }
    override fun onStopJob(p: JobParameters) = true
}

/** La pantalla de la alarma, encima del bloqueo: qué es y «Apagar». */
class AlarmaPantalla : Activity() {
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val d = resources.displayMetrics.density
        val numero = intent.getIntExtra("numero", 0)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#16191C"))
            setPadding((24 * d).toInt(), 0, (24 * d).toInt(), 0)
            addView(TextView(context).apply {
                text = "⏰ " + intent.getStringExtra("hora").orEmpty()
                textSize = 42f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = intent.getStringExtra("texto").orEmpty()
                textSize = 24f; setTextColor(Color.parseColor("#E8D3A2")); gravity = Gravity.CENTER
                setPadding(0, (16 * d).toInt(), 0, (32 * d).toInt())
            })
            addView(Button(context).apply {
                text = "Apagar"; textSize = 22f; isAllCaps = false
                setOnClickListener {
                    getSystemService(NotificationManager::class.java)?.cancel(numero)
                    SirenaServicio.apagar(context)
                    finish()
                }
            })
        })
    }
}
