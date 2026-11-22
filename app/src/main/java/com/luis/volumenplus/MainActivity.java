package com.luis.volumenplus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pantalla de Volumen+: refuerzo para todo el telefono, reproductor, musica en linea y Sonido Pro. */
public class MainActivity extends Activity {

    private static final int PEDIR_AUDIO = 21;

    private static final int FONDO = Color.rgb(11, 18, 32);
    private static final int TARJETA = Color.rgb(20, 30, 50);
    private static final int BOTON = Color.rgb(36, 52, 80);
    private static final int TEXTO = Color.rgb(235, 240, 250);
    private static final int SUAVE = Color.rgb(150, 165, 190);
    private static final int ACENTO = Color.rgb(56, 189, 248);
    private static final int ALERTA = Color.rgb(248, 113, 113);

    private Ajustes aj;
    private final Handler h = new Handler(Looper.getMainLooper());

    private TextView tTitulo, tTiempo, tMotor, tPot, tAviso, tEsp, tGlobal;
    private SeekBar sbPos, sbPot, sbEsp;
    private final SeekBar[] sbBandas = new SeekBar[Cadena.CENTROS.length];
    private final TextView[] tBandas = new TextView[Cadena.CENTROS.length];
    private Button bPlay, bAleatorio, bRepetir, bMiLista, bHablar;
    private EditText etDj, etSaludo;
    private TextView tDj, tEquipo, tGuardian;
    private Button bSeguro, bActualizar;
    private TextView tActualizar;
    private Actualizador.Info nueva;
    private LinearLayout equipos;
    private Locutor locutor;
    private Agente agente;
    private android.speech.SpeechRecognizer oido;
    private boolean oyendo = false;
    private TextView tBajo;
    private SeekBar sbBajo;
    private List<EnLinea.Resultado> ultimos = new ArrayList<>();
    private LinearLayout sugeridas;
    private volatile int turnoPredictor = 0;
    private boolean escribiendoYo = false;
    private EnLinea.Sugerencia muestra;
    private LinearLayout modos;
    /** 2.0.22: las listas de pistas van en ventanas cortas (no una lista enorme). */
    private Ventana lista, aprendido;
    private ImageView ivCaratula;
    private String caratulaMostrada = "";
    private TextView tAprende;
    private int temaAprendido = 0;
    private LinearLayout chipsAprende;
    private Switch swActivo, swComp, swGlobal, swFlac;
    private EditText etBuscar;
    private TextView tLinea;
    private Ventana resultados;
    private boolean arrastrando = false;
    private int listaPintada = -2;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Guardian.arranque(this);
        if (!Guardian.esOficial(this)) { setContentView(pantallaNoOficial()); return; }
        aj = new Ajustes(this);
        locutor = new Locutor(this);
        agente = new Agente(manos);
        getWindow().setStatusBarColor(FONDO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        setContentView(armar());
        pintarAprendido();
        enviar(MotorService.A_INICIAR, -1, -1);
        revisarEnSegundoPlano("abriste la app");
        if (Actualizador.tocaRevisar(this)) h.postDelayed(() -> actualizar(false), 4000);
        if (AprendeYouTube.tocaAprender(this)) h.postDelayed(() -> aprenderDeYouTube(false), 15000);
        // si la app funciona un minuto sin fallas, esta version queda como la "buena" y se respaldan tus datos
        h.postDelayed(() -> Guardian.marcarBuena(this), 60_000);
        abrirDesdeOtraApp(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (aj == null) return; // copia no oficial: no hace nada
        abrirDesdeOtraApp(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (aj == null) return;
        h.post(refresco);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresco);
    }

    private final Runnable refresco = new Runnable() {
        @Override
        public void run() {
            pintarEstado();
            h.postDelayed(this, 500);
        }
    };

    // ---------- pantalla ----------
    private View armar() {
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(dp(16), dp(20), dp(16), dp(28));
        raiz.setBackgroundColor(FONDO);

        TextView t = texto("Volumen+ Pro", 26, TEXTO);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        raiz.addView(t);
        tMotor = texto("", 13, SUAVE);
        raiz.addView(tMotor);

        // --- Agente DJ
        LinearLayout dj = tarjeta(raiz, "🎧 Agente DJ");
        dj.addView(texto("Tu DJ personal: arma fiestas, pone canciones, ecualiza según el equipo, lanza efectos y manda saludos. "
                + "Háblale o escríbele.", 13, SUAVE));
        bHablar = boton("🎤 Háblale al DJ", v -> escucharVoz());
        bHablar.setBackground(fondoBoton(ACENTO));
        bHablar.setTextColor(Color.rgb(8, 20, 36));
        bHablar.setTextSize(17);
        dj.addView(bHablar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        etDj = new EditText(this);
        etDj.setHint("O escríbele: fiesta de salsa, pon Feid, ecualiza para el carro…");
        etDj.setTextColor(TEXTO);
        etDj.setHintTextColor(SUAVE);
        etDj.setSingleLine(true);
        etDj.setImeOptions(EditorInfo.IME_ACTION_SEND);
        etDj.setOnEditorActionListener((v, a, ev) -> {
            String f = etDj.getText().toString();
            etDj.setText("");
            oirFrase(f);
            return true;
        });
        dj.addView(etDj);
        tDj = texto("Prueba: \"fiesta crossover\", \"saluda a la familia Mendoza\", \"suelta la corneta\".", 14, ACENTO);
        dj.addView(tDj);

        tGuardian = texto("🛡 Guardián: revisando la app…", 13, SUAVE);
        tGuardian.setPadding(0, dp(10), 0, 0);
        dj.addView(tGuardian);
        LinearLayout fg = fila();
        fg.addView(boton("Revisar y reparar", v -> manos.revisar()), peso());
        fg.addView(boton("Ver informe", v -> verInforme()), peso());
        dj.addView(fg);
        bSeguro = boton("Salir del modo seguro", v -> {
            Guardian.salirDeModoSeguro(this);
            avisar("Modo seguro apagado. Cierra y abre la app para usar el procesador completo.");
            pintarGuardian(Guardian.revisar(this, "saliste del modo seguro"));
        });
        dj.addView(bSeguro);
        bSeguro.setVisibility(Guardian.modoSeguro(this) ? View.VISIBLE : View.GONE);

        // Actualizaciones desde GitHub: se instalan encima, sin perder listas ni gustos
        tActualizar = texto("⬆️ Versión " + Actualizador.nombreActual(this) + ". Las actualizaciones llegan desde GitHub.", 13, SUAVE);
        tActualizar.setPadding(0, dp(8), 0, 0);
        dj.addView(tActualizar);
        bActualizar = boton("⬆️ Buscar actualización", v -> actualizar(true));
        dj.addView(bActualizar);

        dj.addView(subtitulo("Listas de fiesta (lo mejor, mezclado por tempo y tono)"));
        LinearLayout chipsF = chips(dj);
        for (int i = 0; i < EnLinea.FIESTAS.length; i++) {
            final int e = i;
            chipsF.addView(boton(EnLinea.FIESTAS[i], v -> manos.fiesta(e)));
        }
        dj.addView(boton("🔥 Música nueva y tendencias", v -> manos.nueva("Latin")));

        // 2.0.22: el DJ aprende de YouTube lo mas sonado y con eso arma las fiestas
        dj.addView(subtitulo("🧠 Lo que aprendí de YouTube (lo más sonado)"));
        tAprende = texto("", 12, SUAVE);
        dj.addView(tAprende);
        chipsAprende = chips(dj);
        for (int i = 0; i < AprendeYouTube.TEMAS.length; i++) {
            final int e = i;
            chipsAprende.addView(boton(AprendeYouTube.TEMAS[i], v -> { temaAprendido = e; pintarAprendido(); }));
        }
        aprendido = new Ventana(this, boton("Ver más", null));
        dj.addView(aprendido);
        LinearLayout fa = fila();
        fa.addView(boton("🧠 Aprender ahora", v -> aprenderDeYouTube(true)), peso());
        fa.addView(boton("Puente (opcional)", v -> pedirPuente()), peso());
        dj.addView(fa);

        dj.addView(subtitulo("¿Dónde está sonando? (ecualiza para ese equipo)"));
        equipos = chips(dj);
        for (int i = 0; i < Ajustes.EQUIPOS.length; i++) {
            final int e = i;
            equipos.addView(boton(Ajustes.EQUIPOS[i], v -> manos.equipo(e)));
        }
        tEquipo = texto("", 12, SUAVE);
        dj.addView(tEquipo);

        dj.addView(subtitulo("Efectos de DJ"));
        LinearLayout chipsE = chips(dj);
        for (int i = 0; i < Efectos.NOMBRES.length; i++) {
            final int e = i;
            chipsE.addView(boton(Efectos.NOMBRES[i], v -> Efectos.tocar(e)));
        }

        dj.addView(subtitulo("Saludo con voz de locutor"));
        etSaludo = new EditText(this);
        etSaludo.setHint("¿Para quién? Ej.: la familia Mendoza");
        etSaludo.setTextColor(TEXTO);
        etSaludo.setHintTextColor(SUAVE);
        etSaludo.setSingleLine(true);
        dj.addView(etSaludo);
        LinearLayout fs = fila();
        fs.addView(boton("📣 Mandar saludo", v -> {
            String q = etSaludo.getText().toString().trim();
            if (q.isEmpty()) { avisar("Escribe para quién es el saludo"); return; }
            manos.saludo("¡Un saludo muy especial para " + q + "! ¡Que siga la fiesta!");
        }), peso());
        fs.addView(boton("🎂 Cumpleaños", v -> {
            String q = etSaludo.getText().toString().trim();
            if (q.isEmpty()) { avisar("Escribe el nombre del cumpleañero"); return; }
            manos.saludo("¡Atención, atención! ¡Un feliz cumpleaños para " + q + "! ¡Esta fiesta es en tu honor!");
        }), peso());
        dj.addView(fs);
        dj.addView(interruptor("Transiciones de DJ (cada canción entra y sale suave)", aj.transiciones(), v -> aj.setTransiciones(v)));

        // --- Reproductor
        LinearLayout rep = tarjeta(raiz, "Reproductor");
        ivCaratula = new ImageView(this);
        ivCaratula.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivCaratula.setClipToOutline(true);
        GradientDrawable marco = new GradientDrawable();
        marco.setCornerRadius(dp(18));
        ivCaratula.setBackground(marco);
        LinearLayout.LayoutParams lpc = new LinearLayout.LayoutParams(dp(240), dp(240));
        lpc.gravity = Gravity.CENTER_HORIZONTAL;
        lpc.setMargins(0, dp(6), 0, dp(10));
        rep.addView(ivCaratula, lpc);
        Caratula.poner(ivCaratula, "Volumen+ Pro", "", false, null, 480);
        tTitulo = texto("Agrega tus canciones para empezar", 17, TEXTO);
        tTitulo.setTypeface(Typeface.DEFAULT_BOLD);
        rep.addView(tTitulo);
        sbPos = new SeekBar(this);
        sbPos.setOnSeekBarChangeListener(new Barra() {
            @Override public void onStartTrackingTouch(SeekBar s) { arrastrando = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                arrastrando = false;
                enviar(MotorService.A_IR, -1, s.getProgress());
            }
        });
        rep.addView(sbPos, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        tTiempo = texto("0:00 / 0:00", 13, SUAVE);
        rep.addView(tTiempo);

        LinearLayout ctrl = fila();
        ctrl.setGravity(Gravity.CENTER);
        ctrl.addView(boton("⏮", v -> enviar(MotorService.A_ANT, -1, -1)), peso());
        bPlay = boton("▶", v -> {
            if (aj.lista().isEmpty()) elegirCanciones();
            else enviar(MotorService.A_PAUSA, -1, -1);
        });
        ctrl.addView(bPlay, peso());
        ctrl.addView(boton("⏭", v -> enviar(MotorService.A_SIG, -1, -1)), peso());
        rep.addView(ctrl);

        LinearLayout modoRep = fila();
        bAleatorio = boton("", v -> { aj.setAleatorio(!aj.aleatorio()); pintarReproduccion(); });
        modoRep.addView(bAleatorio, peso());
        bRepetir = boton("", v -> { aj.setRepetir((aj.repetir() + 1) % 3); pintarReproduccion(); });
        modoRep.addView(bRepetir, peso());
        rep.addView(modoRep);

        LinearLayout gest = fila();
        gest.addView(boton("+ Agregar canciones", v -> elegirCanciones()), peso());
        bMiLista = boton("♥ Mi lista", v -> abrirMiLista());
        gest.addView(bMiLista, peso());
        rep.addView(gest);
        gest = fila();
        gest.addView(boton("Limpiar cola", v -> {
            if (aj.lista().isEmpty()) return;
            new AlertDialog.Builder(this)
                    .setTitle("¿Limpiar lo que está sonando?")
                    .setMessage("Solo se quita la cola de reproducción. Mi lista, tus listas guardadas y tus archivos no se tocan.")
                    .setPositiveButton("Limpiar", (d, w) -> { aj.guardarLista(new ArrayList<>()); aj.setActual(0); pintarLista(); })
                    .setNegativeButton("Cancelar", null)
                    .show();
        }), peso());
        rep.addView(gest);
        LinearLayout listasFila = fila();
        listasFila.addView(boton("Guardar cola", v -> guardarListaComo()), peso());
        listasFila.addView(boton("Mis listas", v -> misListas()), peso());
        rep.addView(listasFila);
        rep.addView(texto("Sonando ahora", 14, ACENTO));
        lista = new Ventana(this, boton("Ver más", null));
        rep.addView(lista);

        // --- Musica en linea
        LinearLayout lin = tarjeta(raiz, "Música en línea (gratis, sin anuncios)");
        lin.addView(texto("Toca una canción y suena de una vez. Con + la guardas en Mi lista. La app aprende lo que te gusta "
                + "y te arma \"Para ti\". Mientras escribes, el predictor adivina la canción, incluso con un pedazo de la letra.", 13, SUAVE));
        etBuscar = new EditText(this);
        etBuscar.setHint("Escribe un pedazo de la canción o de la letra");
        etBuscar.setTextColor(TEXTO);
        etBuscar.setHintTextColor(SUAVE);
        etBuscar.setSingleLine(true);
        etBuscar.setInputType(InputType.TYPE_CLASS_TEXT);
        etBuscar.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        etBuscar.setOnEditorActionListener((v, accion, ev) -> { sugeridas.removeAllViews(); buscar(false); return true; });
        etBuscar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) {
                h.removeCallbacks(predecir);
                if (escribiendoYo) return;
                if (e.toString().trim().length() >= 3) h.postDelayed(predecir, 450);
                else sugeridas.removeAllViews();
            }
        });
        lin.addView(etBuscar);
        sugeridas = new LinearLayout(this);
        sugeridas.setOrientation(LinearLayout.VERTICAL);
        lin.addView(sugeridas);
        LinearLayout bus = fila();
        bus.addView(boton("Buscar", v -> buscar(false)), peso());
        bus.addView(boton("Para ti", v -> paraTi()), peso());
        lin.addView(bus);
        bus = fila();
        bus.addView(boton("Éxitos de hoy", v -> exitos()), peso());
        bus.addView(boton("Emisoras", v -> buscar(true)), peso());
        lin.addView(bus);
        swFlac = interruptor("Incluir álbumes FLAC de Internet Archive (gasta más datos)", aj.flac(), v -> aj.setFlac(v));
        lin.addView(swFlac);
        tLinea = texto("Escribe y toca una sugerencia, o usa Para ti y Éxitos de hoy.", 13, SUAVE);
        lin.addView(tLinea);
        resultados = new Ventana(this, boton("Ver más", null));
        lin.addView(resultados);

        // --- Sonido
        LinearLayout son = tarjeta(raiz, "Sonido Pro");
        swActivo = interruptor("Sonido Pro activado", aj.activo(), v -> { aj.setActivo(v); aplicar(); });
        son.addView(swActivo);

        son.addView(texto("Modo", 14, SUAVE));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        modos = fila();
        for (int m : Ajustes.ORDEN_MODOS) {
            final int mm = m;
            modos.addView(boton(nombreModo(m),
                    v -> { aj.setModo(mm); cargarControles(); aplicar(); }));
        }
        hs.addView(modos);
        son.addView(hs);

        tPot = texto("", 16, TEXTO);
        tPot.setPadding(0, dp(12), 0, 0);
        son.addView(tPot);
        sbPot = new SeekBar(this);
        sbPot.setMax(Ajustes.MAX_POTENCIA);
        sbPot.setOnSeekBarChangeListener(new Barra() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) { aj.setPotencia(p); pintarPotencia(); } }
            @Override public void onStopTrackingTouch(SeekBar s) { aplicar(); }
        });
        son.addView(sbPot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        tAviso = texto("", 13, SUAVE);
        son.addView(tAviso);

        tBajo = texto("", 16, TEXTO);
        tBajo.setPadding(0, dp(12), 0, 0);
        son.addView(tBajo);
        sbBajo = new SeekBar(this);
        sbBajo.setMax(10);
        sbBajo.setOnSeekBarChangeListener(new Barra() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) { aj.setBajoExtra(p); pintarBajo(); } }
            @Override public void onStopTrackingTouch(SeekBar s) { aplicar(); }
        });
        son.addView(sbBajo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        son.addView(texto("Sube 60-125 Hz (el golpe que sí se siente) y despeja 250-500 Hz para que la voz siga nítida.", 13, SUAVE));

        son.addView(espacio(10));
        son.addView(texto("Ecualizador de 10 bandas", 15, TEXTO));
        for (int i = 0; i < Cadena.CENTROS.length; i++) {
            final int k = i;
            LinearLayout f = fila();
            f.setGravity(Gravity.CENTER_VERTICAL);
            tBandas[i] = texto("", 13, SUAVE);
            f.addView(tBandas[i], new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
            sbBandas[i] = new SeekBar(this);
            sbBandas[i].setMax(24);
            sbBandas[i].setOnSeekBarChangeListener(new Barra() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                    if (u) { aj.setBanda(k, p - 12); pintarBanda(k, p - 12); pintarModos(); }
                }
                @Override public void onStopTrackingTouch(SeekBar s) { aplicar(); }
            });
            f.addView(sbBandas[i], new LinearLayout.LayoutParams(0, dp(36), 1f));
            son.addView(f);
        }

        tEsp = texto("", 15, TEXTO);
        tEsp.setPadding(0, dp(12), 0, 0);
        son.addView(tEsp);
        sbEsp = new SeekBar(this);
        sbEsp.setMax(10);
        sbEsp.setOnSeekBarChangeListener(new Barra() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) { aj.setEspacial(p * 100); pintarEspacial(); } }
            @Override public void onStopTrackingTouch(SeekBar s) { aplicar(); }
        });
        son.addView(sbEsp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        swComp = interruptor("Maximizador de volumen (sube los dB sin recortar)", aj.compresor(), v -> { aj.setCompresor(v); aplicar(); });
        son.addView(swComp);
        son.addView(interruptor("Claridad tipo Dolby (más nítido, sin retumbe)", aj.claridad(), v -> { aj.setClaridad(v); aplicar(); }));
        swGlobal = interruptor("Refuerzo en todo el teléfono (YouTube, Spotify, videos…)", aj.global(), v -> { aj.setGlobal(v); aplicar(); });
        son.addView(swGlobal);
        tGlobal = texto("", 13, SUAVE);
        son.addView(tGlobal);

        // --- Ayudas
        LinearLayout ay = tarjeta(raiz, "Para que suene al máximo");
        ay.addView(boton("Subir el volumen multimedia al máximo", v -> volumenMax()));
        String dolby = efectosDelTelefono();
        if (dolby != null) {
            ay.addView(texto("Tu teléfono trae " + dolby + ". Actívalo en Ajustes de sonido: se suma a Volumen+.", 13, ACENTO));
        }
        ay.addView(boton("Abrir ajustes de sonido (Dolby / efectos del teléfono)", v -> {
            try { startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS)); } catch (Exception e) { avisar("No se pudo abrir"); }
        }));
        ay.addView(boton("Evitar que Xiaomi cierre la app", v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
        ay.addView(texto("• En Ajustes de la app: Ahorro de batería en \"Sin restricciones\" e \"Inicio automático\" activado.\n"
                + "• \"Bajo + nítido\" junta bajos potentes y voces claras: quita el retumbe que tapa la voz.\n"
                + "• La potencia se reparte en un compresor de 3 bandas y un limitador: suena más fuerte sin recortar. "
                + "Aun así el parlante del celular tiene un límite físico: "
                + "para máxima calidad usa audífonos o un parlante Bluetooth.\n"
                + "• Algunas apps (como YouTube) no dejan aplicar efectos; tu propia música en este reproductor siempre suena con Sonido Pro.\n"
                + "• Más de +12 dB por largo tiempo puede dañar el oído con audífonos.", 13, SUAVE));

        cargarControles();
        pintarLista();
        pintarReproduccion();

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(FONDO);
        sv.addView(raiz);
        return sv;
    }

    private void cargarControles() {
        swActivo.setChecked(aj.activo());
        sbPot.setProgress(aj.potencia());
        int[] b = aj.bandas();
        for (int i = 0; i < b.length; i++) { sbBandas[i].setProgress(b[i] + 12); pintarBanda(i, b[i]); }
        sbEsp.setProgress(aj.espacial() / 100);
        sbBajo.setProgress(aj.bajoExtra());
        pintarBajo();
        pintarPotencia();
        pintarEspacial();
        pintarModos();
    }

    private void pintarPotencia() {
        int p = aj.potencia();
        tPot.setText("Potencia: +" + p + " dB");
        if (p > Ajustes.POTENCIA_SEGURA) {
            tAviso.setText("Nivel muy alto: el limitador protege el sonido, pero úsalo por ratos y con cuidado en audífonos.");
            tAviso.setTextColor(ALERTA);
        } else {
            tAviso.setText("Cada paso sube el volumen real: +6 ≈ 3 dB más, +12 ≈ 5 dB, +24 ≈ 6 dB (techo sin distorsión). Sube también el volumen del teléfono al máximo.");
            tAviso.setTextColor(SUAVE);
        }
    }

    private void pintarBajo() {
        int b = aj.bajoExtra();
        tBajo.setText("Bajo extra: " + (b == 0 ? "apagado" : "+" + b + " dB"));
    }

    private void pintarBanda(int i, int db) {
        int hz = Cadena.CENTROS[i];
        String f = hz >= 1000 ? (hz / 1000) + " kHz" : hz + " Hz";
        tBandas[i].setText(f + "  " + (db > 0 ? "+" : "") + db + " dB");
    }

    private void pintarEspacial() {
        int e = aj.espacial() / 100;
        tEsp.setText("Sonido envolvente: " + (e == 0 ? "apagado" : (e * 10) + " %"));
    }

    private void pintarModos() {
        int m = aj.modo();
        for (int i = 0; i < modos.getChildCount(); i++) {
            Button b = (Button) modos.getChildAt(i);
            boolean on = Ajustes.ORDEN_MODOS[i] == m;
            b.setBackground(fondoBoton(on ? ACENTO : BOTON));
            b.setTextColor(on ? Color.rgb(8, 20, 36) : TEXTO);
        }
    }

    private void pintarEstado() {
        MotorService m = MotorService.yo;
        if (m == null) {
            tMotor.setText("Motor detenido · toca ▶ para empezar");
            bPlay.setText("▶");
            return;
        }
        tMotor.setText(m.motor());
        String t = m.titulo();
        if (m.cargando()) t = "⏳ Cargando… " + (m.reintentosActuales() > 0 ? "(se cayó la señal, retomando donde iba) " : "") + t;
        if (!t.isEmpty() && !t.contentEquals(tTitulo.getText())) tTitulo.setText(t);
        pintarCaratula();
        bPlay.setText(m.estaSonando() ? "⏸" : "▶");
        int d = m.duracion(), p = m.posicion();
        if (!arrastrando) { sbPos.setMax(Math.max(1, d)); sbPos.setProgress(p); }
        tTiempo.setText(hora(p) + " / " + hora(d));
        if (aj.global() && m.estaSonando()) {
            ponerSiCambia(tGlobal, "Mientras suena este reproductor, el refuerzo general descansa para no procesar el sonido dos veces "
                    + "(eso causaba cortes y vaivén). Vuelve solo al pausar, para YouTube, Spotify y videos.");
        } else if (aj.global()) {
            ponerSiCambia(tGlobal, m.globalConectado()
                    ? "Conectado a la salida del teléfono" + (m.otrasApps() > 0 ? " y a " + m.otrasApps() + " app(s) de música" : "") + "."
                    : "Este equipo no deja procesar la salida general; abre tu app de música y vuelve a activar.");
        } else {
            ponerSiCambia(tGlobal, "Apagado: el refuerzo solo se aplica a este reproductor.");
        }
        if (listaPintada != aj.actual()) pintarLista();
        String eq = aj.equipo() == 0
                ? "Automático: sonando en " + (m.equipoDetectado().isEmpty() ? "…" : m.equipoDetectado())
                : "Ecualizado para: " + Ajustes.EQUIPOS[aj.equipo()];
        if (!eq.contentEquals(tEquipo.getText())) { tEquipo.setText(eq); pintarEquipos(); }
        String mi = "♥ Mi lista (" + aj.miLista().size() + ")";
        if (!mi.contentEquals(bMiLista.getText())) bMiLista.setText(mi);
    }

    private void pintarLista() {
        List<Ajustes.Cancion> l = aj.lista();
        int act = aj.actual();
        listaPintada = act;
        if (l.isEmpty()) {
            lista.poner(1, i -> texto("La lista está vacía. Toca \"+ Agregar canciones\" (MP3, FLAC, WAV, AAC, OGG…).", 13, SUAVE));
            return;
        }
        lista.poner(l.size(), i -> {
            Ajustes.Cancion c = l.get(i);
            View v = filaPista(c.nombre, (i == act ? "♪ " : (i + 1) + ". "), i == act, Caratula.imagenDe(this, c.uri));
            v.setOnClickListener(x -> {
                aj.setActual(i);
                enviar(MotorService.A_TOCAR, i, -1);
                pintarLista();
            });
            return v;
        });
        lista.mostrar(act);
    }

    /** Una fila de pista con su caratula pequeña. */
    private LinearLayout filaPista(String nombre, String prefijo, boolean resaltar, String imagen) {
        String[] pt = Caratula.partes(nombre);
        LinearLayout it = fila();
        it.setGravity(Gravity.CENTER_VERTICAL);
        it.setPadding(dp(2), dp(4), dp(2), dp(4));
        ImageView iv = new ImageView(this);
        iv.setClipToOutline(true);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        iv.setBackground(g);
        Caratula.poner(iv, pt[0], pt[1], "1".equals(pt[2]), imagen, 96);
        it.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(10), 0, dp(4), 0);
        TextView a = texto(prefijo + pt[0], 15, resaltar ? ACENTO : TEXTO);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (resaltar) a.setTypeface(Typeface.DEFAULT_BOLD);
        txt.addView(a);
        if (!pt[1].isEmpty()) {
            TextView d = texto(pt[1], 12, SUAVE);
            d.setSingleLine(true);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(d);
        }
        it.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return it;
    }

    /** Caratula grande de lo que suena (solo cambia cuando cambia la cancion). */
    private void pintarCaratula() {
        List<Ajustes.Cancion> l = aj.lista();
        int act = aj.actual();
        if (l.isEmpty() || act < 0 || act >= l.size()) return;
        Ajustes.Cancion c = l.get(act);
        if (c.uri.equals(caratulaMostrada)) return;
        caratulaMostrada = c.uri;
        String[] pt = Caratula.partes(c.nombre);
        Caratula.poner(ivCaratula, pt[0], pt[1], "1".equals(pt[2]), Caratula.imagenDe(this, c.uri), 480);
    }

    private void ponerSiCambia(TextView t, String s) {
        if (!s.contentEquals(t.getText())) t.setText(s); // no redibuja la pantalla cada medio segundo (menos cortes)
    }

    private void pintarReproduccion() {
        boolean a = aj.aleatorio();
        bAleatorio.setText(a ? "🔀 Aleatorio: sí" : "🔀 Aleatorio: no");
        bAleatorio.setBackground(fondoBoton(a ? ACENTO : BOTON));
        bAleatorio.setTextColor(a ? Color.rgb(8, 20, 36) : TEXTO);
        int r = aj.repetir();
        bRepetir.setText(r == Ajustes.REPETIR_UNA ? "🔂 Repetir: una" : r == Ajustes.REPETIR_TODO ? "🔁 Repetir: todas" : "🔁 Repetir: no");
        bRepetir.setBackground(fondoBoton(r != Ajustes.REPETIR_NO ? ACENTO : BOTON));
        bRepetir.setTextColor(r != Ajustes.REPETIR_NO ? Color.rgb(8, 20, 36) : TEXTO);
    }

    // ---------- listas guardadas ----------
    private void guardarListaComo() {
        if (aj.lista().isEmpty()) {
            Toast.makeText(this, "La lista está vacía: agrega canciones primero", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText et = new EditText(this);
        et.setHint("Ej.: Vallenatos del viernes");
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Guardar lista (" + aj.lista().size() + " canciones)")
                .setView(et)
                .setPositiveButton("Guardar", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (n.isEmpty()) n = "Mi lista " + (aj.nombresListas().size() + 1);
                    final String nombre = n;
                    if (aj.nombresListas().contains(nombre)) {
                        new AlertDialog.Builder(this)
                                .setTitle("Ya existe \"" + nombre + "\"")
                                .setMessage("¿Reemplazarla con la lista actual?")
                                .setPositiveButton("Reemplazar", (d2, w2) -> { aj.guardarComo(nombre); avisar("Lista guardada: " + nombre); })
                                .setNegativeButton("Cancelar", null).show();
                    } else {
                        aj.guardarComo(nombre);
                        avisar("Lista guardada: " + nombre);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void misListas() {
        List<String> n = aj.nombresListas();
        if (n.isEmpty()) {
            Toast.makeText(this, "Aún no tienes listas guardadas. Usa \"Guardar lista\".", Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[n.size()];
        for (int i = 0; i < n.size(); i++) items[i] = n.get(i) + "  (" + aj.cantidadEn(n.get(i)) + ")";
        new AlertDialog.Builder(this)
                .setTitle("Mis listas")
                .setItems(items, (d, i) -> opcionesLista(n.get(i)))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void opcionesLista(String nombre) {
        new AlertDialog.Builder(this)
                .setTitle(nombre)
                .setItems(new String[]{"▶ Escuchar esta lista", "🗑 Borrar esta lista"}, (d, i) -> {
                    if (i == 0) {
                        if (aj.cargarLista(nombre)) {
                            enviar(MotorService.A_TOCAR, 0, -1);
                            pintarLista();
                            avisar("Sonando: " + nombre);
                        }
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("¿Borrar la lista \"" + nombre + "\"?")
                                .setMessage("Solo se borra la lista guardada; tus archivos de música no se tocan.")
                                .setPositiveButton("Borrar", (d2, w2) -> { aj.borrarLista(nombre); avisar("Lista borrada"); })
                                .setNegativeButton("Cancelar", null).show();
                    }
                })
                .show();
    }

    /** Busca si el telefono trae Dolby Atmos u otro efecto de fabrica (no se puede meter Dolby en la app: es con licencia). */
    private String efectosDelTelefono() {
        try {
            for (android.media.audiofx.AudioEffect.Descriptor d : android.media.audiofx.AudioEffect.queryEffects()) {
                String n = (d.name + " " + d.implementor).toLowerCase(Locale.ROOT);
                if (n.contains("dolby") || n.contains("atmos")) return "Dolby Atmos";
                if (n.contains("misound") || n.contains("mi sound")) return "Mi Sound";
                if (n.contains("dirac")) return "Dirac";
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ---------- actualizaciones ----------
    private void actualizar(boolean manual) {
        if (nueva != null && manual) { instalarNueva(); return; }
        if (manual) { tActualizar.setText("⬆️ Buscando versión nueva…"); tActualizar.setTextColor(SUAVE); }
        new Thread(() -> {
            Actualizador.Info i = null;
            String m;
            try {
                i = Actualizador.buscar(this);
                m = i == null ? "✅ Tienes la última versión (" + Actualizador.nombreActual(this) + ")."
                        : "🆕 Hay una versión nueva: " + i.nombre + ". Toca «Instalar» y queda encima, sin perder tus listas ni tus gustos.";
            } catch (Exception e) {
                m = "⬆️ No se pudo revisar ahora (" + e.getMessage() + "). Revisa el internet.";
            }
            final Actualizador.Info f = i;
            final String msj = m;
            runOnUiThread(() -> {
                nueva = f;
                if (manual || f != null) { tActualizar.setText(msj); tActualizar.setTextColor(f != null ? ACENTO : SUAVE); }
                bActualizar.setText(f != null ? "⬆️ Instalar " + f.nombre : "⬆️ Buscar actualización");
            });
        }, "VolumenPlus-actualizar").start();
    }

    private void instalarNueva() {
        if (!Actualizador.puedeInstalar(this)) {
            avisar("Activa «Permitir de esta fuente» para Volumen+ y vuelve a tocar Instalar.");
            Actualizador.pedirPermiso(this);
            return;
        }
        final Actualizador.Info i = nueva;
        bActualizar.setEnabled(false);
        tActualizar.setText("⬇️ Descargando " + i.nombre + "…");
        new Thread(() -> {
            String r = Actualizador.descargarEInstalar(this, i, p -> runOnUiThread(() ->
                    tActualizar.setText("⬇️ Descargando " + i.nombre + (p >= 0 ? ": " + p + " %" : "…"))));
            runOnUiThread(() -> {
                bActualizar.setEnabled(true);
                tActualizar.setText(r.isEmpty() ? "📲 Confirma la instalación en la ventana de Android. La música se detiene un momento mientras se instala." : r);
                tActualizar.setTextColor(r.isEmpty() ? ACENTO : ALERTA);
            });
        }, "VolumenPlus-instalar").start();
    }

    private static String nombreModo(int m) {
        return Ajustes.MODOS[m].replace("nitido", "nítido").replace("Maxima", "Máxima").replace("Pico Skorpion", "Picó Skorpión");
    }

    // ---------- lo que el DJ aprende de YouTube ----------
    private void pintarAprendido() {
        if (aprendido == null) return;
        for (int i = 0; i < chipsAprende.getChildCount(); i++) {
            Button b = (Button) chipsAprende.getChildAt(i);
            boolean on = i == temaAprendido;
            b.setBackground(fondoBoton(on ? ACENTO : BOTON));
            b.setTextColor(on ? Color.rgb(8, 20, 36) : TEXTO);
        }
        long f = AprendeYouTube.fecha(this);
        List<AprendeYouTube.Tema> l = AprendeYouTube.top(this, temaAprendido, 40);
        tAprende.setText(f == 0 ? "Todavía no he aprendido: toca «Aprender ahora» (usa unos 10 MB, mejor con wifi)."
                : "Aprendido el " + android.text.format.DateFormat.format("dd/MM/yyyy", f) + " · se refresca cada semana. Toca una y la busco completa.");
        aprendido.poner(l.size(), i -> {
            AprendeYouTube.Tema x = l.get(i);
            View v = filaPista(x.titulo + " · " + x.artista + (x.vistas > 0 ? " · " + AprendeYouTube.vistas(x.vistas) + " vistas" : ""),
                    (i + 1) + ". ", false, null);
            v.setOnClickListener(c -> tocarCancion(x.titulo, x.artista));
            return v;
        });
    }

    private void aprenderDeYouTube(boolean avisar) {
        if (tAprende != null) tAprende.setText("🧠 Aprendiendo de YouTube lo más sonado… (toma un minuto)");
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); // no le quita fuerza a la musica
            String r;
            try { r = AprendeYouTube.aprender(this); }
            catch (Exception e) { r = "No pude aprender de YouTube ahora (" + e.getMessage() + "). Si sigue fallando, configura el puente de Apps Script."; }
            final String msj = r;
            runOnUiThread(() -> {
                pintarAprendido();
                if (avisar) manos.responder(msj);
                else if (tAprende != null && AprendeYouTube.fecha(this) == 0) tAprende.setText(msj);
            });
        }, "VolumenPlus-aprende").start();
    }

    private void pedirPuente() {
        EditText et = new EditText(this);
        et.setHint("https://script.google.com/macros/s/…/exec");
        et.setText(AprendeYouTube.puente(this));
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Puente de YouTube (opcional)")
                .setMessage("Solo hace falta si YouTube no responde desde el teléfono. Es un Apps Script tuyo (apps-script/YouTubeAprende.gs del repositorio) publicado como aplicación web. Pega aquí su dirección /exec.")
                .setView(et)
                .setPositiveButton("Guardar", (d, w) -> {
                    String u = et.getText().toString().trim();
                    if (!u.isEmpty() && !u.startsWith("https://script.google.com/")) { avisar("La dirección debe empezar por https://script.google.com/"); return; }
                    AprendeYouTube.setPuente(this, u);
                    avisar(u.isEmpty() ? "Puente quitado" : "Puente guardado");
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void avisar(String t) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }

    // ---------- predictor ----------
    private final Runnable predecir = new Runnable() {
        @Override
        public void run() {
            final String q = etBuscar.getText().toString().trim();
            if (q.length() < 3) return;
            final int turno = ++turnoPredictor;
            new Thread(() -> {
                try {
                    List<EnLinea.Sugerencia> r = EnLinea.sugerencias(q);
                    runOnUiThread(() -> { if (turno == turnoPredictor) pintarSugerencias(r); });
                } catch (Exception ignored) {
                    // sin internet: no se muestran sugerencias
                }
            }).start();
        }
    };

    private void pintarSugerencias(List<EnLinea.Sugerencia> r) {
        sugeridas.removeAllViews();
        if (r.isEmpty()) return;
        sugeridas.addView(texto("¿Es alguna de estas?", 13, ACENTO));
        int max = Math.min(6, r.size());
        for (int i = 0; i < max; i++) {
            EnLinea.Sugerencia x = r.get(i);
            LinearLayout it = new LinearLayout(this);
            it.setOrientation(LinearLayout.VERTICAL);
            it.setPadding(dp(8), dp(6), dp(4), dp(6));
            TextView a = texto("🎤 " + x.titulo, 15, TEXTO);
            a.setTypeface(Typeface.DEFAULT_BOLD);
            it.addView(a);
            it.addView(texto(x.artista, 12, SUAVE));
            it.setOnClickListener(v -> elegirSugerencia(x));
            sugeridas.addView(it);
        }
    }

    private void elegirSugerencia(EnLinea.Sugerencia x) {
        sugeridas.removeAllViews();
        h.removeCallbacks(predecir);
        turnoPredictor++;
        tocarCancion(x.titulo, x.artista);
    }

    /**
     * Toca una cancion por titulo y artista: si hay version completa gratis suena aqui mismo;
     * si no, se abre en YouTube Music y Volumen+ le pone el refuerzo a todo el telefono.
     */
    private void tocarCancion(String titulo, String artista) {
        aj.sumarGusto("a", artista, 2);
        tLinea.setText("Buscando \"" + titulo + "\" completa…");
        new Thread(() -> {
            EnLinea.Resultado r = EnLinea.versionCompleta(titulo, artista);
            runOnUiThread(() -> {
                if (r != null) {
                    gusto(r, 1);
                    List<Ajustes.Cancion> una = new ArrayList<>();
                    una.add(cancionDe(r));
                    reproducirAhora(una, 0, "Sonando: " + r.titulo + " (" + r.artista + ")");
                } else {
                    abrirEnYoutubeMusic(titulo + " " + artista);
                }
            });
        }).start();
    }

    private void abrirEnYoutubeMusic(String q) {
        try {
            if (!aj.global()) { aj.setGlobal(true); swGlobal.setChecked(true); aplicar(); }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q))));
            tLinea.setText("Esa canción no es gratis completa fuera de las apps oficiales: se abrió en YouTube Music "
                    + "con el refuerzo de Volumen+ en todo el teléfono.");
        } catch (Exception e) {
            tLinea.setText("No hay versión completa gratis y no se pudo abrir YouTube Music.");
        }
    }

    // ---------- Para ti y Exitos de hoy ----------
    private void paraTi() {
        resultados.removeAllViews();
        sugeridas.removeAllViews();
        tLinea.setText(aj.tieneGustos() ? "Armando tu selección según lo que escuchas…" : "Aún no te conozco: te muestro lo más sonado de la semana. Mientras más escuches, mejor te recomiendo.");
        final List<String> g = aj.gustos("g", 3), a = aj.gustos("a", 4), q = aj.gustos("q", 2);
        new Thread(() -> {
            try {
                List<EnLinea.Resultado> r = EnLinea.paraTi(g, a, q);
                runOnUiThread(() -> {
                    pintarResultados(r, false);
                    if (!r.isEmpty()) tLinea.setText("Para ti: " + r.size() + " canciones completas, lo más nuevo primero. Toca una y suena.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> tLinea.setText("No se pudo cargar. Revisa la conexión a internet."));
            }
        }).start();
    }

    private void exitos() {
        resultados.removeAllViews();
        sugeridas.removeAllViews();
        tLinea.setText("Cargando los éxitos de hoy…");
        new Thread(() -> {
            try {
                List<EnLinea.Sugerencia> r = EnLinea.exitos();
                runOnUiThread(() -> {
                    tLinea.setText("Top de hoy (" + r.size() + "). Toca una: si hay versión completa gratis suena aquí; si no, se abre en YouTube Music con tu refuerzo.");
                    resultados.poner(r.size(), i -> {
                        EnLinea.Sugerencia x = r.get(i);
                        View it = filaPista(x.titulo + " · " + x.artista, (i + 1) + ". ", false, null);
                        it.setOnClickListener(v -> tocarCancion(x.titulo, x.artista));
                        return it;
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> tLinea.setText("No se pudieron cargar los éxitos. Revisa la conexión a internet."));
            }
        }).start();
    }

    // ---------- Mi lista ----------
    private void abrirMiLista() {
        List<Ajustes.Cancion> l = aj.miLista();
        if (l.isEmpty()) {
            avisar("Mi lista está vacía: toca + en una canción o agrega archivos.");
            return;
        }
        String[] items = new String[l.size() + 1];
        items[0] = "▶ Escuchar toda Mi lista (" + l.size() + ")";
        for (int i = 0; i < l.size(); i++) items[i + 1] = (i + 1) + ". " + l.get(i).nombre;
        new AlertDialog.Builder(this)
                .setTitle("♥ Mi lista")
                .setItems(items, (d, i) -> {
                    if (i == 0) reproducirAhora(l, 0, "Sonando Mi lista");
                    else opcionesMiLista(l, i - 1);
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void opcionesMiLista(List<Ajustes.Cancion> l, int i) {
        new AlertDialog.Builder(this)
                .setTitle(l.get(i).nombre)
                .setItems(new String[]{"▶ Escuchar desde aquí", "Quitar de Mi lista"}, (d, w) -> {
                    if (w == 0) reproducirAhora(l, i, "Sonando Mi lista");
                    else new AlertDialog.Builder(this)
                            .setTitle("¿Quitar de Mi lista?")
                            .setMessage(l.get(i).nombre + "\n\nEl archivo no se borra del teléfono.")
                            .setPositiveButton("Quitar", (d2, w2) -> {
                                List<Ajustes.Cancion> m = aj.miLista();
                                m.remove(i);
                                aj.guardarMiLista(m);
                                avisar("Quitada de Mi lista");
                            })
                            .setNegativeButton("Cancelar", null).show();
                })
                .show();
    }

    // ---------- musica en linea ----------
    private void buscar(boolean radio) {
        h.removeCallbacks(predecir);
        turnoPredictor++;
        sugeridas.removeAllViews();
        final String q = etBuscar.getText().toString();
        final EnLinea.Sugerencia mu = radio ? null : muestra;
        muestra = null;
        tLinea.setText(radio ? "Buscando emisoras…" : "Buscando canciones…");
        resultados.removeAllViews();
        new Thread(() -> {
            try {
                if (!radio && !q.trim().isEmpty()) aj.sumarGusto("q", q.trim().toLowerCase(Locale.ROOT), 1);
                List<EnLinea.Resultado> r = radio ? EnLinea.buscarRadios(q) : EnLinea.buscarTodo(q, aj.flac());
                runOnUiThread(() -> pintarResultados(r, radio));
            } catch (Exception e) {
                runOnUiThread(() -> tLinea.setText("No se pudo buscar. Revisa la conexión a internet. (" + e.getMessage() + ")"));
            }
        }).start();
    }

    private void pintarResultados(List<EnLinea.Resultado> r, boolean radio) {
        resultados.removeAllViews();
        tLinea.setText(r.isEmpty() ? (radio ? "Sin emisoras. Prueba con otras palabras o un género."
                : "No hay una versión completa y gratuita de esa canción. Prueba otra o busca una emisora de ese género.")
                : r.size() + (radio ? " emisoras. Toca una para escucharla." : " canciones completas. Toca una y suena; con + la guardas en Mi lista."));
        ultimos = r;
        resultados.poner(r.size(), i -> {
            EnLinea.Resultado x = r.get(i);
            LinearLayout it = filaPista((x.radio ? "📻 " : "") + x.titulo + (x.artista.isEmpty() ? "" : " · " + x.artista),
                    x.aprendida ? "🔥 " : "", false, x.imagen);
            it.getChildAt(1).setOnClickListener(v -> elegirResultado(x));
            it.getChildAt(0).setOnClickListener(v -> elegirResultado(x));
            if (x.directo || x.radio) {
                Button mas = boton("+", v -> {
                    boolean nueva = aj.agregarAMiLista(cancionDe(x));
                    if (!x.radio) gusto(x, 3);
                    avisar(nueva ? "Agregada a Mi lista" : "Ya está en Mi lista");
                });
                it.addView(mas, new LinearLayout.LayoutParams(dp(52), dp(44)));
            }
            return it;
        });
    }

    private Ajustes.Cancion cancionDe(EnLinea.Resultado x) {
        Caratula.recordar(this, x.id, x.imagen);
        if (x.radio) return new Ajustes.Cancion(x.id, "📻 " + x.titulo);
        return new Ajustes.Cancion(x.id, x.titulo + (x.artista.isEmpty() ? "" : " · " + x.artista));
    }

    /** La app aprende: genero y artista de lo que tocas o guardas. */
    private void gusto(EnLinea.Resultado x, int puntos) {
        aj.sumarGusto("g", x.genero, puntos);
        aj.sumarGusto("a", x.artista, puntos);
    }

    /** Tocar = suena de una vez. La cola pasa a ser los resultados desde esa cancion (como en Spotify). */
    private void reproducirAhora(List<Ajustes.Cancion> cola, int i, String aviso) {
        if (cola.isEmpty()) return;
        aj.guardarLista(cola);
        aj.setActual(i);
        enviar(MotorService.A_TOCAR, i, -1);
        tLinea.setText(aviso);
        listaPintada = -2;
        pintarLista();
    }

    private void elegirResultado(EnLinea.Resultado x) {
        if (x.directo) {
            gusto(x, 1);
            List<Ajustes.Cancion> cola = new ArrayList<>();
            int i = 0;
            for (EnLinea.Resultado y : ultimos) {
                if (!y.directo) continue;
                if (y == x) i = cola.size();
                cola.add(cancionDe(y));
            }
            reproducirAhora(cola, i, "Sonando: " + x.titulo + ". Siguen las demás de esta búsqueda.");
            return;
        }
        if (x.radio) {
            List<Ajustes.Cancion> una = new ArrayList<>();
            una.add(cancionDe(x));
            reproducirAhora(una, 0, "Sonando: " + x.titulo);
            return;
        }
        final boolean max = aj.flac();
        tLinea.setText("Cargando pistas de \"" + x.titulo + "\"…");
        new Thread(() -> {
            try {
                List<Ajustes.Cancion> p = EnLinea.pistas(x.id, x.titulo, max);
                runOnUiThread(() -> {
                    if (p.isEmpty()) tLinea.setText("Esa grabación no tiene pistas de audio para reproducir. Prueba otra.");
                    else reproducirAhora(p, 0, "Sonando el álbum \"" + x.titulo + "\"");
                });
            } catch (Exception e) {
                runOnUiThread(() -> tLinea.setText("No se pudieron cargar las pistas. (" + e.getMessage() + ")"));
            }
        }).start();
    }

    private void agregarYTocar(List<Ajustes.Cancion> nuevas) {
        List<Ajustes.Cancion> l = aj.lista();
        int primero = -1;
        for (Ajustes.Cancion c : nuevas) {
            int ya = -1;
            for (int k = 0; k < l.size(); k++) if (l.get(k).uri.equals(c.uri)) { ya = k; break; }
            if (ya < 0) { l.add(c); ya = l.size() - 1; }
            if (primero < 0) primero = ya;
        }
        aj.guardarLista(l);
        if (primero >= 0) {
            aj.setActual(primero);
            enviar(MotorService.A_TOCAR, primero, -1);
        }
        tLinea.setText(nuevas.size() == 1 ? "Sonando: " + nuevas.get(0).nombre
                : nuevas.size() + " pistas agregadas a tu lista. Sonando la primera.");
        pintarLista();
    }

    // ---------- acciones ----------
    private void aplicar() {
        enviar(MotorService.A_APLICAR, -1, -1);
        pintarModos();
    }

    // ---------- guardian ----------
    private View pantallaNoOficial() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(FONDO);
        l.setPadding(dp(24), dp(60), dp(24), dp(24));
        TextView t = texto("🚫 Copia no oficial", 24, ALERTA);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        l.addView(t);
        l.addView(texto("Esta copia de Volumen+ fue modificada o no viene de la Central de Luis, así que no se abre "
                + "para proteger tu teléfono. Desinstálala e instala la versión oficial desde la Central.", 16, TEXTO));
        return l;
    }

    private void revisarEnSegundoPlano(String motivo) {
        new Thread(() -> {
            Guardian.Informe inf = Guardian.revisar(this, motivo);
            runOnUiThread(() -> pintarGuardian(inf));
        }).start();
    }

    private void pintarGuardian(Guardian.Informe inf) {
        if (tGuardian == null) return;
        tGuardian.setText(inf.resumen());
        tGuardian.setTextColor(inf.fallas > 0 || inf.modoSeguro ? ALERTA : inf.reparados > 0 ? ACENTO : SUAVE);
        bSeguro.setVisibility(inf.modoSeguro ? View.VISIBLE : View.GONE);
    }

    private void verInforme() {
        String inf = Guardian.ultimoInforme(this);
        String fallo = Guardian.ultimoFallo(this);
        String buena = Guardian.versionBuena(this);
        final String todo = inf + (buena.isEmpty() ? "" : "\nÚltima versión buena guardada: " + buena + "\n")
                + (fallo.isEmpty() ? "" : "\nÚltimo cierre inesperado:\n" + fallo);
        TextView t = texto(todo.isEmpty() ? "Aún no hay informe." : todo, 13, TEXTO);
        t.setPadding(dp(18), dp(12), dp(18), dp(12));
        t.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle("🛡 Informe del guardián")
                .setView(sv)
                .setPositiveButton("Copiar para Claude", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Informe Volumen+", todo));
                    avisar("Copiado: pégalo en el chat con Claude para arreglarlo");
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (oido != null) try { oido.destroy(); } catch (Exception ignored) { }
        if (locutor != null) locutor.cerrar();
        super.onDestroy();
    }

    // =====================================================================
    //  AGENTE DJ
    // =====================================================================
    private TextView subtitulo(String s) {
        TextView t = texto(s, 14, TEXTO);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    /** Fila de botones que se desliza a los lados. */
    private LinearLayout chips(LinearLayout padre) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout f = fila();
        hs.addView(f);
        padre.addView(hs);
        return f;
    }

    private void pintarEquipos() {
        for (int i = 0; i < equipos.getChildCount(); i++) {
            Button b = (Button) equipos.getChildAt(i);
            boolean on = i == aj.equipo();
            b.setBackground(fondoBoton(on ? ACENTO : BOTON));
            b.setTextColor(on ? Color.rgb(8, 20, 36) : TEXTO);
        }
    }

    private void oirFrase(String f) {
        if (f == null || f.trim().isEmpty()) return;
        tDj.setText("Tú: " + f.trim());
        agente.escuchar(f);
    }

    /** Escucha una orden por voz (reconocimiento de voz del telefono, en español). */
    private void escucharVoz() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 20);
            return;
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            tDj.setText("Este teléfono no tiene reconocimiento de voz. Instala o actualiza la app de Google y vuelve a intentar.");
            return;
        }
        if (oyendo) { try { oido.stopListening(); } catch (Exception ignored) { } return; }
        if (oido == null) {
            oido = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            oido.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) { tDj.setText("🎤 Te escucho…"); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float r) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { tDj.setText("Pensando…"); }
                @Override public void onError(int e) {
                    finOido();
                    tDj.setText(e == android.speech.SpeechRecognizer.ERROR_NO_MATCH || e == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ? "No te escuché bien. Toca el micrófono y habla cerca del teléfono."
                            : e == android.speech.SpeechRecognizer.ERROR_NETWORK || e == android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                            ? "Sin internet para entender la voz. Escríbele al DJ."
                            : "El micrófono no respondió (" + e + "). Intenta de nuevo.");
                }
                @Override public void onResults(Bundle r) {
                    finOido();
                    java.util.ArrayList<String> l = r.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if (l != null && !l.isEmpty()) oirFrase(l.get(0));
                }
                @Override public void onPartialResults(Bundle r) { }
                @Override public void onEvent(int t, Bundle b) { }
            });
        }
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "es-CO");
        i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        MotorService m = MotorService.yo;
        if (m != null) m.bajarParaHablar(true); // la musica baja para que el DJ te oiga
        oyendo = true;
        bHablar.setText("⏹ Escuchando… (toca para terminar)");
        try { oido.startListening(i); } catch (Exception e) { finOido(); tDj.setText("No se pudo abrir el micrófono."); }
    }

    private void finOido() {
        oyendo = false;
        bHablar.setText("🎤 Háblale al DJ");
        MotorService m = MotorService.yo;
        if (m != null) m.bajarParaHablar(false);
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos, int[] r) {
        super.onRequestPermissionsResult(codigo, permisos, r);
        if (codigo == 20) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) escucharVoz();
            else tDj.setText("Sin permiso del micrófono no te puedo oír. Puedes escribirle al DJ.");
        }
    }

    private boolean sonandoAhora() { MotorService m = MotorService.yo; return m != null && m.estaSonando(); }

    /** Lo que hace el agente en la app. */
    private final Agente.Manos manos = new Agente.Manos() {
        @Override public void responder(String t) {
            tDj.setText("DJ: " + t);
            MotorService m = MotorService.yo;
            if (m != null && m.estaSonando()) {
                m.bajarParaHablar(true);
                h.postDelayed(() -> { MotorService m2 = MotorService.yo; if (m2 != null) m2.bajarParaHablar(false); }, 900L + t.length() * 65L);
            }
            locutor.decir(t);
        }

        @Override public void tocar(String q) {
            responder("Buscando " + q + "…");
            aj.sumarGusto("q", q, 1);
            new Thread(() -> {
                List<EnLinea.Resultado> r = new ArrayList<>();
                try { r = EnLinea.buscarAudius(q); } catch (Exception ignored) { }
                String nq = EnLinea.normalPublico(q);
                List<EnLinea.Resultado> buenas = new ArrayList<>();
                for (EnLinea.Resultado x : r) {
                    String t = EnLinea.normalPublico(x.titulo + " " + x.artista);
                    if (t.contains(nq)) buenas.add(x);
                }
                final List<EnLinea.Resultado> fin = buenas;
                if (!fin.isEmpty()) {
                    runOnUiThread(() -> {
                        pintarResultados(fin, false);
                        elegirResultado(fin.get(0));
                        responder("Sonando " + fin.get(0).titulo + ".");
                    });
                    return;
                }
                // no hay version libre: se identifica la cancion y se busca completa o se abre en YouTube Music
                try {
                    List<EnLinea.Sugerencia> s = EnLinea.sugerencias(q);
                    if (!s.isEmpty()) { runOnUiThread(() -> tocarCancion(s.get(0).titulo, s.get(0).artista)); return; }
                } catch (Exception ignored) { }
                runOnUiThread(() -> responder("No encontré " + q + ". Prueba con el nombre de la canción y el artista."));
            }).start();
        }

        @Override public void fiesta(int estilo) {
            String nombre = EnLinea.FIESTAS[estilo];
            responder("¡Armando la mejor fiesta de " + nombre + "! Dame unos segundos.");
            tLinea.setText("El DJ está escogiendo canciones de " + nombre + " por energía, tempo y tono…");
            resultados.removeAllViews();
            new Thread(() -> {
                try {
                    List<EnLinea.Resultado> r = EnLinea.listaFiesta(estilo, 40, AprendeYouTube.paraFiesta(MainActivity.this, estilo, 20));
                    runOnUiThread(() -> {
                        if (r.isEmpty()) { responder("No encontré canciones para esa fiesta. Revisa el internet."); return; }
                        aj.setTransiciones(true);
                        aj.setAleatorio(false);
                        pintarReproduccion();
                        pintarResultados(r, false);
                        List<Ajustes.Cancion> cola = new ArrayList<>();
                        for (EnLinea.Resultado x : r) cola.add(cancionDe(x));
                        reproducirAhora(cola, 0, "Fiesta de " + nombre + ": " + r.size() + " canciones. Arranca suave, sube al pico y cierra arriba.");
                        aj.guardarComo("Fiesta " + nombre);
                        locutor.saludo("¡Arrancamos la fiesta de " + nombre + "! ¡Todo el mundo a bailar!", true);
                        tDj.setText("DJ: Fiesta de " + nombre + " lista (" + r.size() + " canciones). Quedó guardada en Mis listas.");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> responder("No pude armar la fiesta. Revisa la conexión a internet."));
                }
            }).start();
        }

        @Override public void nueva(String genero) {
            responder("Buscando lo más nuevo y lo que es tendencia.");
            new Thread(() -> {
                try {
                    List<EnLinea.Resultado> r = EnLinea.musicaNueva(genero);
                    runOnUiThread(() -> {
                        pintarResultados(r, false);
                        if (!r.isEmpty()) elegirResultado(r.get(0));
                        tLinea.setText("Estrenos y tendencias: " + r.size() + " canciones completas, lo más nuevo primero.");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> responder("No pude cargar la música nueva. Revisa el internet."));
                }
            }).start();
        }

        @Override public void exitos() { responder("Estos son los éxitos de hoy."); MainActivity.this.exitos(); }

        @Override public void paraTi() { responder("Te armo algo con lo que te gusta."); MainActivity.this.paraTi(); }

        @Override public void emisora(String q) {
            responder("Buscando emisoras" + (q.isEmpty() ? "" : " de " + q) + ".");
            escribiendoYo = true;
            etBuscar.setText(q);
            escribiendoYo = false;
            buscar(true);
        }

        @Override public void control(String a) {
            switch (a) {
                case "pausa": if (sonandoAhora()) enviar(MotorService.A_PAUSA, -1, -1); tDj.setText("DJ: En pausa."); break;
                case "sigue": if (!sonandoAhora()) enviar(MotorService.A_PAUSA, -1, -1); tDj.setText("DJ: ¡Sigue la música!"); break;
                case "siguiente": enviar(MotorService.A_SIG, -1, -1); tDj.setText("DJ: La que sigue."); break;
                case "anterior": enviar(MotorService.A_ANT, -1, -1); tDj.setText("DJ: La anterior."); break;
                case "aleatorio": aj.setAleatorio(!aj.aleatorio()); pintarReproduccion(); responder(aj.aleatorio() ? "Aleatorio activado." : "Aleatorio apagado."); break;
                case "repetir": aj.setRepetir(Ajustes.REPETIR_UNA); pintarReproduccion(); responder("Repito esta canción."); break;
                default: break;
            }
        }

        @Override public void volumen(int pasos) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            for (int i = 0; i < Math.abs(pasos); i++) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, pasos > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            }
            tDj.setText(pasos > 0 ? "DJ: ¡Más duro!" : "DJ: Un poquito más suave.");
        }

        @Override public void bajo(int pasos) {
            aj.setBajoExtra(aj.bajoExtra() + pasos);
            cargarControles();
            aplicar();
            responder("Bajo extra en " + aj.bajoExtra() + ".");
        }

        @Override public void potencia(int pasos) {
            aj.setPotencia(Math.max(0, Math.min(Ajustes.MAX_POTENCIA, aj.potencia() + pasos)));
            cargarControles();
            aplicar();
            responder("Potencia en más " + aj.potencia() + ".");
        }

        @Override public void aprender() { aprenderDeYouTube(true); }

        @Override public void modo(int modo) {
            aj.setModo(modo);
            cargarControles();
            aplicar();
            responder("Modo " + nombreModo(modo) + ".");
        }

        @Override public void equipo(int e) {
            aj.setEquipo(e);
            pintarEquipos();
            aplicar();
            responder(e == 0 ? "Ecualización automática: detecto dónde está sonando." : "Ecualizado para " + Ajustes.EQUIPOS[e].toLowerCase(Locale.ROOT) + ".");
        }

        @Override public void efecto(int e) {
            Efectos.tocar(e);
            tDj.setText("DJ: ¡" + Efectos.NOMBRES[e] + "!");
        }

        @Override public void saludo(String t) {
            tDj.setText("DJ: " + t);
            if (!locutor.listo()) { avisar("La voz del teléfono aún está cargando, intenta en un momento"); return; }
            locutor.saludo(t, true);
        }

        @Override public void calidad(boolean max) {
            aj.setFlac(max);
            swFlac.setChecked(max);
            responder(max ? "Máxima calidad: incluyo álbumes en FLAC sin pérdida." : "Calidad normal para gastar menos datos.");
        }

        @Override public void revisar() {
            tDj.setText("DJ: Revisando toda la app…");
            new Thread(() -> {
                Guardian.Informe inf = Guardian.revisar(MainActivity.this, "se lo pediste al DJ");
                runOnUiThread(() -> {
                    pintarGuardian(inf);
                    responder(inf.fallas == 0 && inf.reparados == 0 ? "Revisé todo: la app está en perfecto estado."
                            : inf.fallas == 0 ? "Revisé todo y reparé " + inf.reparados + " cosa" + (inf.reparados == 1 ? "" : "s") + ". Ya quedó bien."
                            : "Encontré " + inf.fallas + " problema" + (inf.fallas == 1 ? "" : "s") + ". Mira el informe y cópialo para Claude.");
                });
            }).start();
        }

        @Override public void queSuena() {
            MotorService m = MotorService.yo;
            String t = m != null && !m.titulo().isEmpty() ? m.titulo() : "";
            responder(t.isEmpty() ? "Ahora no está sonando nada." : "Está sonando " + t.replace(" · ", " de ") + ".");
        }
    };

    /** Envia una orden al motor. indice: cancion a tocar; ms: posicion (usa -1 si no aplica). */
    private void enviar(String accion, int indice, int ms) {
        Intent i = new Intent(this, MotorService.class).setAction(accion);
        if (indice >= 0) i.putExtra("i", indice);
        if (ms >= 0) i.putExtra("ms", ms);
        try {
            startForegroundService(i);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo iniciar el motor de sonido: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void elegirCanciones() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, PEDIR_AUDIO);
        } catch (Exception e) {
            Toast.makeText(this, "No hay un administrador de archivos disponible", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PEDIR_AUDIO || res != RESULT_OK || data == null) return;
        List<Ajustes.Cancion> l = aj.lista();
        int antes = l.size();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int k = 0; k < cd.getItemCount(); k++) agregar(l, cd.getItemAt(k).getUri());
        } else if (data.getData() != null) {
            agregar(l, data.getData());
        }
        aj.guardarLista(l);
        int nuevas = l.size() - antes;
        for (int k = antes; k < l.size(); k++) aj.agregarAMiLista(l.get(k));
        Toast.makeText(this, nuevas + (nuevas == 1 ? " canción agregada (también a Mi lista)" : " canciones agregadas (también a Mi lista)"), Toast.LENGTH_SHORT).show();
        if (antes == 0 && nuevas > 0) {
            aj.setActual(0);
            enviar(MotorService.A_TOCAR, 0, -1);
        }
        pintarLista();
    }

    private void agregar(List<Ajustes.Cancion> l, Uri u) {
        if (u == null) return;
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // algunos archivos solo dan permiso temporal; igual se intentan reproducir
        }
        for (Ajustes.Cancion c : l) if (c.uri.equals(u.toString())) return;
        l.add(new Ajustes.Cancion(u.toString(), nombreDe(u)));
    }

    private void abrirDesdeOtraApp(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return;
        List<Ajustes.Cancion> l = aj.lista();
        agregar(l, i.getData());
        aj.guardarLista(l);
        int idx = 0;
        for (int k = 0; k < l.size(); k++) if (l.get(k).uri.equals(i.getData().toString())) idx = k;
        aj.setActual(idx);
        enviar(MotorService.A_TOCAR, idx, -1);
        pintarLista();
    }

    private String nombreDe(Uri u) {
        String n = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) {
            // sin nombre: se usa el final de la direccion
        }
        if (n == null) n = u.getLastPathSegment() != null ? u.getLastPathSegment() : "Canción";
        int punto = n.lastIndexOf('.');
        return punto > 0 ? n.substring(0, punto) : n;
    }

    private void volumenMax() {
        AudioManager a = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (a == null) return;
        a.setStreamVolume(AudioManager.STREAM_MUSIC, a.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI);
    }

    // ---------- piezas de pantalla ----------
    private LinearLayout tarjeta(LinearLayout raiz, String titulo) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(TARJETA);
        g.setCornerRadius(dp(16));
        c.setBackground(g);
        TextView t = texto(titulo, 19, ACENTO);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        raiz.addView(c, lp);
        return c;
    }

    private TextView texto(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private Button boton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TEXTO);
        b.setTextSize(15);
        b.setBackground(fondoBoton(BOTON));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        lp.setMargins(dp(4), dp(6), dp(4), dp(6));
        b.setLayoutParams(lp);
        b.setPadding(dp(14), 0, dp(14), 0);
        return b;
    }

    private GradientDrawable fondoBoton(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(23));
        return g;
    }

    private Switch interruptor(String s, boolean on, Cambio c) {
        Switch w = new Switch(this);
        w.setText(s);
        w.setTextColor(TEXTO);
        w.setTextSize(15);
        w.setChecked(on);
        w.setPadding(0, dp(10), 0, dp(4));
        w.setOnCheckedChangeListener((b, v) -> c.cambio(v));
        return w;
    }

    private LinearLayout fila() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        return f;
    }

    private LinearLayout.LayoutParams peso() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(4), dp(6), dp(4), dp(6));
        return lp;
    }

    private View espacio(int alto) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(alto)));
        return v;
    }

    private String hora(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private interface Cambio { void cambio(boolean v); }

    private abstract static class Barra implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar s, int p, boolean u) { }
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }
}
