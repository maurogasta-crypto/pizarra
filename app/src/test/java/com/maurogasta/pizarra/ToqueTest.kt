package com.maurogasta.pizarra

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// pizarra-23: en la pizarra el ☐ tacha y el texto abre la tarea. Lo que no
// tiene que pasar: que tocar el texto tache, o que tocar el ☐ abra algo.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToqueTest {
    private fun tocar(extras: Intent.() -> Unit) =
        Robolectric.buildActivity(ToqueActivity::class.java, Intent(RuntimeEnvironment.getApplication(), ToqueActivity::class.java).apply(extras)).setup().get()

    @Test fun elCuadritoTachaYNoAbreNada() {
        val a = tocar { putExtra("que", "tachar"); putExtra("id", "t1"); putExtra("hecho", true) }
        val b = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last()
        assertEquals(ACCION_TACHAR, b.action)
        assertEquals("t1", b.getStringExtra("id"))
        assertTrue(b.getBooleanExtra("hecho", false))
        assertEquals(null, shadowOf(a).nextStartedActivity)
        assertTrue(a.isFinishing)
    }

    @Test fun elTextoAbreYNoTacha() {
        val antes = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.size
        val a = tocar { putExtra("que", "abrir"); putExtra("id", "t1") }
        val abierta = shadowOf(a).nextStartedActivity
        // Sin sesión en el teléfono del banco: lleva a entrar.
        assertEquals(MainActivity::class.java.name, abierta.component?.className)
        assertEquals(antes, shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.size)
        assertTrue(a.isFinishing)
    }

    @Test fun laDireccionDeLaTarea() {
        assertEquals("https://maurogasta-crypto.github.io/tiempos/?solapa=tareas&abrir=f%3At1", urlTarea("t1"))
        assertTrue(esDeTiempos(urlTarea("x")))
    }
}
