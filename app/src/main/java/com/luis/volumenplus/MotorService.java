package com.luis.volumenplus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Random;
import java.util.List;
import java.util.Map;

/**
 * Motor de Volumen+: reproductor de musica + cadena de sonido.
 *  - Reproduce la lista del usuario con la cadena profesional (ecualizador, compresor, limitador, potencia).
 *  - Si se activa "todo el telefono", aplica la misma cadena a la salida general y a los reproductores
 *    que anuncian su sesion de audio (Spotify, reproductores de musica, etc.).
 */
public class MotorService extends Service {

    public static final String A_INICIAR = "pro.INICIAR";
    public static final String A_APLICAR = "pro.APLICAR";
    public static final String A_TOCAR = "pro.TOCAR";        // extra "i" = indice
    public static final String A_PAUSA = "pro.PAUSA";        // alterna reproducir / pausar
    public static final String A_SIG = "pro.SIG";
    public static final String A_ANT = "pro.ANT";
    public static final String A_IR = "pro.IR";              // extra "ms"
    public static final String A_APAGAR = "pro.APAGAR";

    private static final String TAG = "VolumenPlus";
    private static final String CANAL = "motor";
    private static final int NOTIF = 7;

    /** Estado que la pantalla lee (todo en el hilo principal). */
    public static volatile MotorService yo;

    private Ajustes aj;
    private MediaPlayer mp;
    private boolean preparado = false;
    private boolean sonando = false;
    private String titulo = "";
    private Cadena cadenaPropia;
    private Cadena cadenaGlobal;
    private final Map<Integer, Cadena> otras = new HashMap<>();
    private boolean receptor = false;
    private boolean receptorRuido = false;
    private AudioManager am;
    private AudioFocusRequest foco;
    private MediaSession ms;
    private String motorTxt = "";

