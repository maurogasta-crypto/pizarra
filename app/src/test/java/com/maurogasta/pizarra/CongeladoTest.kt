package com.maurogasta.pizarra

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/* pizarra-83: lo congelado del plan «Timón» (§ 5 de ARQUITECTURA.md de datos)
   viene APAGADO por defecto, y el sello de la app es UNO (Bitacora.SELLO =
   versionName): hasta pizarra-82 eran dos números sueltos y divergieron. */
@RunWith(RobolectricTestRunner::class)
class CongeladoTest {
    private val c: Context get() = RuntimeEnvironment.getApplication()

    @Test fun loCongeladoVieneApagado() {
        assertFalse("las llamadas nacen apagadas", Congelado.llamadas(c))
        assertFalse("el lector nace apagado", Congelado.lector(c))
        assertFalse("el enlace cerca nace apagado", EnlaceAjustes.prendido(c))
    }

    @Test fun elInterruptorQueda() {
        Congelado.llamadas(c, true)
        assertTrue(Congelado.llamadas(c))
        Congelado.llamadas(c, false)
        assertFalse(Congelado.llamadas(c))
    }
}

class SelloTest {
    @Test fun elSelloDeBitacoraEsElVersionName() {
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.exists() }.readText()
        val v = Regex("versionName = \"(pizarra-\\d+)\"").find(gradle)?.groupValues?.get(1)
        assertEquals("SELLO de Bitacora.kt tiene que ser el versionName", v, SELLO)
    }
}
