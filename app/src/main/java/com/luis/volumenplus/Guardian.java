package com.luis.volumenplus;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Guardian del Agente DJ: revisa la app cada vez que se instala una actualizacion (y al abrirla).
 * - Comprueba que el codigo sea el oficial (firma de Luis). Una copia modificada no se ejecuta.
 * - Revisa y repara los ajustes, las listas y los gustos (si algo esta danado lo restaura del respaldo).
 * - Prueba el motor de sonido, los efectos, la voz y el microfono.
 * - Si una version falla al arrancar, entra en modo seguro y conserva tus datos de la ultima version buena.
 * El codigo solo cambia cuando se compila una version nueva desde GitHub con la llave oficial.
 */
public final class Guardian {
    private static final String TAG = "VolumenPro";
    /** SHA-256 del certificado oficial (la llave con la que se compila cada version). */
    public static final String FIRMA_OFICIAL = "2E1250F3EAC61AA7A142A95E869B97E7EBE076A085CB69485898318E0D8CC547";
    private static final String PREFS = "volumen_pro";
    private static final String RESPALDO = "volumen_pro_respaldo";
    private static final String CONTROL = "volumen_pro_guardian";

    private Guardian() { }

    // ---------------------------------------------------------------------
    public static final class Informe {
        public final List<String> lineas = new ArrayList<>();
        public int reparados = 0, fallas = 0;
        public boolean firmaOk = true, modoSeguro = false;
        public String version = "";
        void ok(String s) { lineas.add("✅ " + s); }
        void reparo(String s) { lineas.add("🔧 " + s); reparados++; }
        void falla(String s) { lineas.add("⚠️ " + s); fallas++; }
        void info(String s) { lineas.add("ℹ️ " + s); }

        public String resumen() {
            if (!firmaOk) return "🚫 Esta copia NO es la oficial. Instala Volumen+ desde la Central de Luis.";
            if (modoSeguro) return "🛟 Modo seguro: la versión " + version + " falló al arrancar. Tus datos están a salvo.";
            if (fallas > 0) return "⚠️ Versión " + version + ": " + fallas + " problema(s)" + (reparados > 0 ? ", " + reparados + " reparado(s)" : "") + ".";
            if (reparados > 0) return "🔧 Versión " + version + ": reparé " + reparados + " cosa(s). Todo quedó bien.";
            return "🛡 Versión " + version + ": todo en orden.";
        }

        public String texto() {
            StringBuilder b = new StringBuilder(resumen()).append('\n');
            for (String l : lineas) b.append(l).append('\n');
            return b.toString();
        }
    }

