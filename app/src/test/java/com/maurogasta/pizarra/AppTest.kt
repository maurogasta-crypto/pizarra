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
    @org.junit.Before fun sinRed() { Nube.sinRed = true }

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
        assertTrue(bs.any { it.text.contains("Seguir en Tiempos") })
        assertTrue("pizarra-12: sin botón de dictado", bs.none { it.text.contains("Dictar") })
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
            "startForeground" to "FOREGROUND_SERVICE",
            "FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK" to "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
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

    // Dictar abre el navegador; la alarma la guarda el sitio. Al volver a la
    // app tiene que ponerse al día, o una alarma cercana no suena.
    @Test fun alVolverALaApp_sePoneAlDía() {
        conSesion()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        c.pause().stop().restart().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Bitacora.ultimos(c.get()).any { it.contains("vuelve a la app") })
    }

    // 00:56 del 5-oct: la alarma «sonó y se cortó». La sirena es un servicio
    // que suena en bucle con el volumen de ALARMA hasta Apagar o 3 minutos.
    @Test fun laSirena_quedaSonandoHastaApagar() {
        val c = RuntimeEnvironment.getApplication()
        val s = Robolectric.buildService(SirenaServicio::class.java,
            android.content.Intent().putExtra("texto", "Dentista").putExtra("hora", "10:30")).create().startCommand(0, 1).get()
        val sh = shadowOf(s)
        assertTrue(Bitacora.ultimos(c).any { it.contains("sirena: suena") })
        assertTrue(sh.lastForegroundNotification != null && !sh.isStoppedBySelf)
        s.onStartCommand(android.content.Intent().setAction(ACCION_APAGAR), 0, 2)
        assertTrue(sh.isStoppedBySelf)
    }

    // «Compartir → Tiempos» desde Instagram: abre con el link en la caja y
    // pide la captura, sin cerrarse.
    @Test fun compartirUnReel_abreConElLinkEnLaCaja() {
        conSesion()
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, "https://www.instagram.com/reel/ABC/?igsh=zzz")
        val a = Robolectric.buildActivity(MainActivity::class.java, i).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        fun cajas(v: View): List<android.widget.EditText> = when (v) {
            is android.widget.EditText -> listOf(v)
            is ViewGroup -> (0 until v.childCount).flatMap { cajas(v.getChildAt(it)) }
            else -> emptyList()
        }
        val texto = cajas(a.window.decorView).first().text.toString()
        assertTrue(texto, texto.contains("Link (Instagram): https://www.instagram.com/reel/ABC/"))
        assertTrue(botones(a.window.decorView).any { it.text.contains("Captura") })
    }

    // tiempos:A13 — la alarma de las 7:26 del 5-oct: Android la demoró, Mauro
    // volvió a la app, la puesta al día vio que «ya pasó» y la CANCELÓ.
    // Ahora sólo se cancela lo que se borró de la base.
    @Test fun unaAlarmaQueYaPasóDeHora_noSeCancela_unaBorradaSí() {
        val c = RuntimeEnvironment.getApplication()
        val am = shadowOf(c.getSystemService(android.app.AlarmManager::class.java))
        val f = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val luego = java.time.LocalDateTime.now().plusMinutes(10)
        val antes = java.time.LocalDateTime.now().minusMinutes(10)
        val a = Alerta("a1", "alarma", "Gimnasio", luego.toLocalDate().toString(), luego.format(f))
        Alarmas.programar(c, listOf(a))
        assertTrue(am.scheduledAlarms.size == 1)
        // La misma alarma, ya pasada de hora (Android la demoró): sigue en la base.
        Alarmas.programar(c, listOf(a.copy(dia = antes.toLocalDate().toString(), hora = antes.format(f))))
        assertTrue("una alarma demorada no se cancela", am.scheduledAlarms.size == 1)
        // Se borró en Tiempos: ahora sí.
        Alarmas.programar(c, emptyList())
        assertTrue("una alarma borrada se cancela", am.scheduledAlarms.isEmpty())
    }

    @Test fun unaAlarmaQueLlega_quedaAnotadaEnElDiscoYSuena() {
        val c = RuntimeEnvironment.getApplication()
        AlarmaReceptor().onReceive(c, android.content.Intent(ACCION_ALERTA)
            .putExtra("id", "x").putExtra("tipo", "alarma").putExtra("texto", "Prueba").putExtra("hora", "07:26"))
        assertTrue(Bitacora.ultimos(c).any { it.contains("alerta RECIBIDA: alarma 07:26") })
        assertTrue(shadowOf(c).nextStartedService?.component?.className?.endsWith("SirenaServicio") == true)
    }

    // pizarra-12 (tiempos:V7): el ícono abre Tiempos ADENTRO; lo de afuera, al navegador.
    @Test fun tiempos_seAbreAdentro_conElSitioYNadaMás() {
        val a = Robolectric.buildActivity(TiemposActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        fun webs(v: View): List<android.webkit.WebView> = when (v) {
            is android.webkit.WebView -> listOf(v)
            is ViewGroup -> (0 until v.childCount).flatMap { webs(v.getChildAt(it)) }
            else -> emptyList()
        }
        val w = webs(a.window.decorView).single()
        assertTrue(shadowOf(w).lastLoadedUrl == TIEMPOS_URL)
        assertTrue(w.settings.javaScriptEnabled && w.settings.domStorageEnabled)
        assertTrue(w.settings.userAgentString.contains("PizarraApp/"))
        assertTrue(esDeTiempos(TIEMPOS_URL + "?dictar=hola") && !esDeTiempos("https://www.instagram.com/reel/x") && !esDeTiempos(null))
        val conUrl = android.content.Intent(RuntimeEnvironment.getApplication(), TiemposActivity::class.java).putExtra(EXTRA_URL, "https://evil.example/")
        val b = Robolectric.buildActivity(TiemposActivity::class.java, conUrl).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("una dirección de afuera no se carga adentro", shadowOf(webs(b.window.decorView).single()).lastLoadedUrl == TIEMPOS_URL)
        val m = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.indexOf(".TiemposActivity") < m.indexOf("android.intent.category.LAUNCHER") && m.indexOf("android.intent.category.LAUNCHER") < m.indexOf(".MainActivity"))
    }
}
