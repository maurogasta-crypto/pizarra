package com.maurogasta.pizarra

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build

// ─────────────────────────────────────────────────────────────────────────────
// EnlaceProveedor.kt — La puerta local: otra app del MISMO teléfono le pasa a la
// Pizarra lo que quiere mandar por el enlace cerca. Sello: pizarra-70
//
// Hoy la usa sólo la app de la Hilux (general:C4, paso 1): el Note 9 de la
// cabina le pasa el estado del viaje cada 2 s y lo que pasa. Es un proveedor
// y no un servicio ligado porque del otro lado alcanza con UNA línea
// (`contentResolver.insert`), sin atar ni soltar nada.
//
// **Sólo atiende a la app de la Hilux, por su paquete Y por la huella de su
// firma** (`HUELLA_HILUX`). Sin lo segundo, cualquier app del teléfono que se
// llamara igual podría mandarle «falla grave» a tu Pizarra. Lo que llega se lee
// con desconfianza (`mensajeDeApp`): tipos conocidos, textos cortos, y nunca
// coordenadas. No lee nada ni deja leer nada: sólo `insert`.
// ─────────────────────────────────────────────────────────────────────────────

const val AUTORIDAD_ENLACE = "com.maurogasta.pizarra.enlace"

class EnlaceProveedor : ContentProvider() {
    override fun onCreate() = true

    override fun insert(uri: Uri, valores: ContentValues?): Uri? {
        val c = context ?: return null
        val quien = callingPackage
        if (quien != PAQUETE_HILUX || !firmaDe(quien)) {
            Bitacora.anotar(c, "enlace: la puerta rechazó a ${quien ?: "alguien sin nombre"}")
            return null
        }
        if (!EnlaceAjustes.prendido(c)) return null
        val o = mensajeDeApp("hilux", valores?.getAsString("mensaje").orEmpty(), nombreDelTelefono(c), System.currentTimeMillis()) ?: return null
        EnlaceServicio.enviar(c, o)
        return uri
    }

    /** Si [paquete] está firmado con la clave propia de la Hilux. */
    private fun firmaDe(paquete: String): Boolean = try {
        val pm = context!!.packageManager
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(paquete, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (info == null) emptyList() else if (info.hasMultipleSigners()) info.apkContentsSigners.toList() else info.signingCertificateHistory.toList()
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(paquete, PackageManager.GET_SIGNATURES).signatures.orEmpty().toList()
        }
        certs.any { huellaDe(it.toByteArray()) == HUELLA_HILUX }
    } catch (e: Throwable) { false }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
}
