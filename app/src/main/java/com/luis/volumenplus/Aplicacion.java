package com.luis.volumenplus;

import android.app.Application;

/** Arranca al guardian antes que todo: si algo falla, queda anotado para repararlo. */
public class Aplicacion extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Guardian.vigilarFallos(this);
    }
}
