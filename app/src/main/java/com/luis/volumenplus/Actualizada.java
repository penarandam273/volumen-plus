package com.luis.volumenplus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Justo despues de instalar una actualizacion: el guardian revisa y repara la app, y te avisa. */
public class Actualizada extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                Guardian.Informe inf = Guardian.revisar(c, "se instaló una actualización");
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new NotificationChannel("guardian", "Guardián de la app", NotificationManager.IMPORTANCE_DEFAULT));
                PendingIntent abrir = PendingIntent.getActivity(c, 7, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
                Notification n = new Notification.Builder(c, "guardian")
                        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                        .setContentTitle("Volumen+ " + inf.version + " instalada")
                        .setContentText(inf.resumen())
                        .setStyle(new Notification.BigTextStyle().bigText(inf.resumen()))
                        .setContentIntent(abrir)
                        .setAutoCancel(true)
                        .build();
                try { nm.notify(77, n); } catch (SecurityException ignored) { }
            } catch (Throwable ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }
}
