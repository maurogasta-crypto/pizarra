package com.maurogasta.pizarra

import android.content.Context

// ─────────────────────────────────────────────────────────────────────────────
// Despertar.kt — Ponerse al día ENTERA, de una vez. Sello: pizarra-63
//
// Mauro, 24-oct-2026: «renovar todas las pizarras en cada modificación, además
// de una vez por hora»; y después: «los mensajes en el ecosistema tienen que
// usar ese canal para notificar despertando el dispositivo». Es el UNO lugar
// que se llama cuando hay que ponerse al día:
//   · hoy, cada 10 minutos de 8 a 23 (`Charla.agendar`, la cuenta de Tibio):
//     antes ese despertar traía sólo los mensajes; desde pizarra-59 trae todo;
//   · mañana, el aviso instantáneo (general:C1, fase 2): cuando alguien guarda
//     algo, llega un aviso SIN contenido —«algo cambió»— y llama a esto mismo.
//     Por eso el canal se puede cambiar (Firebase Cloud Messaging hoy, ntfy si
//     algún teléfono queda sin Google) sin tocar nada de acá.
// Lo que trae: los mensajes y las novedades (que avisan solos lo nuevo), las
// tareas de la pizarra, la semana de «Mi agenda», los avisos de Claude y las
// alarmas. Las LUCES no: preguntarle a Tuya cada 10 minutos gastaría su cupo
// de consultas (ver Tibio.kt).
// ─────────────────────────────────────────────────────────────────────────────

object Despertar {
    /** Todo lo que se muestra o suena, al día. Cada parte en su hilo y ninguna tira. */
    fun todo(c: Context) {
        val app = c.applicationContext
        try { Llamadas.revisar(app) } catch (e: Throwable) { }   // pizarra-63: si alguien está llamando, primero
        try { Charla.ponerAlDia(app) } catch (e: Throwable) { Bitacora.anotar(app, "despertar: mensajes: ${e.javaClass.simpleName}") }
        try { PizarraWidget.actualizarDesdeLaRed(app, null, conLuces = false) } catch (e: Throwable) { }
        try { AgendaWidget.actualizar(app, null) } catch (e: Throwable) { }
        try { Alarmas.sincronizar(app) } catch (e: Throwable) { }
        try { EnlaceLejos.ponerAlDia(app) } catch (e: Throwable) { }   // pizarra-71: lo que dejó la otra Pizarra por internet
    }
}
