package com.luis.volumenplus;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * Ventana corta de pistas (version 2.0.22): en vez de una lista enorme, una ventanita que se desplaza sola
 * (como mucho el 40 % de la pantalla) y que muestra de a 10 con el boton "Ver más".
 */
public class Ventana extends LinearLayout {

    public interface Fila { View crear(int i); }

    private final Caja caja;
    private final LinearLayout filas;
    private final Button mas;
    private Fila fila;
    private int total = 0, mostradas = 0;

    public Ventana(Context c, Button botonMas) {
        super(c);
        setOrientation(VERTICAL);
        caja = new Caja(c);
        caja.setFillViewport(false);
        caja.setVerticalScrollBarEnabled(true);
        caja.setNestedScrollingEnabled(true);
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(Color.argb(40, 255, 255, 255));
        fondo.setCornerRadius(dp(12));
        caja.setBackground(fondo);
        caja.setPadding(dp(4), dp(2), dp(4), dp(2));
        filas = new LinearLayout(c);
        filas.setOrientation(VERTICAL);
        caja.addView(filas, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        super.addView(caja, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mas = botonMas;
        mas.setOnClickListener(v -> agregar(10));
        mas.setVisibility(GONE);
        super.addView(mas, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        caja.setVisibility(GONE);
    }

    /** Pone una lista nueva: se crean solo las filas que se ven (de a 10). */
    public void poner(int cuantas, Fila f) {
        filas.removeAllViews();
        fila = f;
        total = Math.max(0, cuantas);
        mostradas = 0;
        caja.scrollTo(0, 0);
        caja.setVisibility(total > 0 ? VISIBLE : GONE);
        agregar(10);
    }

    /** Se asegura de que la fila i ya este creada y la deja a la vista (la cancion que suena). */
    public void mostrar(int i) {
        if (i < 0 || i >= total) return;
        if (i >= mostradas) agregar(i - mostradas + 5);
        final int k = i;
        caja.post(() -> {
            if (k < filas.getChildCount()) caja.smoothScrollTo(0, Math.max(0, filas.getChildAt(k).getTop() - dp(40)));
        });
    }

    private void agregar(int n) {
        if (fila == null) return;
        int hasta = Math.min(total, mostradas + n);
        for (int i = mostradas; i < hasta; i++) filas.addView(fila.crear(i));
        mostradas = hasta;
        int faltan = total - mostradas;
        mas.setVisibility(faltan > 0 ? VISIBLE : GONE);
        mas.setText("Ver más (" + faltan + ")");
    }

    /** Vacia la ventana (los llamados viejos a removeAllViews siguen funcionando). */
    @Override
    public void removeAllViews() {
        filas.removeAllViews();
        fila = null;
        total = mostradas = 0;
        caja.setVisibility(GONE);
        mas.setVisibility(GONE);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** ScrollView con alto maximo (40 % de la pantalla) que se desplaza dentro de la pantalla principal. */
    private static class Caja extends ScrollView {
        Caja(Context c) { super(c); }

        @Override
        protected void onMeasure(int w, int h) {
            int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.4f);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            // mientras el dedo esta en la ventanita, la pantalla de atras no se lleva el desplazamiento
            if (e.getAction() == MotionEvent.ACTION_DOWN && (canScrollVertically(1) || canScrollVertically(-1)))
                getParent().requestDisallowInterceptTouchEvent(true);
            return super.onInterceptTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) getParent().requestDisallowInterceptTouchEvent(false);
            return super.onTouchEvent(e);
        }
    }
}
