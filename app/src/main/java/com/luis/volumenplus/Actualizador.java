package com.luis.volumenplus;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Actualizaciones de Volumen+ (version 2.0.20).
 * La app se compila sola en GitHub (repositorio penarandam273/volumen-plus) con la llave oficial y se publica en Releases.
 * Aqui se busca la version nueva, se descarga, se comprueba que tenga la firma de Luis y se instala ENCIMA:
 * tus listas, gustos y ajustes se conservan. Android siempre pide confirmar la instalacion.
 */
public final class Actualizador {
    private static final String TAG = "VolumenPlus";
    public static final String REPO = "penarandam273/volumen-plus";
    private static final String PREFS = "volumen_actualizar";

    private Actualizador() { }

    public static final class Info {
        public long codigo;
        public String nombre = "", url = "", notas = "";
    }

    /** Version instalada (codigo). */
    public static long codigoActual(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    public static String nombreActual(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    /** true si toca revisar sola (una vez cada 12 horas). */
    public static boolean tocaRevisar(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return System.currentTimeMillis() - p.getLong("ultima", 0) > 12 * 3600_000L;
    }

    /** Busca la ultima version publicada (llamar fuera de la pantalla, en otro hilo). null = ya estas al dia. */
    public static Info buscar(Context c) throws Exception {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("ultima", System.currentTimeMillis()).apply();
        JSONObject r = new JSONObject(leer("https://api.github.com/repos/" + REPO + "/releases/latest"));
        String tag = r.optString("tag_name", "");
        long codigo = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\D*$").matcher(tag);
        if (m.find()) codigo = Long.parseLong(m.group(1));
        if (codigo <= codigoActual(c)) return null;
        JSONArray a = r.optJSONArray("assets");
        Info i = new Info();
        i.codigo = codigo;
        i.nombre = tag.replaceFirst("^v", "");
        i.notas = r.optString("body", "");
        for (int k = 0; a != null && k < a.length(); k++) {
            JSONObject x = a.getJSONObject(k);
            if (x.optString("name", "").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) { i.url = x.optString("browser_download_url", ""); break; }
        }
        return i.url.isEmpty() ? null : i;
    }

    /** Android pide permiso para que Volumen+ instale su propia actualizacion (solo la primera vez). */
    public static boolean puedeInstalar(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    public static void pedirPermiso(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    public interface Avance { void en(int porcentaje); }

    /** Descarga e instala (llamar en otro hilo). Devuelve "" si Android ya mostro la confirmacion, o el problema. */
    public static String descargarEInstalar(Context c, Info info, Avance avance) {
        File apk = new File(new File(c.getCacheDir(), "actualizacion"), "VolumenPlus.apk");
        try {
            apk.getParentFile().mkdirs();
            HttpURLConnection con = abrir(info.url);
            long total = con.getContentLengthLong(), va = 0;
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                byte[] b = new byte[64 * 1024];
                int n, ultimo = -1;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    va += n;
                    int p = total > 0 ? (int) (va * 100 / total) : -1;
                    if (p != ultimo && avance != null) { ultimo = p; avance.en(p); }
                }
            } finally { con.disconnect(); }

            // seguridad: solo se instala si viene con la firma oficial de Luis
            String firma = firmaDelArchivo(c, apk);
            if (!firma.contains(Guardian.FIRMA_OFICIAL)) return "La descarga no tiene la firma oficial: no se instaló.";

            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(c.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("VolumenPlus.apk", 0, apk.length())) {
                    byte[] b = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    s.fsync(out);
                }
                Intent i = new Intent(c, Resultado.class);
                int banderas = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pend = PendingIntent.getBroadcast(c, 31, i, banderas);
                s.commit(pend.getIntentSender());
            }
            return "";
        } catch (Throwable t) {
            Log.w(TAG, "Actualizar", t);
            return "No se pudo actualizar: " + t.getMessage();
        }
    }

    /** Respuesta de Android al instalar: si pide confirmacion, se le muestra a Luis. */
    public static class Resultado extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int estado = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (estado == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmar = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmar != null) {
                    confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirmar); } catch (Exception e) { Log.w(TAG, "Confirmar instalacion", e); }
                }
            } else if (estado != PackageInstaller.STATUS_SUCCESS) {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.w(TAG, "Instalacion: " + estado + " " + m);
                try {
                    android.widget.Toast.makeText(c, "No se instaló la actualización" + (m != null ? ": " + m : ""), android.widget.Toast.LENGTH_LONG).show();
                } catch (Exception ignored) { }
            }
        }
    }

    // ---------- ayudantes ----------
    private static String firmaDelArchivo(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] firmas;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi == null || pi.signingInfo == null) return "";
                firmas = pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
            } else {
                PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
                if (pi == null) return "";
                firmas = pi.signatures;
            }
            StringBuilder todas = new StringBuilder();
            for (Signature s : firmas) {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
                StringBuilder h = new StringBuilder();
                for (byte x : d) h.append(String.format("%02X", x));
                todas.append(h).append(';');
            }
            return todas.toString();
        } catch (Exception e) { return ""; }
    }

    private static HttpURLConnection abrir(String url) throws Exception {
        String dir = url;
        for (int saltos = 0; saltos < 6; saltos++) {
            HttpURLConnection con = (HttpURLConnection) new URL(dir).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("User-Agent", "VolumenPlus/2 (Android)");
            con.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*");
            int c = con.getResponseCode();
            if (c >= 300 && c < 400 && con.getHeaderField("Location") != null) {
                dir = new URL(new URL(dir), con.getHeaderField("Location")).toString();
                con.disconnect();
                continue;
            }
            if (c != 200) throw new Exception("GitHub respondió " + c);
            return con;
        }
        throw new Exception("demasiadas redirecciones");
    }

    private static String leer(String url) throws Exception {
        HttpURLConnection con = abrir(url);
        try (InputStream in = con.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } finally { con.disconnect(); }
    }
}
