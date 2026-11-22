package com.luis.volumenplus;

import android.content.Context;
import android.media.MediaDataSource;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Reserva de musica (version 2.0.20): adios a los cortes cuando se escucha por internet.
 *
 * Antes el reproductor de Android pedia la cancion de a poquitos (su reserva es de pocos segundos):
 * cualquier bajon de senal o de Wi-Fi la entrecortaba. Ahora la cancion se descarga a toda velocidad a un
 * archivo del telefono mientras suena, y el reproductor lee de ese archivo. Si la descarga va adelante, la
 * musica nunca se detiene; ademas la siguiente de la lista se precarga y una cancion ya escuchada suena
 * al instante y sin datos.
 *
 * Las emisoras en vivo (no tienen fin) no usan la reserva: siguen como antes.
 */
public class Bufer extends MediaDataSource {
    private static final String TAG = "VolumenPlus";
    /** Tope de la carpeta de reserva; al pasarlo se quitan las canciones mas viejas de la reserva (no de tus listas). */
    private static final long TOPE_CARPETA = 400L * 1024 * 1024;
    /** Una cancion de mas de esto no se guarda (seria un album entero o algo raro). */
    private static final long TOPE_CANCION = 150L * 1024 * 1024;

    private static final Map<String, Bufer> activos = new HashMap<>();

    public final String uri;
    private final File archivo, parcial;
    private volatile long descargado = 0, total = -1;
    private volatile boolean completo = false, fallo = false, enVivo = false, cancelar = false;
    private volatile String error = "";
    private RandomAccessFile lector;
    private Thread hilo;

    private Bufer(Context c, String uri) {
        this.uri = uri;
        File dir = carpeta(c);
        String n = hash(uri);
        archivo = new File(dir, n + ".audio");
        parcial = new File(dir, n + ".parte");
        if (archivo.exists() && archivo.length() > 0) {
            completo = true;
            descargado = total = archivo.length();
            archivo.setLastModified(System.currentTimeMillis()); // recien usada: no se quita de la reserva
        }
    }

    /** La reserva de una cancion (la misma si ya se estaba descargando). */
    public static synchronized Bufer de(Context c, String uri) {
        Bufer b = activos.get(uri);
        if (b == null || b.fallo) {
            b = new Bufer(c.getApplicationContext(), uri);
            activos.put(uri, b);
        }
        b.empezar();
        return b;
    }

    /** Descarga en segundo plano la proxima cancion para que suene sin esperar. */
    public static void precargar(Context c, String uri) {
        if (uri == null || !uri.startsWith("http")) return;
        try { de(c, uri); } catch (Throwable ignored) { }
    }

    /** Suelta las descargas que no son la cancion actual ni la siguiente. */
    public static synchronized void soloEstas(String actual, String siguiente) {
        for (Map.Entry<String, Bufer> e : activos.entrySet()) {
            if (!e.getKey().equals(actual) && !e.getKey().equals(siguiente) && !e.getValue().completo) e.getValue().cancelar = true;
        }
        activos.entrySet().removeIf(e -> e.getValue().cancelar || e.getValue().fallo);
    }

    private synchronized void empezar() {
        if (completo || enVivo || (hilo != null && hilo.isAlive())) return;
        cancelar = false;
        fallo = false;
        hilo = new Thread(this::descargar, "VolumenPlus-reserva");
        hilo.start();
    }

    /** true si hay que reproducir directo por internet (emisora en vivo o la descarga no arranco). */
    public boolean esEnVivo(long esperarMs) {
        long fin = System.currentTimeMillis() + esperarMs;
        synchronized (this) {
            while (!enVivo && !fallo && !completo && descargado < 64 * 1024 && System.currentTimeMillis() < fin) {
                try { wait(200); } catch (InterruptedException e) { return false; }
            }
        }
        return enVivo || (fallo && descargado == 0);
    }

    public boolean completo() { return completo; }
    public int porcentaje() { return total > 0 ? (int) Math.min(100, descargado * 100 / total) : -1; }

