package com.maurogasta.pizarra

import android.content.Context
import android.os.Handler
import android.os.Looper

// ─────────────────────────────────────────────────────────────────────────────
// Despertar.kt — Ponerse al día, POR PARTES. Sello: pizarra-74
//
// Mauro, 24-oct-2026: «renovar todas las pizarras en cada modificación, además
// de una vez por hora»; y después: «los mensajes en el ecosistema tienen que
// usar ese canal para notificar despertando el dispositivo». Es el UNO lugar
// que se llama cuando hay que ponerse al día.
//
// pizarra-74 (28-oct-2026, tiempos:A42): el 27-oct la base de Tiempos se quedó
// SIN CUPO de lecturas (50.000 por día, Firebase Spark). Hasta pizarra-73 cada
// aviso —un mensaje, una tarea tachada, una prueba del enlace— hacía que CADA
// Pizarra de la base bajara TODO: las tareas enteras (dos veces: la pizarra y
// la agenda), la agenda, las alarmas, los avisos, la charla y el enlace. Y
// cada 10 minutos, lo mismo. Eso crece con (tareas × teléfonos × avisos) y no
// entra en el cupo con 10 personas. Desde acá:
//   · el aviso trae SÓLO la parte que cambió (`partesDe(que)`): un mensaje
//     trae la charla; una tarea, la pizarra y la agenda; una alerta, las
//     alarmas y la agenda. El «todo» del enlace trae el enlace y la charla;
//   · una misma parte no se trae dos veces en `ESPACIO_MS`: una ráfaga de
//     avisos se junta en una vuelta más al final (`demora`), así no se pierde
//     el último cambio;
//   · cada 10 minutos (de 8 a 23, `Charla.agendar`) sólo lo barato —la charla
//     desde lo último, el enlace y las llamadas: una lectura cada uno si no hay
//     nada—, y TODO cada `TODO_CADA_MS` como red por si se perdió un aviso.
// CUPO (Spark: 50.000 lecturas por día por base), con unas 150 tareas. Traer
// todo son ~200 lecturas (las tareas, 150; la agenda y las alarmas, ~45; lo
// demás, unas pocas); una tarea que cambia, ~170 (tareas y agenda juntas,
// `conTareasCompartidas`); un mensaje, ~1. Por teléfono y por día: lo barato
// cada 10 min, 3 × 90 = 270; la red de seguridad, ~3 × 200 = 600. Con 10
// teléfonos: ~9.000, más ~1.700 por cada tarea que cambia (20 por día:
// ~34.000). Unas 43.000: entra, justo. Hoy son 4 teléfonos (~17.000). Si se
// acerca, lo que sigue es traer sólo las tareas cambiadas (anotado en tiempos:A42).
// Las LUCES no: preguntarle a Tuya cada 10 minutos gastaría su cupo de
// consultas (ver Tibio.kt).
// ─────────────────────────────────────────────────────────────────────────────

const val ESPACIO_MS = 20_000L
const val TODO_CADA_MS = 6 * 3600_000L

/** Las partes que se pueden poner al día por separado. */
val PARTES = listOf("llamadas", "mensajes", "tareas", "agenda", "alarmas", "enlace")
private val BARATAS = setOf("llamadas", "mensajes", "enlace")

/** Qué partes trae un aviso, según lo que cambió (`que` de despertar.mjs). Lo desconocido trae todo. */
fun partesDe(que: String?): Set<String> = when (que) {
    "llamadas" -> setOf("llamadas")
    "mensajes" -> setOf("mensajes")
    "tareas" -> setOf("tareas", "agenda")
    "agenda" -> setOf("agenda")
    "alertas" -> setOf("alarmas", "agenda")
    "avisos", "plata" -> setOf("alarmas", "mensajes")     // los avisos de Claude viven en el buzón (Alarmas) y en las novedades
    "reservas", "pedidos" -> setOf("mensajes")            // lo de Casa Verde se cuenta en las novedades
    "todo" -> setOf("enlace", "mensajes")                 // lo usa el enlace lejos (pizarra-71)
    else -> PARTES.toSet()
}

