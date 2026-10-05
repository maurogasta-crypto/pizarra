package com.maurogasta.pizarra

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// La app abierta de verdad, sin teléfono (Robolectric). Existe porque
// pizarra-2 pasó el banco y compiló, y en el teléfono de Mauro se trababa
// al abrir: el banco probaba la lógica, nadie abría la pantalla.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppTest {
    private fun conSesion() {
        val c = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("sesion", Context.MODE_PRIVATE).edit()
            .putString("refresh", "x").putString("uid", "uidPrueba").putString("mail", "a@b.c").commit()
    }

    private fun botones(v: View): List<Button> = when (v) {
        is Button -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { botones(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun abreSinSesion() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(botones(a.window.decorView).any { it.text == "Entrar" })
    }

    @Test fun abreConSesion_yLosBotonesResponden() {
        conSesion()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val bs = botones(a.window.decorView)
        println("botones: " + bs.map { it.text })
        assertTrue(bs.any { it.text.contains("Dictar") })
        for (b in bs.filter { it.text != "Salir" && it.text != "Poner el widget" }) {
            b.performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // pizarra-3 se cerraba al abrir en el teléfono: el trabajo «con red» exige
    // ACCESS_NETWORK_STATE, y Robolectric no lo exige. Lo que pide Android y el
    // simulador no controla, se controla leyendo el manifiesto.
    @Test fun cadaPermisoQueElCódigoNecesita_estáEnElManifiesto() {
        val m = java.io.File("src/main/AndroidManifest.xml").readText()
        val codigo = java.io.File("src/main/java/com/maurogasta/pizarra").listFiles()!!.joinToString("\n") { it.readText() }
        val exige = mapOf(
            "setRequiredNetworkType" to "ACCESS_NETWORK_STATE",
            "setPersisted(true)" to "RECEIVE_BOOT_COMPLETED",
            "setAlarmClock" to "USE_EXACT_ALARM",
            "setFullScreenIntent" to "USE_FULL_SCREEN_INTENT",
            "POST_NOTIFICATIONS" to "POST_NOTIFICATIONS",
            "openConnection" to "INTERNET",
        )
        for ((uso, permiso) in exige) if (codigo.contains(uso))
            assertTrue("$uso necesita $permiso en el manifiesto", m.contains("android.permission.$permiso\""))
    }

    @Test fun abrirLaApp_programaElTrabajoDeCadaHora_sinCerrarse() {
        conSesion()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val js = a.getSystemService(android.app.job.JobScheduler::class.java)
        assertTrue(js.allPendingJobs.isNotEmpty())
    }
}
