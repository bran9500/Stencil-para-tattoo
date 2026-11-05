package com.ktrsis.capas;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Una sola pantalla: la app web de capas dentro de un WebView, con selector de imágenes y guardado en la galería. */
public class MainActivity extends Activity {
    private static final int ELEGIR = 7;
    private static final int FONDO = 0xFF0E0F11;
    private WebView web;
    private ValueCallback<Uri[]> esperaArchivo;
    private Uri compartidaPendiente;
    private boolean paginaLista;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(FONDO);
        getWindow().setNavigationBarColor(FONDO);

        web = new WebView(this);
        web.setBackgroundColor(FONDO);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);   // para leer los píxeles de la imagen de ejemplo
        web.addJavascriptInterface(new Puente(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                paginaLista = true;
                if (compartidaPendiente != null) { abrirCompartida(compartidaPendiente); compartidaPendiente = null; }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (esperaArchivo != null) esperaArchivo.onReceiveValue(null);
                esperaArchivo = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Elegir imagen"), ELEGIR);
                } catch (Exception e) {
                    esperaArchivo = null;
                    return false;
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");

        // Margen para no quedar debajo de la barra de estado ni de la de navegación (Android 15 dibuja hasta el borde).
        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(FONDO);
        raiz.addView(web, new FrameLayout.LayoutParams(-1, -1));
        int arriba = dimen("status_bar_height"), abajo = dimen("navigation_bar_height");
        raiz.setPadding(0, arriba, 0, abajo);
        if (Build.VERSION.SDK_INT >= 30) {
            raiz.setOnApplyWindowInsetsListener((v, ins) -> {
                android.graphics.Insets bars = ins.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, Math.max(bars.top, arriba), bars.right, bars.bottom);
                return android.view.WindowInsets.CONSUMED;
            });
        }
        setContentView(raiz);
        recibir(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); recibir(i); }

    /** Imagen compartida desde la galería u otra app. */
    private void recibir(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        if (paginaLista) abrirCompartida(u); else compartidaPendiente = u;
    }

    private void abrirCompartida(Uri u) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String tipo = getContentResolver().getType(u);
            if (tipo == null) tipo = "image/jpeg";
            String datos = "data:" + tipo + ";base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            web.evaluateJavascript("window.abrirDesdeAndroid&&window.abrirDesdeAndroid(" + jsTexto(datos) + ",'compartida')", null);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir la imagen", Toast.LENGTH_SHORT).show();
        }
    }

    private static String jsTexto(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"; }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != ELEGIR || esperaArchivo == null) return;
        Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
        esperaArchivo.onReceiveValue(u != null ? new Uri[]{u} : null);
        esperaArchivo = null;
    }

    private int dimen(String nombre) {
        int id = getResources().getIdentifier(nombre, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onNativeBack&&window.onNativeBack()", r -> {
            if (!"true".equals(r)) MainActivity.super.onBackPressed();
        });
    }

    class Puente {
        /** Guarda un PNG (en base64) en Galería › Pictures/Capas. */
        @JavascriptInterface
        public boolean guardarImagen(String base64, String nombre) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, nombre);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Capas");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                ContentResolver cr = getContentResolver();
                Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (u == null) return false;
                try (OutputStream o = cr.openOutputStream(u)) { o.write(bytes); }
                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(u, cv, null, null);
                return true;
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error al guardar: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return false;
            }
        }
    }
}
