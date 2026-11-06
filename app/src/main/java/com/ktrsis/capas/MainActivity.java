package com.ktrsis.capas;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.WindowManager;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import java.util.List;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
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
    private static final int PERMISO_MIC = 9;
    private SpeechRecognizer reconocedor;
    private Intent intentVoz;
    private boolean escuchando;
    private String servicioVoz = ""; // "" = predeterminado, "ondevice" = en el dispositivo, o paquete/clase
    private String idiomaVoz = "es-US";
    private long ultimoNivel = 0;
    private TextToSpeech tts;
    private boolean ttsListo, hablandoTts;
    private final Handler manejador = new Handler(Looper.getMainLooper());

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
        iniciarTts();
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


    /* ---------------- comandos de voz ---------------- */
    private void js(String codigo) { if (web != null) web.evaluateJavascript(codigo, null); }

    private void iniciarVoz() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            evento("pide_permiso", "");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISO_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Este teléfono no tiene reconocimiento de voz (instala o activa la app de Google)", Toast.LENGTH_LONG).show();
            evento("no_disponible", "");
            js("window.vozEstado&&vozEstado('no')");
            return;
        }
        if (reconocedor == null) {
            reconocedor = crearReconocedor();
            reconocedor.setRecognitionListener(oyente);
        }
        intentVoz = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intentVoz.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        if (idiomaVoz.length() > 0) {
            intentVoz.putExtra(RecognizerIntent.EXTRA_LANGUAGE, idiomaVoz);
            intentVoz.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, idiomaVoz);
        }
        intentVoz.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intentVoz.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intentVoz.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        escuchando = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { reconocedor.startListening(intentVoz); } catch (Exception e) { reiniciarVoz(600); }
        js("window.vozEstado&&vozEstado('on')");
    }

    private SpeechRecognizer crearReconocedor() {
        evento("crear", servicioVoz.isEmpty() ? "predeterminado" : servicioVoz);
        try {
            if ("ondevice".equals(servicioVoz) && Build.VERSION.SDK_INT >= 31) return SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            if (servicioVoz.contains("/")) return SpeechRecognizer.createSpeechRecognizer(this, ComponentName.unflattenFromString(servicioVoz));
        } catch (Exception e) { evento("error_crear", e.toString()); }
        return SpeechRecognizer.createSpeechRecognizer(this);
    }

    private void evento(String tipo, String dato) {
        js("window.vozEvento&&vozEvento(" + JSONObject.quote(tipo) + "," + JSONObject.quote(dato == null ? "" : dato) + ")");
    }

    private String diagnostico() {
        try {
            JSONObject o = new JSONObject();
            o.put("modelo", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            o.put("disponible", SpeechRecognizer.isRecognitionAvailable(this));
            if (Build.VERSION.SDK_INT >= 31) o.put("enDispositivo", SpeechRecognizer.isOnDeviceRecognitionAvailable(this));
            o.put("permisoMic", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            String def = Settings.Secure.getString(getContentResolver(), "voice_recognition_service");
            o.put("predeterminado", def == null ? "" : def);
            JSONArray serv = new JSONArray();
            List<ResolveInfo> l = getPackageManager().queryIntentServices(new Intent("android.speech.RecognitionService"), 0);
            for (ResolveInfo r : l) serv.put(r.serviceInfo.packageName + "/" + r.serviceInfo.name);
            o.put("servicios", serv);
            o.put("servicioUsado", servicioVoz.isEmpty() ? "predeterminado" : servicioVoz);
            o.put("idioma", idiomaVoz.isEmpty() ? "del sistema" : idiomaVoz);
            o.put("escuchando", escuchando);
            return o.toString();
        } catch (Exception e) { return "{\"error\":" + JSONObject.quote(e.toString()) + "}"; }
    }

    private void detenerVoz() {
        escuchando = false;
        manejador.removeCallbacksAndMessages(null);
        if (reconocedor != null) { try { reconocedor.cancel(); } catch (Exception ignored) {} }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        js("window.vozEstado&&vozEstado('off')");
    }

    private void reiniciarVoz(long espera) {
        if (!escuchando || hablandoTts) return;
        manejador.removeCallbacksAndMessages(null);
        manejador.postDelayed(() -> {
            if (!escuchando || reconocedor == null || hablandoTts) return;
            try { reconocedor.cancel(); reconocedor.startListening(intentVoz); }
            catch (Exception e) { reiniciarVoz(800); }
        }, espera);
    }

    private final RecognitionListener oyente = new RecognitionListener() {
        @Override public void onResults(Bundle r) {
            ArrayList<String> t = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            evento("resultado", t == null ? "(vacío)" : t.toString());
            if (t != null && !t.isEmpty()) js("window.comandoVoz&&comandoVoz(" + new JSONArray(t) + ")");
            reiniciarVoz(150);
        }
        @Override public void onPartialResults(Bundle r) {
            ArrayList<String> t = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (t != null && !t.isEmpty()) js("window.vozParcial&&vozParcial(" + JSONObject.quote(t.get(0)) + ")");
        }
        @Override public void onError(int error) {
            evento("error", String.valueOf(error));
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { detenerVoz(); return; }
            reiniciarVoz(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 700 : 250);
        }
        @Override public void onReadyForSpeech(Bundle p) { evento("listo", ""); }
        @Override public void onBeginningOfSpeech() { evento("habla", ""); }
        @Override public void onRmsChanged(float v) {
            long t = System.currentTimeMillis();
            if (t - ultimoNivel > 120) { ultimoNivel = t; js("window.vozNivel&&vozNivel(" + v + ")"); }
        }
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() { evento("fin", ""); }
        @Override public void onEvent(int t, Bundle p) {}
    };

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != PERMISO_MIC) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) iniciarVoz();
        else { Toast.makeText(this, "Sin permiso de micrófono no puedo escuchar comandos", Toast.LENGTH_LONG).show(); js("window.vozEstado&&vozEstado('off')"); }
    }

    @Override protected void onPause() { super.onPause(); if (escuchando) detenerVoz(); }
    @Override protected void onResume() { super.onResume(); if (paginaLista) js("window.alVolver&&alVolver()"); }
    @Override protected void onDestroy() { super.onDestroy(); if (reconocedor != null) reconocedor.destroy(); if (tts != null) tts.shutdown(); }

    /* ---------------- voz de respuesta ---------------- */
    private void iniciarTts() {
        tts = new TextToSpeech(this, estado -> {
            if (estado != TextToSpeech.SUCCESS) { evento("tts", "no disponible"); return; }
            int r = tts.setLanguage(new Locale("es", "US"));
            if (r < 0) r = tts.setLanguage(new Locale("es", "ES"));
            if (r < 0) tts.setLanguage(new Locale("es"));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { runOnUiThread(() -> {
                    hablandoTts = true; manejador.removeCallbacksAndMessages(null);
                    if (reconocedor != null) { try { reconocedor.cancel(); } catch (Exception ignored) {} } }); }
                @Override public void onDone(String id) { runOnUiThread(() -> { hablandoTts = false; if (escuchando) reiniciarVoz(250); }); }
                @Override public void onError(String id) { runOnUiThread(() -> { hablandoTts = false; if (escuchando) reiniciarVoz(250); }); }
            });
            ttsListo = true;
            evento("tts", "lista");
        });
    }

    /* ---------------- llamada a la API de Claude ---------------- */
    private void llamarClaude(String id, String clave, String cuerpo) {
        try {
            JSONObject h = new JSONObject();
            h.put("x-api-key", clave); h.put("anthropic-version", "2023-06-01");
            llamarHttp(id, "https://api.anthropic.com/v1/messages", h.toString(), cuerpo);
        } catch (Exception e) { evento("error", e.toString()); }
    }

    /** POST JSON genérico (Claude, Gemini o un servidor propio). Responde a iaRespuesta(id, código, texto). */
    private void llamarHttp(String id, String url, String encabezados, String cuerpo) {
        new Thread(() -> {
            int codigo = 0; String res;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                boolean get = cuerpo == null || cuerpo.isEmpty();
                c.setRequestMethod(get ? "GET" : "POST");
                c.setConnectTimeout(15000); c.setReadTimeout(45000); c.setDoOutput(!get);
                c.setRequestProperty("content-type", "application/json");
                JSONObject h = new JSONObject(encabezados == null || encabezados.isEmpty() ? "{}" : encabezados);
                java.util.Iterator<String> it = h.keys();
                while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, h.getString(k)); }
                if (!get) try (OutputStream o = c.getOutputStream()) { o.write(cuerpo.getBytes("UTF-8")); }
                codigo = c.getResponseCode();
                InputStream in = codigo < 400 ? c.getInputStream() : c.getErrorStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
                if (in != null) while ((n = in.read(b)) > 0) out.write(b, 0, n);
                res = out.toString("UTF-8");
            } catch (Exception e) {
                res = "{\"error\":{\"type\":\"red\",\"message\":" + JSONObject.quote(e.toString()) + "}}";
            }
            final int cod = codigo; final String r = res;
            runOnUiThread(() -> js("window.iaRespuesta&&iaRespuesta(" + JSONObject.quote(id) + "," + cod + "," + JSONObject.quote(r) + ")"));
        }).start();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onNativeBack&&window.onNativeBack()", r -> {
            if (!"true".equals(r)) MainActivity.super.onBackPressed();
        });
    }

    class Puente {
        /** Prende o apaga la escucha de comandos de voz. */
        @JavascriptInterface
        public void voz(boolean prender) { runOnUiThread(() -> { if (prender) iniciarVoz(); else detenerVoz(); }); }

        /** Datos para la pantalla de diagnóstico de voz. */
        @JavascriptInterface
        public String vozDiag() { return diagnostico(); }

        /** Cambia el servicio de reconocimiento y el idioma, y reinicia la escucha. */
        @JavascriptInterface
        public void hablar(String texto) {
            runOnUiThread(() -> {
                if (!ttsListo || texto == null || texto.isEmpty()) return;
                tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime());
            });
        }

        /** Envía un mensaje a la API de Claude; la respuesta llega a claudeRespuesta(id, código, texto). */
        @JavascriptInterface
        public void claude(String id, String clave, String cuerpo) { llamarClaude(id, clave, cuerpo); }

        @JavascriptInterface
        public void http(String id, String url, String encabezados, String cuerpo) { llamarHttp(id, url, encabezados, cuerpo); }

        @JavascriptInterface
        public void vozConfig(String servicio, String idioma) {
            runOnUiThread(() -> {
                boolean estaba = escuchando;
                detenerVoz();
                if (reconocedor != null) { try { reconocedor.destroy(); } catch (Exception ignored) {} reconocedor = null; }
                servicioVoz = servicio == null ? "" : servicio;
                idiomaVoz = idioma == null ? "" : idioma;
                evento("config", "servicio=" + (servicioVoz.isEmpty() ? "predeterminado" : servicioVoz) + " idioma=" + (idiomaVoz.isEmpty() ? "sistema" : idiomaVoz));
                if (estaba) iniciarVoz();
            });
        }

        /** Copia texto al portapapeles. */
        @JavascriptInterface
        public void copiar(String texto) {
            runOnUiThread(() -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("prompt", texto));
                Toast.makeText(MainActivity.this, "Prompt copiado", Toast.LENGTH_SHORT).show();
            });
        }

        /** Abre un enlace afuera (por ejemplo Gemini). */
        @JavascriptInterface
        public void abrir(String url) {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "No se pudo abrir", Toast.LENGTH_SHORT).show(); }
            });
        }

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
