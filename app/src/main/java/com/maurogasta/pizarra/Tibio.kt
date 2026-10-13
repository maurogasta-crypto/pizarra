package com.maurogasta.pizarra

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Tibio.kt — Las luces, listas para el toque, de 8 a 23. Sello: pizarra-26
//
// Mauro, 13-oct-2026: «mantener la sesión despierta en el horario de uso del
// teléfono, desde las 8 a las 23:00». Lo que hace demorar el primer toque es
// lo que se enfría: la sesión de remate (dura una hora) y la función del
// puente, que el servidor apaga si nadie la llama por unos minutos.
//
// Cada 10 minutos, de 8 a 23: si a la sesión le quedan menos de 15 minutos se
// renueva, y se le hace al puente un pedido SIN sesión —contesta «falta la
// sesión» enseguida— que alcanza para que la función siga despierta. No toca
// Tuya ni el cupo de sus consultas, no lee la base y no mueve ninguna luz.
// Fuera del horario no hace nada y se agenda para las 8.
//
// Sólo corre si hay sesión de remate en la app. Son unos noventa pedidos
// chicos por día: la batería no se entera.
// ─────────────────────────────────────────────────────────────────────────────

const val TIBIO_DESDE = 8
const val TIBIO_HASTA = 23
const val TIBIO_CADA_MIN = 10L
private const val ACCION_TIBIO = "com.maurogasta.pizarra.TIBIO"

/** Cuándo toca el próximo: en 10 minutos si cae dentro de 8–23; si no, a las 8. */
fun proximoTibio(ahora: LocalDateTime): LocalDateTime {
    val ocho = ahora.toLocalDate().atTime(TIBIO_DESDE, 0)
    if (ahora.isBefore(ocho)) return ocho                      // de madrugada: a las 8 en punto
    val siguiente = ahora.plusMinutes(TIBIO_CADA_MIN)
    if (enHorario(ahora) && enHorario(siguiente) && siguiente.toLocalDate() == ahora.toLocalDate()) return siguiente
    return ocho.plusDays(1)
}

/** ¿Es horario de uso? */
fun enHorario(ahora: LocalDateTime): Boolean = ahora.hour in TIBIO_DESDE until TIBIO_HASTA

object Tibio {
    /** Deja agendado el próximo (o lo saca, si no hay sesión de remate). */
    fun agendar(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 30, Intent(c, TibioReceptor::class.java).setAction(ACCION_TIBIO),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (!Luces(c).conSesion) { am.cancel(pi); return }
        val cuando = proximoTibio(LocalDateTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Inexacto a propósito (una ventana de 2 minutos): Android junta los
        // despertares y gasta menos. Para mantener algo tibio, sobra.
        am.setWindow(AlarmManager.RTC_WAKEUP, cuando, 2 * 60_000L, pi)
    }
}

class TibioReceptor : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pendiente = goAsync()
        thread {
            try {
                if (enHorario(LocalDateTime.now())) Luces(c).mantenerTibia()
            } catch (e: Throwable) {
                // Sin señal o la sesión venció: se intenta en el próximo. No se anota
                // cada vez, para no llenar la bitácora de cortes de red.
            } finally {
                try { Tibio.agendar(c) } catch (e: Throwable) { }
                pendiente.finish()
            }
        }
    }
}