    private void descargar() {
        // 2.0.22: la descarga va con prioridad baja para no quitarle procesador a la musica que suena (menos cortes)
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) { }
        HttpURLConnection con = null;
        try {
            long ya = parcial.exists() ? parcial.length() : 0;
            String dir = uri;
            for (int saltos = 0; saltos < 6; saltos++) {
                con = (HttpURLConnection) new URL(dir).openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                con.setInstanceFollowRedirects(false);
                con.setRequestProperty("User-Agent", "VolumenPlus/2 (Android)");
                con.setRequestProperty("Icy-MetaData", "0");
                if (ya > 0) con.setRequestProperty("Range", "bytes=" + ya + "-");
                int c = con.getResponseCode();
                if (c >= 300 && c < 400 && con.getHeaderField("Location") != null) {
                    dir = new URL(new URL(dir), con.getHeaderField("Location")).toString();
                    con.disconnect();
                    continue;
                }
                break;
            }
            int codigo = con.getResponseCode();
            if (codigo != 200 && codigo != 206) throw new IOException("el servidor respondió " + codigo);
            if (codigo == 200) ya = 0; // el servidor no permite continuar: se empieza de nuevo
            long largo = con.getContentLengthLong();
            String tipo = String.valueOf(con.getContentType()).toLowerCase(java.util.Locale.ROOT);
            boolean icy = con.getHeaderField("icy-name") != null || con.getHeaderField("icy-br") != null || con.getHeaderField("icy-metaint") != null;
            if (icy || largo <= 0 || tipo.contains("mpegurl") || tipo.contains("x-scpls")) {
                // emisora en vivo o sin tamano: no se guarda, se escucha directo
                synchronized (this) { enVivo = true; notifyAll(); }
                return;
            }
            long nuevoTotal = ya + largo;
            if (nuevoTotal > TOPE_CANCION) { synchronized (this) { enVivo = true; notifyAll(); } return; }
            total = nuevoTotal;
            descargado = ya;
            limpiarCarpeta(archivo.getParentFile());
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(parcial, ya > 0)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while (!cancelar && (n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    synchronized (this) { descargado += n; notifyAll(); }
                }
                out.flush();
            }
            if (cancelar) return;
            if (descargado >= total) {
                if (!parcial.renameTo(archivo)) throw new IOException("no se pudo guardar la reserva");
                synchronized (this) { completo = true; notifyAll(); }
            } else {
                throw new IOException("la descarga quedó incompleta");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Reserva: " + t.getMessage());
            synchronized (this) { fallo = true; error = String.valueOf(t.getMessage()); notifyAll(); }
        } finally {
            if (con != null) try { con.disconnect(); } catch (Throwable ignored) { }
        }
    }

    // ---------- lo que pide el reproductor ----------
    @Override
    public int readAt(long pos, byte[] buffer, int offset, int size) throws IOException {
        if (size <= 0) return 0;
        long fin = System.currentTimeMillis() + 45000;
        synchronized (this) {
            // espera a que la descarga llegue a esa parte (el reproductor lo toma como "cargando")
            while (!completo && descargado <= pos && !(total > 0 && pos >= total)) {
                if (fallo) {
                    // se cayo la senal: se reintenta continuar desde donde iba
                    if (System.currentTimeMillis() > fin) throw new IOException("sin señal: " + error);
                    fallo = false;
                    try { wait(1500); } catch (InterruptedException e) { throw new IOException("interrumpido"); }
                    empezarDeNuevo();
                    continue;
                }
                if (System.currentTimeMillis() > fin) throw new IOException("la descarga no avanza");
                try { wait(250); } catch (InterruptedException e) { throw new IOException("interrumpido"); }
            }
        }
        if (total > 0 && pos >= total) return -1;
        long disponible = (completo ? total : descargado) - pos;
        int n = (int) Math.min(size, disponible);
        if (n <= 0) return -1;
        RandomAccessFile r = abrir();
        synchronized (r) {
            r.seek(pos);
            return r.read(buffer, offset, n);
        }
    }

    private void empezarDeNuevo() {
        if (hilo != null && hilo.isAlive()) return;
        hilo = new Thread(this::descargar, "VolumenPlus-reserva");
        hilo.start();
    }

    private synchronized RandomAccessFile abrir() throws IOException {
        File f = completo ? archivo : parcial;
        if (lector == null || (completo && !f.equals(archivoAbierto))) {
            if (lector != null) try { lector.close(); } catch (IOException ignored) { }
            lector = new RandomAccessFile(f, "r");
            archivoAbierto = f;
        }
        return lector;
    }
    private File archivoAbierto;

    @Override
    public long getSize() {
        return total > 0 ? total : -1;
    }

    @Override
    public synchronized void close() {
        if (lector != null) try { lector.close(); } catch (IOException ignored) { }
        lector = null;
        archivoAbierto = null;
        // la descarga sigue: asi la cancion queda guardada y la proxima vez suena al instante
    }

    // ---------- carpeta de reserva ----------
    private static File carpeta(Context c) {
        File d = new File(c.getCacheDir(), "reserva-musica");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Mantiene la reserva por debajo del tope quitando las canciones de reserva usadas hace mas tiempo. */
    private static void limpiarCarpeta(File d) {
        try {
            // pedazos de descargas que quedaron a medias hace mas de un dia
            File[] partes = d.listFiles((dir, name) -> name.endsWith(".parte"));
            if (partes != null) for (File f : partes) if (System.currentTimeMillis() - f.lastModified() > 86_400_000L) f.delete();
            File[] fs = d.listFiles((dir, name) -> name.endsWith(".audio"));
            if (fs == null) return;
            long suma = 0;
            for (File f : fs) suma += f.length();
            if (suma < TOPE_CARPETA) return;
            Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : fs) {
                if (suma < TOPE_CARPETA * 3 / 4) break;
                long l = f.length();
                if (f.delete()) suma -= l;
            }
        } catch (Throwable ignored) { }
    }

    private static String hash(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i++) b.append(String.format("%02x", h[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