/** Lo de cada 10 minutos: lo barato siempre, y todo si pasaron `TODO_CADA_MS` desde la última vez. */
fun partesPeriodicas(ahora: Long, ultimoTodo: Long): Set<String> =
    if (ahora - ultimoTodo >= TODO_CADA_MS) PARTES.toSet() else BARATAS

/** 0: traerla ya. Más de 0: se trajo hace poco; volver a traerla dentro de tanto. */
fun demora(ahora: Long, ultima: Long?): Long =
    if (ultima == null || ahora - ultima >= ESPACIO_MS) 0L else ESPACIO_MS - (ahora - ultima)

object Despertar {
    private val ultima = HashMap<String, Long>()
    private val agendadas = HashSet<String>()
    private val principal by lazy { Handler(Looper.getMainLooper()) }

    /** Todo lo que se muestra o suena, al día. */
    fun todo(c: Context) = partes(c, PARTES.toSet())

    /** Lo que trae el aviso instantáneo. */
    fun por(c: Context, que: String?) = partes(c, partesDe(que))

    /** Cada 10 minutos (CharlaReceptor). */
    fun periodico(c: Context) {
        val p = c.applicationContext.getSharedPreferences("despertar", Context.MODE_PRIVATE)
        val ahora = System.currentTimeMillis()
        val cuales = partesPeriodicas(ahora, p.getLong("todo", 0L))
        if (cuales.size == PARTES.size) p.edit().putLong("todo", ahora).apply()
        partes(c, cuales)
        // pizarra-78: el parte de salud, una vez por hora (Salud.kt).
        kotlin.concurrent.thread(name = "salud") { try { Salud.subir(c.applicationContext) } catch (e: Throwable) { } }
    }

    /** Cada parte en su hilo y ninguna tira. Las que se trajeron hace poco esperan su turno, una sola vez. */
    fun partes(c: Context, cuales: Set<String>) {
        val app = c.applicationContext
        val ahora = System.currentTimeMillis()
        val ya = mutableSetOf<String>()
        synchronized(this) {
            for (parte in cuales) {
                val d = demora(ahora, ultima[parte])
                if (d == 0L) { ultima[parte] = ahora; ya += parte }
                else if (agendadas.add(parte)) principal.postDelayed({
                    synchronized(this) { agendadas.remove(parte) }
                    partes(app, setOf(parte))
                }, d)
            }
        }
        if ("llamadas" in ya) try { Llamadas.revisar(app) } catch (e: Throwable) { }   // pizarra-63: si alguien está llamando, primero
        if ("mensajes" in ya) try { Charla.ponerAlDia(app) } catch (e: Throwable) { Bitacora.anotar(app, "despertar: mensajes: ${e.javaClass.simpleName}") }
        // La pizarra y la agenda leen las mismas tareas: juntas, se piden una vez.
        if ("tareas" in ya && "agenda" in ya) try { conTareasCompartidas(app) } catch (e: Throwable) { }
        else {
            if ("tareas" in ya) try { PizarraWidget.actualizarDesdeLaRed(app, null, conLuces = false) } catch (e: Throwable) { }
            if ("agenda" in ya) try { AgendaWidget.actualizar(app, null) } catch (e: Throwable) { }
        }
        if ("alarmas" in ya) try { Alarmas.sincronizar(app) } catch (e: Throwable) { }
        if ("enlace" in ya) try { EnlaceLejos.ponerAlDia(app) } catch (e: Throwable) { }   // pizarra-71: lo que dejó la otra Pizarra por internet
    }

    private fun conTareasCompartidas(app: Context) {
        kotlin.concurrent.thread {
            val tareas = try { Nube(app).let { if (it.conSesion && it.esTiempos) it.tareas() else null } } catch (e: Throwable) { null }
            try { PizarraWidget.actualizarDesdeLaRed(app, null, conLuces = false, tareas = tareas) } catch (e: Throwable) { }
            try { AgendaWidget.actualizar(app, null, tareas = tareas) } catch (e: Throwable) { }
        }
    }
}
