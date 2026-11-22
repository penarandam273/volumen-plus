package com.luis.volumenplus;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import java.util.Random;

/**
 * Efectos de DJ creados por la propia app (sintetizados, sin grabaciones de terceros).
 * Suenan encima de la musica, como en una fiesta.
 */
public final class Efectos {
    private static final String TAG = "VolumenPro";
    private static final int FS = 44100;

    public static final String[] NOMBRES = {"Corneta", "Sirena", "Láser", "Explosión", "Aplausos", "Redoble", "Rebobinar"};

    private Efectos() { }

    /** Busca el efecto por una palabra (sirve para la voz). -1 si no hay. */
    public static int buscar(String t) {
        String s = t.toLowerCase();
        if (s.contains("corneta") || s.contains("bocina") || s.contains("pito") || s.contains("trompeta") || s.contains("air horn")) return 0;
        if (s.contains("sirena") || s.contains("alarma")) return 1;
        if (s.contains("laser") || s.contains("láser") || s.contains("disparo") || s.contains("pistola")) return 2;
        if (s.contains("explosi") || s.contains("bomba") || s.contains("drop") || s.contains("boom")) return 3;
        if (s.contains("aplaus") || s.contains("palmas") || s.contains("public")) return 4;
        if (s.contains("redoble") || s.contains("tambor") || s.contains("bateria") || s.contains("batería")) return 5;
        if (s.contains("rebobin") || s.contains("rewind") || s.contains("scratch") || s.contains("raspad")) return 6;
        return -1;
    }

    public static void tocar(int i) {
        if (i < 0 || i >= NOMBRES.length) return;
        new Thread(() -> {
            try {
                reproducir(generar(i));
            } catch (Throwable t) {
                Log.w(TAG, "No se pudo tocar el efecto", t);
            }
        }).start();
    }

    /** Reproduce audio de 16 bits mono a 44,1 kHz y espera a que termine. */
    static void reproducir(short[] pcm) throws InterruptedException {
        int bytes = pcm.length * 2;
        AudioTrack at = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(FS)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build();
        at.write(pcm, 0, pcm.length);
        at.play();
        Thread.sleep(pcm.length * 1000L / FS + 150);
        at.stop();
        at.release();
    }

    static short[] generar(int i) {
        switch (i) {
            case 0: return corneta();
            case 1: return sirena();
            case 2: return laser();
            case 3: return explosion();
            case 4: return aplausos();
            case 5: return redoble();
            default: return rebobinar();
        }
    }

    // ---------- sintesis ----------
    private static short[] aPcm(float[] x, float volumen) {
        float max = 1e-6f;
        for (float v : x) max = Math.max(max, Math.abs(v));
        short[] r = new short[x.length];
        float g = volumen / max;
        for (int i = 0; i < x.length; i++) r[i] = (short) (Math.max(-1f, Math.min(1f, x[i] * g)) * 32000);
        return r;
    }

    private static float saw(double fase) {
        double f = fase - Math.floor(fase);
        return (float) (2 * f - 1);
    }

    /** Corneta de estadio: acorde de dientes de sierra saturado, corto-corto-largo. */
    private static short[] corneta() {
        double[][] golpes = {{0, 0.16}, {0.22, 0.16}, {0.44, 0.75}};
        int n = (int) (FS * 1.25);
        float[] x = new float[n];
        double[] notas = {466.16, 587.33, 698.46};
        for (double[] g : golpes) {
            int a = (int) (g[0] * FS), L = (int) (g[1] * FS);
            for (int k = 0; k < L && a + k < n; k++) {
                double t = k / (double) FS;
                double vib = 1 + 0.004 * Math.sin(2 * Math.PI * 6 * t);
                float s = 0;
                for (int j = 0; j < notas.length; j++) s += saw(notas[j] * vib * t + j * 0.13) + 0.5f * saw(notas[j] * 2.003 * t);
                float env = (float) Math.min(1, Math.min(t / 0.015, (g[1] - t) / 0.04));
                x[a + k] += (float) Math.tanh(s * 0.9) * env;
            }
        }
        return aPcm(x, 0.85f);
    }

    /** Sirena que sube y baja. */
    private static short[] sirena() {
        int n = (int) (FS * 2.0);
        float[] x = new float[n];
        double fase = 0;
        for (int k = 0; k < n; k++) {
            double t = k / (double) FS;
            double f = 750 + 450 * Math.sin(2 * Math.PI * 1.0 * t - Math.PI / 2);
            fase += f / FS;
            float env = (float) Math.min(1, Math.min(t / 0.05, (2.0 - t) / 0.2));
            x[k] = (float) (Math.sin(2 * Math.PI * fase) + 0.3 * Math.sin(4 * Math.PI * fase)) * env;
        }
        return aPcm(x, 0.7f);
    }