    // ---------------------------------------------------------------------
    //  Firma: nadie en el telefono puede cambiar el codigo
    // ---------------------------------------------------------------------
    public static String firmaActual(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] firmas;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                firmas = pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
            } else {
                firmas = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            StringBuilder todas = new StringBuilder();
            for (Signature s : firmas) {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
                StringBuilder h = new StringBuilder();
                for (byte x : d) h.append(String.format("%02X", x));
                todas.append(h).append(';');
            }
            return todas.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean esOficial(Context c) {
        return firmaActual(c).contains(FIRMA_OFICIAL);
    }

    public static long version(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String nombreVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    // ---------------------------------------------------------------------
    //  Fallos: si la app se cae, queda anotado para repararla
    // ---------------------------------------------------------------------
    public static void vigilarFallos(Context c) {
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler anterior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((hilo, error) -> {
            try {
                SharedPreferences k = app.getSharedPreferences(CONTROL, Context.MODE_PRIVATE);
                long v = version(app);
                boolean alArrancar = System.currentTimeMillis() - k.getLong("inicio", 0) < 60_000;
                k.edit().putInt("fallos_" + v, k.getInt("fallos_" + v, 0) + (alArrancar ? 1 : 0))
                        .putInt("fallos_total_" + v, k.getInt("fallos_total_" + v, 0) + 1).commit();
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                String t = "Volumen+ " + nombreVersion(app) + " (" + v + ") · Android " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL
                        + "\nHilo: " + hilo.getName() + "\n" + sw;
                try (FileOutputStream o = new FileOutputStream(new File(app.getFilesDir(), "ultimo_fallo.txt"))) {
                    o.write(t.getBytes("UTF-8"));
                }
            } catch (Throwable ignored) { }
            if (anterior != null) anterior.uncaughtException(hilo, error);
        });
    }

    public static String ultimoFallo(Context c) {
        File f = new File(c.getFilesDir(), "ultimo_fallo.txt");
        if (!f.exists()) return "";
        try {
            byte[] d = new byte[(int) Math.min(f.length(), 8000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int n = in.read(d); return new String(d, 0, Math.max(0, n), "UTF-8"); }
        } catch (Exception e) { return ""; }
    }

    public static boolean modoSeguro(Context c) {
        return c.getSharedPreferences(CONTROL, Context.MODE_PRIVATE).getBoolean("seguro", false);
    }

    public static void salirDeModoSeguro(Context c) {
        SharedPreferences k = c.getSharedPreferences(CONTROL, Context.MODE_PRIVATE);
        k.edit().putBoolean("seguro", false).putInt("fallos_" + version(c), 0).apply();
    }

    /** Se llama al abrir la app: anota la hora para saber si un fallo fue "al arrancar". */
    public static void arranque(Context c) {
        c.getSharedPreferences(CONTROL, Context.MODE_PRIVATE).edit().putLong("inicio", System.currentTimeMillis()).apply();
    }

    /** Despues de un minuto sin fallas: esta version queda como "buena" y se respaldan tus datos. */
    public static void marcarBuena(Context c) {
        if (!esOficial(c) || modoSeguro(c)) return;
        respaldar(c);
        c.getSharedPreferences(CONTROL, Context.MODE_PRIVATE).edit()
                .putLong("buena", version(c)).putString("buena_nombre", nombreVersion(c)).apply();
    }

    // ---------------------------------------------------------------------
    //  Respaldo de tus datos (ajustes, Mi lista, listas guardadas, gustos)
    // ---------------------------------------------------------------------
    private static void respaldar(Context c) {
        SharedPreferences o = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor r = c.getSharedPreferences(RESPALDO, Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, ?> e : o.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof String) r.putString(e.getKey(), (String) v);
            else if (v instanceof Integer) r.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Boolean) r.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Long) r.putLong(e.getKey(), (Long) v);
            else if (v instanceof Float) r.putFloat(e.getKey(), (Float) v);
        }
        r.putLong("_version", version(c)).apply();
    }

    private static String delRespaldo(Context c, String clave) {
        try { return c.getSharedPreferences(RESPALDO, Context.MODE_PRIVATE).getString(clave, null); } catch (Exception e) { return null; }
    }

    // ---------------------------------------------------------------------
    //  Revision completa
    // ---------------------------------------------------------------------
    public static Informe revisar(Context c, String motivo) {
        Informe inf = new Informe();
        inf.version = nombreVersion(c);
        SharedPreferences k = c.getSharedPreferences(CONTROL, Context.MODE_PRIVATE);
        long v = version(c);
        long antes = k.getLong("revisada", 0);
        if (antes != 0 && antes != v) inf.info("Actualización detectada: de " + k.getString("revisada_nombre", "?") + " a " + inf.version + ".");
        inf.info("Motivo de la revisión: " + motivo + ".");

        // 1. Codigo oficial
        inf.firmaOk = esOficial(c);
        if (inf.firmaOk) inf.ok("Código oficial y sin cambios (firma verificada).");
        else { inf.falla("La firma no coincide: esta copia fue modificada o no es la oficial."); return guardarInforme(c, inf); }

        // 2. Fallos al arrancar -> modo seguro
        int fallos = k.getInt("fallos_" + v, 0);
        if (fallos >= 2 && !k.getBoolean("seguro", false)) {
            k.edit().putBoolean("seguro", true).apply();
            inf.reparo("Esta versión falló " + fallos + " veces al arrancar: activé el modo seguro (sonido compatible).");
        }
        inf.modoSeguro = k.getBoolean("seguro", false);
        if (!inf.modoSeguro) {
            int total = k.getInt("fallos_total_" + v, 0);
            if (total == 0) inf.ok("Sin fallas registradas en esta versión.");
            else inf.info("Hubo " + total + " cierre(s) inesperado(s) en esta versión. El informe queda para repararlo.");
        }

        // 3. Ajustes y datos
        revisarAjustes(c, inf);

        // 4. Motor de sonido
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int sesion = am.generateAudioSessionId();
            Cadena prueba = new Cadena(sesion, true);
            String m = prueba.motor();
            prueba.aplicar(true, 8, new int[10], 300, true, true, 5);
            prueba.liberar();
            inf.ok("Motor de sonido funcionando: " + m + ".");
        } catch (Throwable t) {
            inf.falla("El motor de sonido no arrancó (" + t.getClass().getSimpleName() + "). Se usará el modo compatible.");
            k.edit().putBoolean("seguro", true).apply();
            inf.modoSeguro = true;
        }

        // 5. Efectos de DJ
        int malos = 0;
        for (int i = 0; i < Efectos.NOMBRES.length; i++) {
            try {
                short[] s = Efectos.generar(i);
                int pico = 0;
                for (short x : s) pico = Math.max(pico, Math.abs(x));
                if (s.length < 1000 || pico < 1000) malos++;
            } catch (Throwable t) { malos++; }
        }
        if (malos == 0) inf.ok("Los " + Efectos.NOMBRES.length + " efectos de DJ suenan bien.");
        else inf.falla(malos + " efecto(s) de DJ no se pudieron crear.");

        // 6. Voz y microfono
        boolean tts = !c.getPackageManager().queryIntentServices(new Intent("android.intent.action.TTS_SERVICE"), 0).isEmpty();
        if (tts) inf.ok("Voz del locutor disponible.");
        else inf.falla("El teléfono no tiene motor de voz: instala \"Servicios de voz de Google\" para los saludos.");
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(c)) inf.ok("Reconocimiento de voz disponible.");
        else inf.falla("No hay reconocimiento de voz: instala o actualiza la app de Google.");

        k.edit().putLong("revisada", v).putString("revisada_nombre", inf.version).apply();
        return guardarInforme(c, inf);
    }

    private static Informe guardarInforme(Context c, Informe inf) {
        c.getSharedPreferences(CONTROL, Context.MODE_PRIVATE).edit()
                .putString("informe", inf.texto()).putLong("informe_hora", System.currentTimeMillis()).apply();
        return inf;
    }

    public static String ultimoInforme(Context c) {
        return c.getSharedPreferences(CONTROL, Context.MODE_PRIVATE).getString("informe", "");
    }

    /** Revisa cada ajuste: si esta fuera de rango o danado, lo repara (del respaldo si se puede). */
    private static void revisarAjustes(Context c, Informe inf) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        int antes = inf.reparados;
        try {
            rango(p, e, inf, "modo", 0, Ajustes.MODOS.length - 1, Ajustes.MODO_BAJO_NITIDO, "el modo de sonido");
            rango(p, e, inf, "potencia", 0, Ajustes.MAX_POTENCIA, 8, "la potencia");
            rango(p, e, inf, "bajo", 0, 10, 5, "el bajo extra");
            rango(p, e, inf, "equipo", 0, Ajustes.EQUIPOS.length - 1, 0, "el equipo");
            rango(p, e, inf, "espacial", 0, 1000, 300, "el sonido envolvente");
            rango(p, e, inf, "repetir", 0, 2, Ajustes.REPETIR_TODO, "repetir");
            // ecualizador
            String b = p.getString("bandas", null);
            if (b != null) {
                String[] x = b.split(",");
                boolean bien = x.length == 10;
                for (String s : x) {
                    try { int n = Integer.parseInt(s.trim()); if (n < -12 || n > 12) bien = false; } catch (Exception ex) { bien = false; }
                }
                if (!bien) { e.remove("bandas"); inf.reparo("El ecualizador estaba dañado: lo volví al modo Bajo + nítido."); }
            }
            // datos en JSON
            json(c, p, e, inf, "listas", "tus listas guardadas");
            json(c, p, e, inf, "gustos", "tus gustos");
            // listas de canciones
            lineas(c, p, e, inf, "milista", "Mi lista");
            lineas(c, p, e, inf, "lista", "la cola de reproducción");
            int n = lineasValidas(p.getString("lista", ""));
            int actual = p.getInt("actual", 0);
            if (actual < 0 || (n > 0 && actual >= n)) { e.putInt("actual", 0); inf.reparo("La canción actual apuntaba fuera de la lista: la puse en la primera."); }
        } catch (Throwable t) {
            inf.falla("No se pudieron revisar todos los ajustes (" + t.getClass().getSimpleName() + ").");
        }
        e.apply();
        if (inf.reparados == antes) inf.ok("Ajustes, Mi lista, listas guardadas y gustos en buen estado.");
    }

    private static void rango(SharedPreferences p, SharedPreferences.Editor e, Informe inf, String k, int min, int max, int def, String nombre) {
        if (!p.contains(k)) return;
        int v;
        try { v = p.getInt(k, def); } catch (ClassCastException ex) { e.putInt(k, def); inf.reparo("Reparé " + nombre + " (tipo dañado)."); return; }
        if (v < min || v > max) { e.putInt(k, def); inf.reparo("Reparé " + nombre + " (estaba en " + v + ")."); }
    }

    private static void json(Context c, SharedPreferences p, SharedPreferences.Editor e, Informe inf, String k, String nombre) {
        String s;
        try { s = p.getString(k, "{}"); } catch (ClassCastException ex) { s = "x"; }
        try { new JSONObject(s); return; } catch (Exception ignored) { }
        String r = delRespaldo(c, k);
        try {
            if (r != null) { new JSONObject(r); e.putString(k, r); inf.reparo("Recuperé " + nombre + " del respaldo de la última versión buena."); return; }
        } catch (Exception ignored) { }
        e.putString(k, "{}");
        inf.reparo(nombre.substring(0, 1).toUpperCase() + nombre.substring(1) + " estaban dañados y no había respaldo: quedaron vacíos.");
    }

    private static int lineasValidas(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (String l : s.split("\n")) if (l.indexOf('\t') > 0) n++;
        return n;
    }

    private static void lineas(Context c, SharedPreferences p, SharedPreferences.Editor e, Informe inf, String k, String nombre) {
        String s;
        try { s = p.getString(k, ""); } catch (ClassCastException ex) { s = null; }
        if (s != null && s.isEmpty()) return;
        boolean danada = s == null;
        StringBuilder limpia = new StringBuilder();
        int quitadas = 0;
        if (s != null) for (String l : s.split("\n")) {
            int t = l.indexOf('\t');
            if (t > 0 && (l.startsWith("http") || l.startsWith("content:") || l.startsWith("file:"))) {
                if (limpia.length() > 0) limpia.append('\n');
                limpia.append(l);
            } else if (!l.trim().isEmpty()) quitadas++;
        }
        if (danada || (quitadas > 0 && limpia.length() == 0)) {
            String r = delRespaldo(c, k);
            if (r != null && lineasValidas(r) > 0) { e.putString(k, r); inf.reparo("Recuperé " + nombre + " del respaldo de la última versión buena."); return; }
        }
        if (quitadas > 0 || danada) {
            e.putString(k, limpia.toString());
            inf.reparo("Quité " + quitadas + " canción(es) dañada(s) de " + nombre + ".");
        }
    }

    public static String versionBuena(Context c) {
        return c.getSharedPreferences(CONTROL, Context.MODE_PRIVATE).getString("buena_nombre", "");
    }

    static void log(String s) { Log.i(TAG, s); }
}
