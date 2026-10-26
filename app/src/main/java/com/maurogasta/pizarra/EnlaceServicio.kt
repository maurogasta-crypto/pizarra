package com.maurogasta.pizarra

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// EnlaceServicio.kt — La escucha y el envío del enlace cerca. Sello: pizarra-71
// Ver Enlace.kt: qué es, por qué así y qué viaja.
//
// Mientras el enlace está prendido, este servicio:
//   · ESCUCHA por la red local (un puerto anunciado con NSD) y por Bluetooth
//     (un servicio con UUID_BT, sólo para emparejados);
//   · cuando tiene algo que mandar y no hay nadie conectado, BUSCA: la otra
//     Pizarra en la red local y, si no, entre los teléfonos emparejados;
//   · lo que llega lo muestra: un «vivo» en una notificación quieta, un
//     «evento» en una que suena, y la «voz» del handy cerca al parlante
//     (pizarra-71, Radio.kt). Eso es lo que despierta sin internet;
//   · si no hay nadie cerca y hay una cuenta de Tiempos, un evento va por
//     internet, por la base (pizarra-71, EnlaceLejos.kt), y el vivo también,
//     pero sólo mientras alguien lo mira. La voz no: es cerca o nada.
// Gasta poco: un puerto y un Bluetooth esperando. Buscar se hace sólo cuando
// hay algo para mandar, y como mucho cada BUSCAR_CADA_MS.
// ─────────────────────────────────────────────────────────────────────────────

const val CANAL_ENLACE = "enlace"
const val CANAL_ENLACE_EVENTOS = "enlace_eventos"
private const val ID_SERVICIO = 7070
private const val ID_VIVO = 7071
private const val BUSCAR_CADA_MS = 30_000L
private const val VIVO_SE_VA_MS = 30_000L
private const val PENDIENTES_MAX = 20
/** Lo que espera a que aparezca alguien: un vivo viejo o una voz de hace un rato no sirven. */
private val TIPOS_QUE_ESPERAN = setOf("evento", "prueba")

/** El enlace prendido o no, y el código. Vive en «enlace», fuera de las copias. */
object EnlaceAjustes {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("enlace", Context.MODE_PRIVATE)
    fun prendido(c: Context) = prefs(c).getBoolean("prendido", false)
    fun prender(c: Context, v: Boolean) = prefs(c).edit().putBoolean("prendido", v).apply()
    /** El código de este teléfono; si no hay, se crea. */
    fun codigo(c: Context): String = prefs(c).getString("codigo", null) ?: codigoNuevo().also { prefs(c).edit().putString("codigo", it).apply() }
    /** Usar el código de la otra Pizarra. `false` si no es un código. */
    fun usarCodigo(c: Context, t: String): Boolean {
        val n = normalizarCodigo(t) ?: return false
        prefs(c).edit().putString("codigo", n).apply(); return true
    }
    fun llave(c: Context) = llaveDe(codigo(c))
    /** pizarra-71: escuchar el handy cerca de la otra Pizarra (sí, salvo que se apague). */
    fun radio(c: Context) = prefs(c).getBoolean("radio", true)
    fun radioSiNo(c: Context, v: Boolean) = prefs(c).edit().putBoolean("radio", v).apply()
}

class EnlaceServicio : Service() {
    companion object {
        @Volatile private var activo: EnlaceServicio? = null
        /** Lo que se esperaba mandar antes de que el servicio estuviera listo. */
        private val antes = CopyOnWriteArrayList<JSONObject>()

        /** Lo que pasa ahora, en palabras, para la pantalla de ajustes. */
        @Volatile var estado: String = "apagado"; private set

        fun arrancar(c: Context) {
            if (!EnlaceAjustes.prendido(c)) return
            try { c.startForegroundService(Intent(c, EnlaceServicio::class.java)) }
            catch (e: Throwable) { Bitacora.anotar(c, "enlace: no arrancó (${e.javaClass.simpleName})") }
        }

        fun detener(c: Context) { c.stopService(Intent(c, EnlaceServicio::class.java)); estado = "apagado" }

        /** El último «vivo» que llegó cerca (para «🚙 En vivo»). */
        @Volatile var ultimoVivo: JSONObject? = null; private set

        /** Manda [o] a las Pizarras cerca. Si el servicio no está, lo arranca y lo manda cuando esté. */
        fun enviar(c: Context, o: JSONObject) {
            val s = activo
            if (s != null) { s.mandar(o); return }
            if (o.optString("tipo") in TIPOS_QUE_ESPERAN && antes.size < PENDIENTES_MAX) antes.add(o)
            arrancar(c)
        }

        /** Buscar a la otra Pizarra ya (al abrir la radio o «En vivo»), sin esperar a tener algo para mandar. */
        fun buscarYa(c: Context) { activo?.buscar(forzar = true) ?: arrancar(c) }
    }