    private final BroadcastReceiver sesiones = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int s = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
            if (s <= 0 || (mp != null && s == mp.getAudioSessionId())) return;
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(intent.getAction())) {
                if (aj.global() && !otras.containsKey(s)) {
                    Cadena c = new Cadena(s, false);
                    if (c.funciona()) { otras.put(s, c); aplicarA(c); } else c.liberar();
                }
            } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(intent.getAction())) {
                Cadena c = otras.remove(s);
                if (c != null) c.liberar();
            }
        }
    };

    // Audifonos desconectados: pausar para que no suene de golpe por el parlante
    private final BroadcastReceiver ruido = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (sonando) pausar();
        }
    };

    private boolean pausaPorOtro = false;
    private final AudioManager.OnAudioFocusChangeListener cambioFoco = cambio -> {
        if (cambio == AudioManager.AUDIOFOCUS_LOSS) {
            pausaPorOtro = false;
            if (sonando) pausar();
        } else if (cambio == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // llamada, asistente o audio de otra app por un momento: se pausa y luego vuelve sola
            if (sonando) { pausaPorOtro = true; pausar(); }
        } else if (cambio == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            volFoco = 0.3f; ponerVolumen(); // notificacion: baja un momento, no se corta
        } else if (cambio == AudioManager.AUDIOFOCUS_GAIN) {
            volFoco = 1f; ponerVolumen();
            if (pausaPorOtro) { pausaPorOtro = false; reanudar(); }
        }
    };

    // ---------- conexion estable para escuchar por internet ----------
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private WifiManager.WifiLock wifi;
    private boolean esInternet = false, cargando = false, reintentando = false;
    private int reintentos = 0, volverA = 0, ultimaPos = 0, duracionConocida = 0;
    /** Reserva de la cancion que suena (version 2.0.20): se descarga entera mientras suena, sin cortes. */
    private Bufer bufer;
    private int turno = 0;
    private boolean precargada = false;

    /** Mantiene el Wi-Fi despierto con la pantalla apagada (sin esto el Wi-Fi ahorra energia y la musica se corta). */
    private void tomarWifi() {
        try {
            if (wifi == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm == null) return;
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VolumenPlus:musica");
                wifi.setReferenceCounted(false);
            }
            if (!wifi.isHeld()) wifi.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "Sin bloqueo de Wi-Fi", t);
        }
    }

    private void soltarWifi() {
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Throwable ignored) { }
    }

    /** Lleva la cuenta de por donde va la cancion aunque la pantalla este apagada. */
    private final Runnable seguir = new Runnable() {
        @Override public void run() {
            try { if (preparado && mp.isPlaying()) { int p = mp.getCurrentPosition(); if (p > 0) ultimaPos = p; } } catch (Exception ignored) { }
            // cuando la cancion actual ya esta guardada entera, se precarga la siguiente
            if (!precargada && bufer != null && bufer.completo()) {
                precargada = true;
                String sig = uriSiguiente();
                Bufer.soloEstas(bufer.uri, sig);
                if (sig != null) Bufer.precargar(MotorService.this, sig);
            }
            if (sonando) h.postDelayed(this, 1000);
        }
    };

    /** Corte de senal: vuelve a abrir la misma cancion desde donde iba. */
    private void reintentar() {
        reintentos++;
        volverA = duracionConocida > 0 ? Math.max(0, ultimaPos - 2000) : 0; // en una emisora en vivo no se adelanta
        reintentando = true;
        cargando = true;
        final int i = aj.actual();
        h.postDelayed(() -> tocar(i), 1000L * reintentos);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try { ((AudioManager) getSystemService(AUDIO_SERVICE)).registerAudioDeviceCallback(cambioEquipo, null); } catch (Throwable ignored) { }
        yo = this;
        aj = new Ajustes(this);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        crearCanal();

        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(p -> {
            preparado = true;
            cargando = false;
            try { duracionConocida = mp.getDuration(); } catch (Exception ignored) { }
            if (volverA > 0) { try { mp.seekTo(volverA); } catch (Exception ignored) { } volverA = 0; }
            empezar();
        });
        mp.setOnCompletionListener(p -> {
            // si una cancion por internet "termina" mucho antes de su duracion, fue un corte de senal: se retoma
            int d = duracionConocida;
            try { int fin = mp.getCurrentPosition(); if (fin > ultimaPos) ultimaPos = fin; } catch (Exception ignored) { }
            if (esInternet && d > 0 && ultimaPos > 0 && ultimaPos < d - 8000 && reintentos < 3) { reintentar(); return; }
            if (esInternet && d <= 0 && reintentos < 3) { reintentar(); return; } // emisora en vivo: se cayo, se reconecta
            alTerminar();
        });
        mp.setOnInfoListener((p, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) cargando = true;
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) cargando = false;
            return false;
        });
        mp.setOnErrorListener((p, what, extra) -> {
            Log.w(TAG, "Error del reproductor " + what + "/" + extra);
            preparado = false;
            cargando = false;
            if (esInternet && reintentos < 3) { reintentar(); return true; }
            sonando = false;
            soltarWifi();
            actualizarSesion();
            notificar();
            if (esInternet) h.postDelayed(this::siguiente, 1500); // no se pudo: sigue con la proxima
            return true;
        });
        // en modo seguro (si una version fallo) se usa el motor compatible, el mas sencillo
        cadenaPropia = new Cadena(mp.getAudioSessionId(), !Guardian.modoSeguro(this));
        motorTxt = cadenaPropia.motor();

        ms = new MediaSession(this, "VolumenPlus");
        ms.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { reanudar(); }
            @Override public void onPause() { pausar(); }
            @Override public void onSkipToNext() { siguiente(); }
            @Override public void onSkipToPrevious() { anterior(); }
            @Override public void onSeekTo(long pos) { ir((int) pos); }
            @Override public void onStop() { apagar(); }
        });
        ms.setActive(true);

        IntentFilter f = new IntentFilter();
        f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(sesiones, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(sesiones, f);
        receptor = true;
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ruido, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(ruido, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        receptorRuido = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        notificar(); // primero: Android exige mostrar la notificacion enseguida
        String a = intent != null && intent.getAction() != null ? intent.getAction() : A_INICIAR;
        switch (a) {
            case A_APAGAR: apagar(); return START_NOT_STICKY;
            case A_TOCAR: tocar(intent.getIntExtra("i", 0)); break;
            case A_PAUSA: if (sonando) pausar(); else reanudar(); break;
            case A_SIG: siguiente(); break;
            case A_ANT: anterior(); break;
            case A_IR: ir(intent.getIntExtra("ms", 0)); break;
            default: break;
        }
        aplicarTodo();
        notificar();
        return START_STICKY;
    }

    // ---------- reproductor ----------
    private void tocar(int i) {
        ultimaPos = reintentando ? ultimaPos : 0;
        List<Ajustes.Cancion> l = aj.lista();
        if (l.isEmpty()) return;
        if (i < 0) i = l.size() - 1;
        if (i >= l.size()) i = 0;
        if (i != aj.actual() || !reintentando) { reintentos = 0; volverA = 0; }
        reintentando = false;
        aj.setActual(i);
        Ajustes.Cancion c = l.get(i);
        titulo = c.nombre;
        esInternet = c.uri.startsWith("http");
        duracionConocida = 0;
        final int miTurno = ++turno;
        precargada = false;
        try {
            preparado = false;
            cargando = esInternet;
            mp.reset();
            if (!esInternet) {
                bufer = null;
                mp.setDataSource(this, Uri.parse(c.uri));
                mp.prepareAsync();
            } else {
                // por internet: primero la reserva. Se decide en segundo plano (sin congelar la pantalla)
                // si es una cancion (se guarda y suena sin cortes) o una emisora en vivo (directo, como antes).
                final Bufer b = Bufer.de(this, c.uri);
                bufer = b;
                final String nombre = c.nombre;
                new Thread(() -> {
                    boolean directo = b.esEnVivo(12000);
                    h.post(() -> {
                        if (miTurno != turno) return; // ya se eligio otra cancion
                        try {
                            if (directo) { bufer = null; mp.setDataSource(this, Uri.parse(b.uri)); }
                            else mp.setDataSource(b);
                            mp.prepareAsync();
                        } catch (Exception e) {
                            Log.w(TAG, "No se pudo abrir " + nombre, e);
                            if (reintentos < 3) reintentar();
                            else { titulo = nombre + " (no se pudo abrir)"; sonando = false; cargando = false; actualizarSesion(); notificar(); }
                        }
                    });
                }, "VolumenPlus-abrir").start();
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudo abrir " + c.nombre, e);
            titulo = c.nombre + " (no se pudo abrir)";
            sonando = false;
        }
        actualizarSesion();
    }

    private void empezar() {
        if (!pedirFoco()) return;
        try {
            mp.start();
            sonando = true;
            if (esInternet) tomarWifi();
            h.removeCallbacks(seguir);
            h.post(seguir);
            h.removeCallbacks(fundido);
            h.post(fundido);
        } catch (IllegalStateException e) {
            sonando = false;
        }
        aplicarTodo();
        actualizarSesion();
        notificar();
    }

    private void reanudar() {
        if (preparado) empezar();
        else tocar(aj.actual());
    }

    private void pausar() {
        try { if (preparado && mp.isPlaying()) mp.pause(); } catch (IllegalStateException ignored) { }
        sonando = false;
        soltarWifi();
        aplicarTodo(); // el refuerzo general vuelve para las otras apps
        actualizarSesion();
        notificar();
    }

    // ---------- aleatorio y repetir ----------
    private final Random azar = new Random();
    private final ArrayList<Integer> bolsa = new ArrayList<>();      // canciones que faltan en modo aleatorio
    private final ArrayDeque<Integer> historial = new ArrayDeque<>(); // para "anterior" en modo aleatorio

    /** Al terminar una cancion: repetir la misma, seguir, o parar al final de la lista. */
    private void alTerminar() {
        int n = aj.lista().size();
        if (n == 0) return;
        if (aj.repetir() == Ajustes.REPETIR_UNA) { tocar(aj.actual()); return; }
        if (!aj.aleatorio() && aj.repetir() == Ajustes.REPETIR_NO && aj.actual() >= n - 1) {
            sonando = false;
            preparado = false;
            soltarWifi();
            aplicarTodo();
            actualizarSesion();
            notificar();
            return;
        }
        siguiente();
    }

    /** La cancion que va a sonar despues (para precargarla). */
    private String uriSiguiente() {
        try {
            List<Ajustes.Cancion> l = aj.lista();
            int n = l.size();
            if (n < 2 || aj.repetir() == Ajustes.REPETIR_UNA) return null;
            int i;
            if (aj.aleatorio()) {
                if (bolsa.isEmpty()) return null;
                i = bolsa.get(0);
            } else {
                i = aj.actual() + 1;
                if (i >= n) { if (aj.repetir() == Ajustes.REPETIR_NO) return null; i = 0; }
            }
            return i >= 0 && i < n ? l.get(i).uri : null;
        } catch (Throwable t) { return null; }
    }

    private void siguiente() {
        int n = aj.lista().size();
        if (n == 0) return;
        if (!aj.aleatorio() || n < 2) { tocar(aj.actual() + 1); return; }
        // Aleatorio sin repetir hasta que suenen todas
        bolsa.removeIf(i -> i >= n || i == aj.actual());
        if (bolsa.isEmpty()) {
            for (int i = 0; i < n; i++) if (i != aj.actual()) bolsa.add(i);
            Collections.shuffle(bolsa, azar);
        }
        historial.push(aj.actual());
        while (historial.size() > 100) historial.removeLast();
        tocar(bolsa.remove(0));
    }

    private void anterior() {
        if (preparado && posicion() > 4000) { ir(0); return; }
        if (aj.aleatorio() && !historial.isEmpty()) {
            int i = historial.pop();
            if (i < aj.lista().size()) { tocar(i); return; }
        }
        tocar(aj.actual() - 1);
    }

    private void ir(int pos) {
        try { if (preparado) mp.seekTo(pos); } catch (IllegalStateException ignored) { }
        actualizarSesion();
    }

    private boolean pedirFoco() {
        if (am == null) return true;
        if (foco == null) {
            foco = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(cambioFoco)
                    .build();
        }
        return am.requestAudioFocus(foco) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    // ---------- sonido ----------
    private void aplicarA(Cadena c) {
        c.aplicar(aj.activo(), potenciaEquipo(), bandasEquipo(), aj.espacial(), aj.compresor(), aj.claridad(), aj.bajoExtra());
    }

    // ---------- ecualizacion por equipo ----------
    private String equipoDetectado = "";

    /** Equipo en uso: el elegido, o el que se detecta si esta en Automatico. */
    public int equipoActual() {
        int e = aj.equipo();
        if (e != 0) return e;
        int r = 1;
        equipoDetectado = "parlante del celular";
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            for (android.media.AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                String nombre = String.valueOf(d.getProductName());
                if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    String n = nombre.toLowerCase(java.util.Locale.ROOT);
                    boolean carro = n.contains("car") || n.contains("auto") || n.contains("coche") || n.contains("sync")
                            || n.contains("vehic") || n.contains("radio") || n.contains("pioneer") || n.contains("kenwood");
                    boolean audif = n.contains("bud") || n.contains("pods") || n.contains("headphone") || n.contains("auricular")
                            || n.contains("audifono") || n.contains("wh-") || n.contains("wf-") || n.contains("earphone");
                    r = carro ? 4 : audif ? 2 : 3;
                    equipoDetectado = nombre + (carro ? " (carro)" : audif ? " (audífonos)" : " (parlante Bluetooth)");
                    return r;
                }
                if (t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) {
                    r = 2; equipoDetectado = "audífonos con cable";
                }
                if (t == android.media.AudioDeviceInfo.TYPE_HDMI && r == 1) { r = 6; equipoDetectado = "televisor (HDMI)"; }
            }
        } catch (Throwable ignored) { }
        return r;
    }

    public String equipoDetectado() { return equipoDetectado; }

    private boolean listoParaAplicar() { return aj != null && cadenaPropia != null && aj.equipo() == 0; }

    private int[] bandasEquipo() {
        int[] b = aj.bandas().clone();
        int[] c = Ajustes.curvaEquipo(equipoActual());
        for (int i = 0; i < b.length && i < c.length; i++) b[i] = Math.max(-12, Math.min(12, b[i] + c[i]));
        return b;
    }

    private int potenciaEquipo() {
        return Math.min(aj.potencia(), Ajustes.topeEquipo(equipoActual()));
    }

    private final android.media.AudioDeviceCallback cambioEquipo = new android.media.AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] d) { if (listoParaAplicar()) aplicarTodo(); }
        @Override public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] d) { if (listoParaAplicar()) aplicarTodo(); }
    };

    // ---------- volumen: voz del DJ, notificaciones y transiciones ----------
    private float volHablar = 1f, volFoco = 1f, volFundido = 1f;

    private void ponerVolumen() {
        float v = volHablar * volFoco * volFundido;
        try { mp.setVolume(v, v); } catch (Exception ignored) { }
    }

    /** Baja la musica mientras el DJ habla o manda un saludo. */
    public void bajarParaHablar(boolean bajar) {
        h.post(() -> { volHablar = bajar ? 0.22f : 1f; ponerVolumen(); });
    }

    /** Fundido de DJ: la cancion entra suave y se despide bajando en los ultimos segundos. */
    private final Runnable fundido = new Runnable() {
        @Override public void run() {
            if (!sonando) return;
            float objetivo = 1f;
            try {
                if (aj.transiciones() && preparado && duracionConocida > 20000) {
                    int p = mp.getCurrentPosition();
                    if (p < 1500) objetivo = 0.35f + 0.65f * p / 1500f;
                    else if (p > duracionConocida - 4500) objetivo = Math.max(0.15f, (duracionConocida - p) / 4500f);
                }
            } catch (Exception ignored) { }
            if (Math.abs(objetivo - volFundido) > 0.01f) { volFundido = objetivo; ponerVolumen(); }
            h.postDelayed(this, 150);
        }
    };

    private void aplicarTodo() {
        if (aj.global() && !Guardian.modoSeguro(this)) {
            if (cadenaGlobal == null) {
                cadenaGlobal = new Cadena(0, false);
                if (!cadenaGlobal.funciona()) { cadenaGlobal.liberar(); cadenaGlobal = null; }
            }
            if (cadenaGlobal != null) {
                cadenaGlobal.aplicar(aj.activo() && !sonando, potenciaEquipo(), bandasEquipo(), aj.espacial(), aj.compresor(),
                        aj.claridad(), aj.bajoExtra());
            }
            for (Cadena c : otras.values()) aplicarA(c);
        } else {
            if (cadenaGlobal != null) { cadenaGlobal.liberar(); cadenaGlobal = null; }
            for (Cadena c : otras.values()) c.liberar();
            otras.clear();
        }
        // Si la salida general ya recibe el refuerzo, el reproductor no lo suma otra vez (evita saturar).
        aplicarA(cadenaPropia);
    }

    // ---------- lo que la pantalla consulta ----------
    public boolean estaSonando() { return sonando; }
    public String titulo() { return titulo; }
    public String motor() { return motorTxt; }
    public boolean globalConectado() { return cadenaGlobal != null || !otras.isEmpty(); }
    public int otrasApps() { return otras.size(); }

    public int posicion() {
        try {
            if (!preparado) return volverA > 0 ? volverA : ultimaPos;
            int p = mp.getCurrentPosition();
            if (p > 0) ultimaPos = p;
            return p;
        } catch (IllegalStateException e) { return 0; }
    }

    public boolean cargando() { return cargando || reintentando || (esInternet && !preparado && sonando); }
    public int reintentosActuales() { return reintentos; }

    public int duracion() {
        try { return preparado ? mp.getDuration() : 0; } catch (IllegalStateException e) { return 0; }
    }

    // ---------- notificacion y controles del sistema ----------
    private void crearCanal() {
        NotificationChannel ch = new NotificationChannel(CANAL, "Reproductor y sonido", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Controles de Volumen+");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private PendingIntent pi(String accion, int codigo) {
        return PendingIntent.getService(this, codigo, new Intent(this, MotorService.class).setAction(accion),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action accion(int icono, String texto, String a, int codigo) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icono), texto, pi(a, codigo)).build();
    }

    private void notificar() {
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String sub = (aj.activo() ? "Sonido Pro +" + aj.potencia() + " dB" : "Sonido Pro apagado")
                + (aj.global() ? " · todo el telefono" : "");
        Notification n = new Notification.Builder(this, CANAL)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle(titulo.isEmpty() ? "Volumen+ Pro" : titulo)
                .setContentText(sub)
                .setContentIntent(abrir)
                .setOngoing(sonando || aj.global())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(accion(android.R.drawable.ic_media_previous, "Anterior", A_ANT, 1))
                .addAction(accion(sonando ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        sonando ? "Pausar" : "Reproducir", A_PAUSA, 2))
                .addAction(accion(android.R.drawable.ic_media_next, "Siguiente", A_SIG, 3))
                .addAction(accion(android.R.drawable.ic_menu_close_clear_cancel, "Apagar", A_APAGAR, 4))
                .setStyle(new Notification.MediaStyle().setMediaSession(ms != null ? ms.getSessionToken() : null)
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF, n);
    }

    private void actualizarSesion() {
        if (ms == null) return;
        long acciones = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        ms.setPlaybackState(new PlaybackState.Builder()
                .setActions(acciones)
                .setState(sonando ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, posicion(), sonando ? 1f : 0f)
                .build());
        ms.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titulo.isEmpty() ? "Volumen+ Pro" : titulo)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duracion())
                .build());
    }

    private void apagar() {
        pausar();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        yo = null;
        try { ((AudioManager) getSystemService(AUDIO_SERVICE)).unregisterAudioDeviceCallback(cambioEquipo); } catch (Throwable ignored) { }
        soltarWifi();
        h.removeCallbacksAndMessages(null);
        if (receptor) { try { unregisterReceiver(sesiones); } catch (Throwable ignored) { } receptor = false; }
        if (receptorRuido) { try { unregisterReceiver(ruido); } catch (Throwable ignored) { } receptorRuido = false; }
        if (cadenaPropia != null) cadenaPropia.liberar();
        if (cadenaGlobal != null) cadenaGlobal.liberar();
        for (Cadena c : otras.values()) c.liberar();
        otras.clear();
        try { mp.release(); } catch (Throwable ignored) { }
        if (am != null && foco != null) am.abandonAudioFocusRequest(foco);
        if (ms != null) { ms.setActive(false); ms.release(); }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
