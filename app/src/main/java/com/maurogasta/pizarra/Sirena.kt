package com.maurogasta.pizarra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

// ─────────────────────────────────────────────────────────────────────────────
// Sirena.kt — La alarma que suena HASTA que se la apaga. Sello: pizarra-10
//
// 5-oct-2026, 00:56: la primera alarma de verdad «sonó pero se cortó
// enseguida». Sonaba la NOTIFICACIÓN, y aunque llevaba FLAG_INSISTENT, HyperOS
// corta el sonido de una notificación a los pocos segundos. Un despertador no
// puede depender de eso: ahora suena la app misma, con un servicio en primer
// plano que reproduce el sonido de alarma del teléfono en bucle (volumen de
// ALARMA, no de notificaciones) y vibra, hasta «Apagar» —en la notificación o
// en la pantalla— o hasta 3 minutos, para que no suene toda la noche en un
// teléfono olvidado.
// ─────────────────────────────────────────────────────────────────────────────

private const val CANAL_SIRENA = "sirena-1"
const val ACCION_APAGAR = "com.maurogasta.pizarra.APAGAR"
private const val NOTI_SIRENA = 7001
private const val TOPE_MS = 3 * 60 * 1000L

class SirenaServicio : Service() {
    private var reproductor: MediaPlayer? = null
    private var tono: android.media.Ringtone? = null
    private val reloj = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == ACCION_APAGAR) { apagar(); return START_NOT_STICKY }
        val texto = i?.getStringExtra("texto").orEmpty()
        val hora = i?.getStringExtra("hora").orEmpty()
        try {
            val n = notificacion(this, texto, hora)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTI_SIRENA, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTI_SIRENA, n)
            sonar()
            Bitacora.anotar(this, "sirena: suena ($hora)")
        } catch (e: Throwable) {
            Bitacora.anotar(this, "sirena: no pudo sonar: " + Bitacora.pila(e).take(300))
            stopSelf()
        }
        reloj.removeCallbacksAndMessages(null)
        reloj.postDelayed({ Bitacora.anotar(this, "sirena: 3 min sin apagar, se calla"); apagar() }, TOPE_MS)
        return START_NOT_STICKY
    }

    private fun sonar() {
        reproductor?.release()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val atributos = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        try {
            reproductor = MediaPlayer().apply {
                setAudioAttributes(atributos)
                setDataSource(this@SirenaServicio, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Throwable) {
            // Si el reproductor no puede con ese sonido, el Ringtone del sistema,
            // también en bucle: una alarma muda es peor que cualquier otra cosa.
            reproductor?.release(); reproductor = null
            Bitacora.anotar(this, "sirena: reproductor falló (${e.message}); va el tono del sistema")
            tono = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = atributos
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        @Suppress("DEPRECATION")
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
    }

    private fun apagar() {
        reloj.removeCallbacksAndMessages(null)
        try { reproductor?.stop() } catch (e: Throwable) {}
        reproductor?.release(); reproductor = null
        tono?.stop(); tono = null
        @Suppress("DEPRECATION")
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        reloj.removeCallbacksAndMessages(null)
        reproductor?.release(); reproductor = null
        tono?.stop(); tono = null
        super.onDestroy()
    }

    companion object {
        fun arrancar(c: Context, texto: String, hora: String) {
            val i = Intent(c, SirenaServicio::class.java).putExtra("texto", texto).putExtra("hora", hora)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun apagar(c: Context) {
            try { c.startService(Intent(c, SirenaServicio::class.java).setAction(ACCION_APAGAR)) }
            catch (e: Throwable) { c.stopService(Intent(c, SirenaServicio::class.java)) }
        }

        /** La notificación del servicio: callada (suena el servicio), con «Apagar» y la pantalla entera. */
        fun notificacion(c: Context, texto: String, hora: String): Notification {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(CANAL_SIRENA, "Alarma sonando", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "La alarma que está sonando, con «Apagar»."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
            val pantalla = PendingIntent.getActivity(c, 7002,
                Intent(c, AlarmaPantalla::class.java).setData(Uri.parse("tiempos://sirena"))
                    .putExtra("texto", texto).putExtra("hora", hora).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val apagar = PendingIntent.getService(c, 7003,
                Intent(c, SirenaServicio::class.java).setAction(ACCION_APAGAR), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(c, CANAL_SIRENA)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ $hora · $texto")
                .setContentText("Sonando. Tocá Apagar.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(pantalla)
                .setFullScreenIntent(pantalla, true)
                .addAction(Notification.Action.Builder(null, "Apagar", apagar).build())
                .build()
        }
    }
}