    /** Laser: tres disparos que bajan de tono. */
    private static short[] laser() {
        int n = (int) (FS * 0.9);
        float[] x = new float[n];
        for (int d = 0; d < 3; d++) {
            int a = (int) (d * 0.28 * FS), L = (int) (0.25 * FS);
            double fase = 0;
            for (int k = 0; k < L && a + k < n; k++) {
                double t = k / (double) FS;
                double f = 2600 * Math.exp(-t * 14) + 180;
                fase += f / FS;
                x[a + k] += (float) (Math.sin(2 * Math.PI * fase) * Math.exp(-t * 7));
            }
        }
        return aPcm(x, 0.7f);
    }

    /** Explosion / "drop": subida de ruido, golpe grave y cola. */
    private static short[] explosion() {
        int n = (int) (FS * 2.2);
        float[] x = new float[n];
        Random r = new Random(7);
        float lp = 0;
        double fase = 0;
        for (int k = 0; k < n; k++) {
            double t = k / (double) FS;
            float ruido = (float) r.nextGaussian();
            if (t < 0.8) { // subida
                float a = (float) (0.02 + 0.3 * t);
                lp += a * (ruido - lp);
                x[k] = lp * (float) (t / 0.8) * 0.6f;
            } else { // golpe
                double u = t - 0.8;
                double f = 120 * Math.exp(-u * 5) + 38;
                fase += f / FS;
                lp += 0.05f * (ruido - lp);
                x[k] = (float) (Math.tanh(1.8 * Math.sin(2 * Math.PI * fase)) * Math.exp(-u * 2.2) + lp * 2.5 * Math.exp(-u * 3));
            }
        }
        return aPcm(x, 0.9f);
    }

    /** Aplausos: muchas palmas cortas al azar. */
    private static short[] aplausos() {
        int n = (int) (FS * 2.5);
        float[] x = new float[n];
        Random r = new Random(3);
        for (int p = 0; p < 420; p++) {
            int a = (int) (r.nextDouble() * (n - FS * 0.05));
            float g = 0.4f + r.nextFloat();
            float hp = 0, prev = 0;
            for (int k = 0; k < FS * 0.03; k++) {
                float w = (float) r.nextGaussian();
                hp = 0.85f * (hp + w - prev);
                prev = w;
                x[a + k] += hp * g * (float) Math.exp(-k / (FS * 0.006));
            }
        }
        for (int k = 0; k < n; k++) {
            double t = k / (double) FS;
            x[k] *= (float) Math.min(1, Math.min(t / 0.3, (2.5 - t) / 0.6));
        }
        return aPcm(x, 0.75f);
    }

    /** Redoble de tambor que acelera y remata con un golpe. */
    private static short[] redoble() {
        int n = (int) (FS * 2.0);
        float[] x = new float[n];
        Random r = new Random(11);
        double t0 = 0, intervalo = 0.12;
        while (t0 < 1.6) {
            golpeCaja(x, (int) (t0 * FS), 0.5f + (float) (t0 / 1.6) * 0.5f, r);
            t0 += intervalo;
            intervalo = Math.max(0.035, intervalo * 0.93);
        }
        golpeCaja(x, (int) (1.65 * FS), 1.6f, r);
        return aPcm(x, 0.85f);
    }

    private static void golpeCaja(float[] x, int a, float g, Random r) {
        double fase = 0;
        for (int k = 0; k < FS * 0.2 && a + k < x.length; k++) {
            double t = k / (double) FS;
            fase += (190 * Math.exp(-t * 20) + 160) / FS;
            x[a + k] += g * (float) (0.6 * Math.sin(2 * Math.PI * fase) * Math.exp(-t * 30) + 0.5 * r.nextGaussian() * Math.exp(-t * 22));
        }
    }

    /** Rebobinar: barrido que baja y sube rapido. */
    private static short[] rebobinar() {
        int n = (int) (FS * 1.1);
        float[] x = new float[n];
        Random r = new Random(5);
        double fase = 0;
        for (int k = 0; k < n; k++) {
            double t = k / (double) FS;
            double f = 300 + 900 * Math.abs(Math.sin(2 * Math.PI * 2.6 * t));
            fase += f / FS;
            float env = (float) Math.min(1, Math.min(t / 0.03, (1.1 - t) / 0.15));
            x[k] = (float) (saw(fase) * 0.7 + r.nextGaussian() * 0.15) * env;
        }
        return aPcm(x, 0.7f);
    }
}