    private class Conexion(val socket: java.io.Closeable, val entrada: InputStream, val salida: OutputStream, val por: String, val quien: String)

    private val conexiones = CopyOnWriteArrayList<Conexion>()
    private val pendientes = CopyOnWriteArrayList<JSONObject>()
    private val ultimoDe = ConcurrentHashMap<String, Long>()
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var vivo = true
    private var servidorTcp: ServerSocket? = null
    private var servidorBt: BluetoothServerSocket? = null
    private var nsd: NsdManager? = null
    private var registro: NsdManager.RegistrationListener? = null
    @Volatile private var nombrePropio: String? = null
    @Volatile private var ultimaBusqueda = 0L
    private var candado: WifiManager.MulticastLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanales(this)
        val n = Notification.Builder(this, CANAL_ENLACE).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("📡 Enlace cerca").setContentText("Escuchando por wi-fi y Bluetooth, sin datos (lejos, por internet)").setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            // pizarra-71: la radio y lo que manda el detector, a un toque desde la barra.
            .addAction(Notification.Action.Builder(null, "📻 Hablar",
                PendingIntent.getActivity(this, 7073, Intent(this, RadioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)).build())
            .addAction(Notification.Action.Builder(null, "🚙 En vivo",
                PendingIntent.getActivity(this, 7074, Intent(this, EnVivoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_SERVICIO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID_SERVICIO, n)
        } catch (e: Throwable) { Bitacora.anotar(this, "enlace: sin servicio (${e.javaClass.simpleName})"); stopSelf(); return }
        activo = this
        estado = "escuchando"
        pendientes.addAll(antes); antes.clear()
        thread(name = "enlace-tcp") { escucharRed() }
        thread(name = "enlace-bt") { escucharBluetooth() }
        Bitacora.anotar(this, "enlace: prendido")
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int = START_STICKY

    override fun onDestroy() {
        vivo = false
        activo = null
        estado = "apagado"
        try { registro?.let { nsd?.unregisterService(it) } } catch (e: Throwable) { }
        try { servidorTcp?.close() } catch (e: Throwable) { }
        try { servidorBt?.close() } catch (e: Throwable) { }
        for (c in conexiones) cerrar(c)
        try { candado?.release() } catch (e: Throwable) { }
        Bitacora.anotar(this, "enlace: apagado")
        super.onDestroy()
    }

    /* ── Escuchar ─────────────────────────────────────────────────────────── */

    private fun escucharRed() = try {
        val s = ServerSocket(0); servidorTcp = s
        val info = NsdServiceInfo().apply { serviceName = "Pizarra ${nombreDelTelefono(this@EnlaceServicio)}".take(60); serviceType = TIPO_NSD; port = s.localPort }
        val m = getSystemService(Context.NSD_SERVICE) as NsdManager; nsd = m
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { nombrePropio = i.serviceName }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Bitacora.anotar(this@EnlaceServicio, "enlace: la red local no anunció ($e)") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        registro = l
        m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        while (vivo) {
            val c = s.accept()
            agregar(Conexion(c, c.getInputStream(), c.getOutputStream(), "wi-fi", c.inetAddress.hostAddress ?: "?"))
        }
    } catch (e: Throwable) { if (vivo) Bitacora.anotar(this, "enlace: la red local se cortó (${e.javaClass.simpleName})"); Unit }

    private fun puedeBluetooth() = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")   // puedeBluetooth() lo comprueba antes de cada uso
    private fun escucharBluetooth() = try {
        val a = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (a == null || !puedeBluetooth() || !a.isEnabled) { Bitacora.anotar(this, "enlace: sin Bluetooth (apagado o sin permiso)") }
        else {
            val s = a.listenUsingRfcommWithServiceRecord("Pizarra", UUID.fromString(UUID_BT)); servidorBt = s
            while (vivo) {
                val c = s.accept()
                agregar(Conexion(c, c.inputStream, c.outputStream, "Bluetooth", c.remoteDevice?.name ?: "?"))
            }
        }
    } catch (e: Throwable) { if (vivo) Bitacora.anotar(this, "enlace: el Bluetooth se cortó (${e.javaClass.simpleName})"); Unit }

    private fun agregar(c: Conexion) {
        conexiones.add(c)
        estado = "conectado por ${c.por} con ${c.quien}"
        Bitacora.anotar(this, "enlace: $estado")
        thread(name = "enlace-lee") { leer(c) }
        // Lo que esperaba, sale ahora.
        val ya = pendientes.toList(); pendientes.clear()
        for (o in ya) mandar(o)
    }

    private fun leer(c: Conexion) {
        try {
            val r = BufferedReader(InputStreamReader(c.entrada, Charsets.UTF_8))
            while (vivo) {
                val renglon = r.readLine() ?: break
                val o = leerRenglon(EnlaceAjustes.llave(this), renglon, System.currentTimeMillis(), ultimoDe) ?: continue
                when (o.optString("tipo")) {
                    "voz" -> Altavoz.tocar(this, o)           // en este hilo: el sonido no espera
                    else -> { if (o.optString("tipo") == "vivo") ultimoVivo = o; principal.post { AvisosEnlace.mostrar(this, o) } }
                }
            }
        } catch (e: Throwable) { }
        cerrar(c)
    }

    private fun cerrar(c: Conexion) {
        conexiones.remove(c)
        try { c.socket.close() } catch (e: Throwable) { }
        if (conexiones.isEmpty() && activo != null) estado = "escuchando"
    }

    /* ── Mandar ───────────────────────────────────────────────────────────── */

    @Volatile private var ultimoT = 0L

    fun mandar(o: JSONObject) {
        if (!o.has("de")) o.put("de", nombreDelTelefono(this))
        if (!o.has("v")) o.put("v", VERSION_ENLACE)
        // El instante siempre AVANZA: la otra Pizarra tira lo que no avanza, y diez trozos de voz por segundo se pisarían.
        synchronized(this) { ultimoT = maxOf(System.currentTimeMillis(), ultimoT + 1); o.put("t", ultimoT) }
        val renglon = renglonFirmado(EnlaceAjustes.llave(this), o.toString()).toByteArray(Charsets.UTF_8)
        if (conexiones.isEmpty()) {
            // Nadie cerca: un evento va por internet si se puede (pizarra-71) y si no espera; el vivo,
            // por internet sólo si alguien mira; la voz se pierde (es en vivo).
            val tipo = o.optString("tipo")
            val porInternet = tipo != "voz" && EnlaceLejos.mandar(this, o)
            if (!porInternet && tipo in TIPOS_QUE_ESPERAN && pendientes.size < PENDIENTES_MAX) pendientes.add(o)
            buscar(); return
        }
        thread(name = "enlace-manda") {
            for (c in conexiones) try { synchronized(c) { c.salida.write(renglon); c.salida.flush() } } catch (e: Throwable) { cerrar(c) }
        }
    }

    /** La otra Pizarra: primero en la red local; si en 8 s no apareció, entre los emparejados. */
    fun buscar(forzar: Boolean = false) {
        val ahora = System.currentTimeMillis()
        if (conexiones.isNotEmpty() || (!forzar && ahora - ultimaBusqueda < BUSCAR_CADA_MS) || ahora - ultimaBusqueda < 8_000L) return
        ultimaBusqueda = ahora
        estado = "buscando"
        thread(name = "enlace-busca") {
            try {
                try { buscarEnLaRed() } catch (e: Throwable) { }
                Thread.sleep(8_000)
                if (conexiones.isEmpty()) try { buscarBluetooth() } catch (e: Throwable) { }
                if (conexiones.isEmpty() && activo != null) estado = "nadie cerca · escuchando"
            } catch (e: Throwable) { }   // un hilo nunca tira
        }
    }

    private fun buscarEnLaRed() {
        val m = nsd ?: return
        val w = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (candado == null) candado = w?.createMulticastLock("pizarra-enlace")?.apply { setReferenceCounted(false) }
        try { candado?.acquire() } catch (e: Throwable) { }
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName == nombrePropio || !i.serviceType.startsWith("_pizarra")) return
                @Suppress("DEPRECATION")
                m.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        @Suppress("DEPRECATION") val host = r.host ?: return
                        thread(name = "enlace-conecta") {
                            try {
                                val s = Socket(); s.connect(InetSocketAddress(host, r.port), 4_000)
                                agregar(Conexion(s, s.getInputStream(), s.getOutputStream(), "wi-fi", r.serviceName))
                            } catch (e: Throwable) { }
                        }
                    }
                })
            }
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) { try { candado?.release() } catch (e: Throwable) { } }
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        m.discoverServices(TIPO_NSD, NsdManager.PROTOCOL_DNS_SD, l)
        principal.postDelayed({ try { m.stopServiceDiscovery(l) } catch (e: Throwable) { } }, 7_000)
    }

    @SuppressLint("MissingPermission")   // puedeBluetooth() lo comprueba antes
    private fun buscarBluetooth() {
        val a = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        if (!puedeBluetooth() || !a.isEnabled) return
        for (d in a.bondedDevices.orEmpty()) {
            if (conexiones.isNotEmpty() || !vivo) return
            var s: BluetoothSocket? = null
            try {
                s = d.createRfcommSocketToServiceRecord(UUID.fromString(UUID_BT))
                s.connect()
                agregar(Conexion(s, s.inputStream, s.outputStream, "Bluetooth", d.name ?: "?"))
            } catch (e: Throwable) { try { s?.close() } catch (x: Throwable) { } }
        }
    }

}

