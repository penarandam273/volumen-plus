package com.luis.volumenplus;

import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.os.Build;
import android.util.Log;

/**
 * Cadena de sonido aplicada a una sesion de audio.
 *
 * En Android 9 o superior usa DynamicsProcessing, el procesador de audio profesional de Android:
 *   ecualizador de 10 bandas -> compresor de 3 bandas (da cuerpo y nivela) -> limitador (evita que se rompa el sonido)
 *   y una ganancia de entrada que es la "potencia".
 * Si el equipo no lo permite en esa sesion, usa Equalizer + LoudnessEnhancer.
 * Ademas agrega Virtualizer (sonido envolvente) cuando el equipo lo soporta.
 */
public class Cadena {

    private static final String TAG = "VolumenPlus";

    /** Centro de cada banda (para mostrar) y borde superior (lo que usa DynamicsProcessing). */
    public static final int[] CENTROS = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private static final float[] BORDES = {44f, 88f, 177f, 355f, 710f, 1420f, 2840f, 5680f, 11360f, 20000f};

    private final int sesion;
    private DynamicsProcessing dp;
    private Equalizer eq;
    private LoudnessEnhancer le;
    private Virtualizer vz;

    /**
     * @param pro true = procesador profesional (para el reproductor propio).
     *            false = refuerzo + ecualizador clasicos, el metodo que si funciona en la salida general
     *            del telefono y en otras apps (en Xiaomi DynamicsProcessing no tiene efecto ahi).
     */
    public Cadena(int sesion, boolean pro) {
        this.sesion = sesion;
        if (pro && Build.VERSION.SDK_INT >= 28) {
            try {
                // Version 2.0.20: resolucion de tiempo (golpes de bombo y voces mas nitidos, menos carga para el
                // procesador del celular y menos cortes). Si el equipo no la acepta, se usa la de frecuencia.
                for (int variante : new int[]{DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION}) {
                    try {
                        DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                                variante, 2,
                                true, CENTROS.length,   // ecualizador
                                true, 3,                // compresor multibanda
                                false, 0,               // sin ecualizador final
                                true)                   // limitador
                                .build();
                        dp = new DynamicsProcessing(0, sesion, cfg);
                        break;
                    } catch (Throwable t) {
                        Log.w(TAG, "DynamicsProcessing (variante " + variante + ") no disponible en la sesion " + sesion, t);
                        dp = null;
                    }
                }
            } catch (Throwable t) {
                dp = null;
            }
        }
        if (dp == null) {
            try { eq = new Equalizer(0, sesion); } catch (Throwable t) { eq = null; }
            try { le = new LoudnessEnhancer(sesion); } catch (Throwable t) { le = null; }
        }
        try { vz = new Virtualizer(0, sesion); } catch (Throwable t) { vz = null; }
    }

    /** true si al menos un efecto quedo conectado. */
    public boolean funciona() {
        return dp != null || eq != null || le != null;
    }

    /** Nombre del motor que se esta usando (se muestra en la app). */
    public String motor() {
        if (dp != null) return "Procesador profesional (DynamicsProcessing)";
        if (le != null || eq != null) return "Modo compatible (ecualizador + refuerzo)";
        return "Sin efectos en esta salida";
    }

    /**
     * Aplica los ajustes.
     * @param activo   encendido o apagado
     * @param potencia dB extra (0 a 24)
     * @param bandas   10 valores en dB (-12 a +12)
     * @param espacial 0 a 1000
     * @param compresor true = da cuerpo y nivela el volumen entre canciones
     */
    /** Curva de "Claridad" (estilo Dolby): quita retumbe en 250-500 Hz y abre presencia y brillo. */
    private static final int[] CLARIDAD = {0, 0, 0, -1, -2, -1, 1, 2, 2, 2};

    /** Bajo extra: se concentra en 62-125 Hz (lo que si suena en parlantes y audifonos), poco sub-bajo y despeja 250-500 Hz. */
    private static final float[] BAJO = {0.3f, 0.9f, 1f, 0.35f, -0.25f, -0.1f, 0f, 0f, 0f, 0f};
    /** Ningun refuerzo del ecualizador pasa de +9 dB (mas que eso solo agrega distorsion). */
    private static final int MAX_REFUERZO = 9;

    /** Ultimos ajustes aplicados: si no cambian, no se vuelve a configurar (reconfigurar en plena cancion da cortes y "clics"). */
    private String ultimo = "";

    public void aplicar(boolean activo, int potencia, int[] bandasUsuario, int espacial, boolean compresor) {
        aplicar(activo, potencia, bandasUsuario, espacial, compresor, false, 0);
    }

    /**
     * @param claridad        agrega la curva de nitidez
     * @param bajo            bajo extra en dB (0 a 10)
     */
    public void aplicar(boolean activo, int potencia, int[] bandasUsuario, int espacial, boolean compresor,
                        boolean claridad, int bajo) {
        int[] bandas = new int[bandasUsuario.length];
        for (int i = 0; i < bandas.length; i++) {
            bandas[i] = Math.max(-12, Math.min(MAX_REFUERZO, Math.round(bandasUsuario[i] + (claridad ? CLARIDAD[i] : 0) + BAJO[i] * bajo)));
        }
        int pico = 0;
        for (int v : bandas) pico = Math.max(pico, v);
        String clave = activo + "|" + potencia + "|" + java.util.Arrays.toString(bandas) + "|" + espacial + "|" + compresor + "|" + bajo;
        if (clave.equals(ultimo)) return; // nada cambio: no se toca el sonido que esta sonando
        ultimo = clave;
        if (dp != null) {
            try {
                DynamicsProcessing.Eq pre = new DynamicsProcessing.Eq(true, true, CENTROS.length);
                for (int i = 0; i < CENTROS.length; i++) {
                    pre.setBand(i, new DynamicsProcessing.EqBand(true, BORDES[i], (float) bandas[i]));
                }
                dp.setPreEqAllChannelsTo(pre);

                // Potencia real sin distorsion (version 2.0.20, medido en simulacion con musica ya masterizada):
                // 1) el ecualizador no sube los picos (se resta su refuerzo maximo a la entrada),
                // 2) el compresor de 3 bandas hace el trabajo: sube lo suave y controla lo fuerte (rodilla suave de 8 dB),
                // 3) el limitador queda solo para picos raros (antes cortaba el 80% del tiempo a maxima potencia; ahora ~5%).
                // La respuesta crece con la raiz de la potencia: el control se nota desde la mitad, no solo al final.
                float p = Math.max(0, Math.min(24, potencia));
                boolean usarMbc = compresor && (p > 0 || bajo > 0);
                // 2.0.21: el volumen va a lo que el parlante del celular SI reproduce (voces e instrumentos, > 150 Hz).
                // En la 2.0.20 la ganancia se iba al sub-bajo y la voz quedaba 2 dB mas baja que en la 2.0.19.
                // Medido en simulacion: igual o mas fuerte que la 2.0.19 en todas las potencias y el limitador
                // trabaja el 13 % del tiempo a maxima potencia (la 2.0.19: 80 %, por eso distorsionaba).
                float q = (float) Math.sqrt(Math.max(p, 4f) / 24f);
                float t = -14f * q;                  // umbral de medios y agudos
                float tGraves = t - 3f * q;          // el bajo se controla un poco antes: golpea firme sin ahogar la voz
                float r = 4f;
                float extra = pico * 0.25f;          // recupera parte del espacio que se dejo para el ecualizador
                float mGraves = (-tGraves * (1f - 1f / r) * 0.8f + extra) * 0.4f; // el bajo no se come el volumen
                float mResto = -t * (1f - 1f / r) * 0.8f + extra;
                DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, usarMbc, 3);
                // graves (< 160 Hz): ataque de 10 ms (no deforma la onda del bajo) y suelta en 250 ms (sin "bombeo")
                mbc.setBand(0, new DynamicsProcessing.MbcBand(usarMbc, 160f, 10f, 250f, r, tGraves, 8f, -90f, 1f, 0f, usarMbc ? mGraves : 0f));
                // medios (160 Hz - 4 kHz): voces e instrumentos
                mbc.setBand(1, new DynamicsProcessing.MbcBand(usarMbc, 4000f, 5f, 150f, r, t, 8f, -90f, 1f, 0f, usarMbc ? mResto : 0f));
                // agudos (> 4 kHz): controla la "s" y los platillos sin opacar
                mbc.setBand(2, new DynamicsProcessing.MbcBand(usarMbc, 20000f, 2f, 100f, r, t, 8f, -90f, 1f, 0f, usarMbc ? mResto - 0.5f : 0f));
                dp.setMbcAllChannelsTo(mbc);

                // Limitador final: techo en -1 dB, nada pasa del maximo digital (sin recorte en el parlante)
                dp.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 0.1f, 150f, 50f, -1f, 0f));
                // sin compresor: un poco de ganancia segun la potencia y la mitad del espacio del ecualizador
                dp.setInputGainAllChannelsTo(usarMbc ? -pico * 0.25f : p * 0.2f - pico * 0.5f);
                dp.setEnabled(activo);
            } catch (Throwable t) {
                Log.w(TAG, "No se pudo ajustar DynamicsProcessing", t);
            }
        } else {
            if (eq != null) {
                try {
                    short n = eq.getNumberOfBands();
                    short[] rango = eq.getBandLevelRange();
                    for (short b = 0; b < n; b++) {
                        int centroHz = eq.getCenterFreq(b) / 1000;
                        // la banda mas alta queda en 0 dB y las demas por debajo: el ecualizador nunca satura
                        int db = bandas[cercana(centroHz)] - pico;
                        int mb = Math.max(rango[0], Math.min(rango[1], db * 100));
                        eq.setBandLevel(b, (short) mb);
                    }
                    eq.setEnabled(activo);
                } catch (Throwable t) {
                    Log.w(TAG, "No se pudo ajustar el ecualizador", t);
                }
            }
            if (le != null) {
                try {
                    // el refuerzo (que trae su propio compresor) recupera lo que se bajo al ecualizar.
                    // 2.0.21: vuelve la potencia de la 2.0.19 en las otras apps (la 2.0.20 la habia bajado a 16 dB).
                    int total = Math.min(30, potencia + (eq != null ? pico : 0));
                    le.setTargetGain(total * 100);
                    le.setEnabled(activo && total > 0);
                } catch (Throwable t) {
                    Log.w(TAG, "No se pudo ajustar el refuerzo", t);
                }
            }
        }
        if (vz != null) {
            try {
                if (vz.getStrengthSupported()) vz.setStrength((short) Math.max(0, Math.min(1000, espacial)));
                vz.setEnabled(activo && espacial > 0);
            } catch (Throwable t) {
                Log.w(TAG, "No se pudo ajustar el sonido envolvente", t);
            }
        }
    }

    private static int cercana(int hz) {
        int mejor = 0;
        double dist = Double.MAX_VALUE;
        for (int i = 0; i < CENTROS.length; i++) {
            double d = Math.abs(Math.log(Math.max(1, hz)) - Math.log(CENTROS[i]));
            if (d < dist) { dist = d; mejor = i; }
        }
        return mejor;
    }

    public void liberar() {
        try { if (dp != null) { dp.setEnabled(false); dp.release(); } } catch (Throwable ignored) { }
        try { if (eq != null) { eq.setEnabled(false); eq.release(); } } catch (Throwable ignored) { }
        try { if (le != null) { le.setEnabled(false); le.release(); } } catch (Throwable ignored) { }
        try { if (vz != null) { vz.setEnabled(false); vz.release(); } } catch (Throwable ignored) { }
        dp = null; eq = null; le = null; vz = null;
        ultimo = "";
    }

    public int getSesion() {
        return sesion;
    }
}
