package com.maurogasta.pizarra

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout

// ─────────────────────────────────────────────────────────────────────────────
// TiemposActivity.kt — Tiempos ADENTRO de la app. Sello: pizarra-13
//
// 7-oct-2026, tiempos:V7 (Mauro): «no es práctico dos aplicaciones para hacer
// lo mismo». La app muestra el sitio de Tiempos en su propia pantalla (sin
// navegador) y conserva lo que sólo puede hacer una app: el widget, las
// alarmas, la lectura de Airbnb y Compartir. La lógica sigue siendo UNA, la del
// sitio: nada de Tiempos se copia acá.
//
// Lo que se abre adentro es SÓLO el sitio de Tiempos; cualquier otro enlace
// (Instagram, Casa Verde) va al navegador. El ⚙ de abajo a la izquierda lleva
// a los ajustes de la app (MainActivity): la cuenta del widget, la bodega, el
// diagnóstico.
// ─────────────────────────────────────────────────────────────────────────────

const val EXTRA_URL = "url"

/** Lo que se queda adentro: el sitio de Tiempos y nada más. */
fun esDeTiempos(url: String?): Boolean = url != null && url.startsWith(TIEMPOS_URL)

class TiemposActivity : Activity() {
    private lateinit var web: WebView
    private var archivos: ValueCallback<Array<Uri>>? = null
    // pizarra-13: la ubicación APROXIMADA, sólo para el sitio de Tiempos (de
    // qué país sale la alarma de salir: lugares.js). Se pide al usarla.
    private var ubicacion: Pair<String, GeolocationPermissions.Callback>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        Bitacora.anotar(this, "tiempos: abre adentro")
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#14161a"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true          // la sesión de Firebase vive en IndexedDB
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString + " PizarraApp/$SELLO"
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = desviar(r.url.toString())
            }
            settings.setGeolocationEnabled(true)
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origen: String, cb: GeolocationPermissions.Callback) {
                    if (!esDeTiempos(origen) && !TIEMPOS_URL.startsWith(origen)) { cb.invoke(origen, false, false); return }
                    if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        cb.invoke(origen, true, false); return
                    }
                    ubicacion = origen to cb
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), PEDIDO_UBICACION)
                }
                // <input type="file">: el flyer, la boleta, la captura.
                override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                    archivos?.onReceiveValue(null)
                    archivos = cb
                    return try { startActivityForResult(p.createIntent(), PEDIDO_ARCHIVO); true }
                    catch (e: ActivityNotFoundException) { archivos = null; false }
                }
            }
        }
        val marco = FrameLayout(this)
        marco.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val d = resources.displayMetrics.density
        marco.addView(Button(this).apply {
            text = "⚙"
            textSize = 18f
            alpha = 0.7f
            contentDescription = "Ajustes de la app"
            setOnClickListener { startActivity(Intent(this@TiemposActivity, MainActivity::class.java)) }
        }, FrameLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt(), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = (10 * d).toInt(); bottomMargin = (14 * d).toInt()
        })
        // Desde Android 15 la app se dibuja debajo de las barras: el sitio no
        // tiene que quedar abajo de la hora ni de los botones del sistema.
        if (Build.VERSION.SDK_INT >= 30) marco.setOnApplyWindowInsetsListener { v, ins ->
            val m = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            v.setPadding(m.left, m.top, m.right, m.bottom)
            ins
        }
        setContentView(marco)
        if (estado != null) web.restoreState(estado)
        if (web.url == null) web.loadUrl(urlPedida(intent))
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        i.getStringExtra(EXTRA_URL)?.let { if (esDeTiempos(it)) web.loadUrl(it) }
    }

    private fun urlPedida(i: Intent?): String = i?.getStringExtra(EXTRA_URL)?.takeIf { esDeTiempos(it) } ?: TIEMPOS_URL

    /** true = lo manejo yo (afuera); false = se carga adentro. */
    private fun desviar(url: String): Boolean {
        if (esDeTiempos(url)) return false
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
        return true
    }

    @Deprecated("Activity sin AndroidX")
    override fun onActivityResult(pedido: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(pedido, resultado, datos)
        if (pedido != PEDIDO_ARCHIVO) return
        archivos?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultado, datos))
        archivos = null
    }

    override fun onRequestPermissionsResult(pedido: Int, permisos: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(pedido, permisos, resultados)
        if (pedido != PEDIDO_UBICACION) return
        val (origen, cb) = ubicacion ?: return
        ubicacion = null
        val si = resultados.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Bitacora.anotar(this, "tiempos: ubicación aproximada " + if (si) "permitida" else "negada")
        cb.invoke(origen, si, false)
    }

    @Deprecated("Activity sin AndroidX")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onSaveInstanceState(s: Bundle) { super.onSaveInstanceState(s); web.saveState(s) }

    companion object { private const val PEDIDO_ARCHIVO = 41; private const val PEDIDO_UBICACION = 42 }
}
