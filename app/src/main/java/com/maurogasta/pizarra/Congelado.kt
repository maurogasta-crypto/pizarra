package com.maurogasta.pizarra

import android.content.Context

// ─────────────────────────────────────────────────────────────────────────────
// Congelado.kt — Lo CONGELADO del plan «Timón» (§ 5 de ARQUITECTURA.md de
// `datos`), apagado POR DEFECTO desde pizarra-83 (15-nov-2026; Mauro, 10-nov:
// «apagados por defecto, sin borrar», hasta que lo central ande).
//
//  · El enlace cerca (el handy por wi-fi y Bluetooth) ya nacía apagado:
//    EnlaceAjustes.prendido arranca en false y tiene su casilla.
//  · Las LLAMADAS entre Pizarras: sin prenderlas, una entrante no suena (la
//    llamada queda en la base y quien llamó la cancela solo: nada se pierde)
//    y una saliente no arranca, diciendo por qué.
//  · El LECTOR de Facebook por la pantalla: aunque el servicio de
//    accesibilidad esté dado de alta en Android, sin prenderlo acá no se lee
//    ni se registra nada.
//
// Nada se borra: cada cosa tiene su casilla a la vista en la pantalla del
// enlace. El interruptor es POR TELÉFONO y una actualización no lo toca:
// quien lo prendió una vez lo tiene prendido.
// ─────────────────────────────────────────────────────────────────────────────
object Congelado {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("congelado", Context.MODE_PRIVATE)

    fun llamadas(c: Context): Boolean = p(c).getBoolean("llamadas", false)
    fun llamadas(c: Context, v: Boolean) { p(c).edit().putBoolean("llamadas", v).apply() }

    fun lector(c: Context): Boolean = p(c).getBoolean("lector", false)
    fun lector(c: Context, v: Boolean) { p(c).edit().putBoolean("lector", v).apply() }
}
