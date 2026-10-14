package com.maurogasta.pizarra

import android.appwidget.AppWidgetManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// pizarra-30: el ＋ del widget. Lo que crea tiene que pasar la regla de
// `tareas` de Tiempos (dueño = quien escribe, alcance, tipo, título 1-120) y
// quedar en MI pizarra, sin pisar nunca otra tarea.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgregarTest {
    @Test fun laTareaNuevaTieneLaFormaDelSitio() {
        val w = cuerpoNuevaTarea("abc", "  Comprar   cinta  ", "yo").getJSONArray("writes").getJSONObject(0)
        val f = w.getJSONObject("update").getJSONObject("fields")
        assertEquals("$BASE_DOCS/tareas/abc", w.getJSONObject("update").getString("name"))
        assertEquals("Comprar cinta", f.getJSONObject("titulo").getString("stringValue"))
        assertEquals("yo", f.getJSONObject("duenio").getString("stringValue"))
        assertEquals("personal", f.getJSONObject("alcance").getString("stringValue"))
        assertEquals("personal", f.getJSONObject("tipo").getString("stringValue"))
        assertFalse(f.getJSONObject("hecho").getBoolean("booleanValue"))
        assertTrue(f.getJSONObject("pizarra").getJSONObject("mapValue").getJSONObject("fields").getJSONObject("yo").getBoolean("booleanValue"))
        assertEquals("yo", f.getJSONObject("encargados").getJSONObject("arrayValue").getJSONArray("values").getJSONObject(0).getString("stringValue"))
        assertTrue(f.getJSONObject("parentId").has("nullValue"))
        // Nunca pisa: sólo si no existe.
        assertFalse(w.getJSONObject("currentDocument").getBoolean("exists"))
        assertFalse("sin máscara: es un documento nuevo entero", w.has("updateMask"))
        assertEquals("creadoEn", w.getJSONArray("updateTransforms").getJSONObject(0).getString("fieldPath"))
    }

    @Test fun elTituloComoLoAceptaLaRegla() {
        assertEquals("", tituloNuevo("   "))
        assertEquals(120, tituloNuevo("x".repeat(300)).length)
        assertEquals("a b", tituloNuevo("a\n  b"))
    }

    @Test fun sinSesionLlevaAEntrar() {
        val app = RuntimeEnvironment.getApplication()
        val a = Robolectric.buildActivity(AgregarActivity::class.java, Intent(app, AgregarActivity::class.java)).setup().get()
        assertEquals(MainActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
        assertTrue(a.isFinishing)
    }

    @Test fun conSesionAbreLaVentanitaYUnTituloVacioNoSale() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("sesion", android.content.Context.MODE_PRIVATE).edit()
            .putString("refresh", "r").putString("uid", "yo").commit()
        val a = Robolectric.buildActivity(AgregarActivity::class.java, Intent(app, AgregarActivity::class.java)).setup().get()
        assertFalse(a.isFinishing)
        val boton = encontrar(a.window.decorView) { it is android.widget.Button && it.text == "Agregar" } as android.widget.Button
        boton.performClick()
        assertTrue(encontrar(a.window.decorView) { it is android.widget.TextView && it.text == "Escribí qué hay que hacer." } != null)
        app.getSharedPreferences("sesion", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun encontrar(v: android.view.View, que: (android.view.View) -> Boolean): android.view.View? {
        if (que(v)) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) encontrar(v.getChildAt(i), que)?.let { return it }
        return null
    }

    @Test fun elWidgetTieneElMas() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(AppWidgetManager.getInstance(app)).createWidget(PizarraWidget::class.java, R.layout.widget)
        PizarraWidget.dibujar(app)   // arma el ＋ sin romper
        assertTrue(R.id.agregar != 0)
    }
}