/** Lo que llega de la otra Pizarra, cerca o por internet: un vivo quieto, un evento que suena. */
object AvisosEnlace {
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var nm: NotificationManager? = null
    private val sacarVivo = Runnable { try { nm?.cancel(ID_VIVO) } catch (e: Throwable) { } }

    fun mostrar(c: Context, o: JSONObject) {
        try {
            crearCanales(c)
            val m = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm = m
            if (o.optString("tipo") == "vivo") {
                val enVivo = PendingIntent.getActivity(c, 7071, Intent(c, EnVivoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
                m.notify(ID_VIVO, Notification.Builder(c, CANAL_ENLACE).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle(textoVivo(o)).setContentText("En vivo · desde ${o.optString("de")}").setOngoing(true)
                    .setOnlyAlertOnce(true).setContentIntent(enVivo).build())
                principal.removeCallbacks(sacarVivo); principal.postDelayed(sacarVivo, VIVO_SE_VA_MS)
                return
            }
            val abrir = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val (titulo, cuerpo) = textoEvento(o)
            m.notify(("enlace:" + o.optLong("t")).hashCode(), Notification.Builder(c, if (suena(o)) CANAL_ENLACE_EVENTOS else CANAL_ENLACE)
                .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(titulo).setContentText(cuerpo.lineSequence().first())
                .setStyle(Notification.BigTextStyle().bigText(cuerpo)).setAutoCancel(true).setContentIntent(abrir).build())
            Bitacora.anotar(c, "enlace: llegó un ${o.optString("tipo")} de ${o.optString("app")} desde ${o.optString("de")}${if (o.optString("por") == "internet") " (por internet)" else ""}")
        } catch (e: Throwable) { }
    }
}

fun crearCanales(c: Context) {
    val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(NotificationChannel(CANAL_ENLACE, "Enlace cerca (en vivo)", NotificationManager.IMPORTANCE_LOW))
    nm.createNotificationChannel(NotificationChannel(CANAL_ENLACE_EVENTOS, "Enlace cerca · lo que pasa", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Lo que manda la otra Pizarra cerca: un viaje que terminó, algo raro en la camioneta. Suena aunque no haya internet."
    })
}
