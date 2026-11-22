package com.luis.volumenplus;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * La voz del Agente DJ: responde hablando y manda saludos con voz de locutor
 * (voz del telefono + eco + corneta, bajando la musica mientras habla).
 */
public final class Locutor {
    private static final String TAG = "VolumenPro";
    private final Context ctx;
    private TextToSpeech tts;
    private boolean listo = false;

    public Locutor(Context c) {
        ctx = c.getApplicationContext();
        tts = new TextToSpeech(ctx, estado -> {
            if (estado != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(new Locale("es", "CO"));
            if (r < 0) r = tts.setLanguage(new Locale("es", "US"));
            if (r < 0) tts.setLanguage(new Locale("es"));
            listo = true;
        });
    }

    public boolean listo() { return listo; }

    /** Respuesta corta del agente, sin efectos. */
    public void decir(String texto) {
        if (!listo) return;
        tts.setPitch(1.0f);
        tts.setSpeechRate(1.05f);
        tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "dj-" + System.nanoTime());
    }

    /** Saludo de locutor: corneta, voz grave con eco y la musica baja mientras tanto. */
    public void saludo(String texto, boolean conCorneta) {
        if (!listo) return;
        final File f = new File(ctx.getCacheDir(), "saludo.wav");
        final String id = "saludo-" + System.nanoTime();
        tts.setPitch(0.82f);
        tts.setSpeechRate(0.95f);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String u) { }
            @Override public void onError(String u) { tts.setPitch(1.0f); }
            @Override public void onDone(String u) {
                if (!id.equals(u)) return;
                tts.setPitch(1.0f);
                new Thread(() -> {
                    MotorService m = MotorService.yo;
                    try {
                        if (m != null) m.bajarParaHablar(true);
                        if (conCorneta) Efectos.reproducir(Efectos.generar(0));
                        short[] voz = leerWav(f);
                        if (voz != null) Efectos.reproducir(conEco(voz, rate));
                    } catch (Throwable t) {
                        Log.w(TAG, "No se pudo decir el saludo", t);
                    } finally {
                        if (m != null) m.bajarParaHablar(false);
                    }
                }).start();
            }
        });
        Bundle b = new Bundle();
        tts.synthesizeToFile(texto, b, f, id);
    }

    private int rate = 22050;

    /** Lee un WAV PCM de 16 bits mono y lo pasa a 44,1 kHz. */
    private short[] leerWav(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] d = new byte[(int) f.length()];
            int n = 0, k;
            while (n < d.length && (k = in.read(d, n, d.length - n)) > 0) n += k;
            ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            int canales = bb.getShort(22), fs = bb.getInt(24), bits = bb.getShort(34);
            if (bits != 16) return null;
            // buscar el bloque "data"
            int p = 12, inicio = -1, largo = 0;
            while (p + 8 <= d.length) {
                String id = new String(d, p, 4, "US-ASCII");
                int tam = bb.getInt(p + 4);
                if (id.equals("data")) { inicio = p + 8; largo = Math.min(tam, d.length - inicio); break; }
                p += 8 + tam + (tam & 1);
            }
            if (inicio < 0) return null;
            int muestras = largo / 2 / Math.max(1, canales);
            short[] mono = new short[muestras];
            for (int i = 0; i < muestras; i++) mono[i] = bb.getShort(inicio + i * 2 * canales);
            rate = fs;
            if (fs == 44100) return mono;
            int nuevo = (int) ((long) muestras * 44100 / fs);
            short[] r = new short[nuevo];
            for (int i = 0; i < nuevo; i++) {
                double x = i * (double) fs / 44100;
                int a = (int) x;
                double t = x - a;
                int b2 = Math.min(muestras - 1, a + 1);
                r[i] = (short) (mono[Math.min(a, muestras - 1)] * (1 - t) + mono[b2] * t);
            }
            return r;
        } catch (Exception e) {
            Log.w(TAG, "WAV del saludo ilegible", e);
            return null;
        }
    }

    /** Eco de locutor (tres repeticiones que se apagan) y un poco de cuerpo. */
    private static short[] conEco(short[] v, int ignorado) {
        int fs = 44100;
        int[] retrasos = {(int) (0.16 * fs), (int) (0.32 * fs), (int) (0.48 * fs)};
        float[] g = {0.45f, 0.25f, 0.12f};
        int n = v.length + retrasos[2];
        float[] x = new float[n];
        for (int i = 0; i < v.length; i++) {
            x[i] += v[i];
            for (int j = 0; j < 3; j++) x[i + retrasos[j]] += v[i] * g[j];
        }
        float max = 1;
        for (float s : x) max = Math.max(max, Math.abs(s));
        float k = 30000f / max;
        short[] r = new short[n];
        for (int i = 0; i < n; i++) r[i] = (short) (x[i] * k);
        return r;
    }

    public void cerrar() {
        try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
    }
}
