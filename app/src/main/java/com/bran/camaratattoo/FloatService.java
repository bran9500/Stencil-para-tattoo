package com.bran.camaratattoo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatService extends Service {

    // Three tabs, each with its own sub-modes: rows of {label, value} plus a tip
    private static final String[] TABS = {"FOTO", "VIDEO", "AJUSTES"};

    private static final String[][] MODES = {
        {"Ventana", "Lámpara", "Fresco", "Trípode"},
        {"Mano", "Trípode"},
        {"Una vez"},
    };

    private static final String[][][][] ROWS = {
        { // FOTO (modo PRO)
            {{"ISO", "100"}, {"S", "A (auto)"}, {"EV", "-0.3 a -0.7"},
             {"AF", "M, enfoca la figura"}, {"WB", "5200K"}},
            {{"ISO", "100"}, {"S", "A (auto)"}, {"EV", "-0.7"},
             {"AF", "M, enfoca la figura"}, {"WB", "5500K blanca / 3500K cálida"}},
            {{"ISO", "100"}, {"S", "A (auto)"}, {"EV", "-1.0"},
             {"AF", "M, enfoca la figura"}, {"WB", "5200K"}},
            {{"ISO", "50"}, {"S", "1/30 a 1/60"}, {"EV", "(no aplica)"},
             {"AF", "M, enfoca la figura"}, {"WB", "5200K"}},
        },
        { // VIDEO PRO
            {{"ISO", "100-400 (manual)"}, {"S", "1/60"}, {"±", "no aplica en manual"},
             {"AF", "MF, enfoca la figura"}, {"WB", "5200K (sin A)"},
             {"Lente", "W (T para detalle)"}, {"Calidad", "UHD 30 · 9:16"},
             {"Estab.", "ENCENDIDA"}},
            {{"ISO", "100-400 (manual)"}, {"S", "1/60"}, {"±", "no aplica en manual"},
             {"AF", "MF, enfoca la figura"}, {"WB", "5200K (sin A)"},
             {"Lente", "W (T para detalle)"}, {"Calidad", "UHD 30 · 9:16"},
             {"Estab.", "APAGADA"}},
        },
        { // AJUSTES de la cámara (se ponen una sola vez)
            {{"Optimizador", "Priorizar calidad"}, {"Filtros", "ninguno"},
             {"Formato foto", "RAW si editas, si no JPEG"}, {"Marca agua", "apagada"},
             {"Fotos mov.", "apagadas"}, {"Video", "HEVC (H.264 si falla)"},
             {"FPS auto", "apagado"}, {"HDR10+", "apagado"},
             {"Cuadrícula", "encendida"}, {"Histograma", "encendido"},
             {"Flash", "apagado"}, {"Perfil color", "Normal, no LOG"}},
        },
    };

    private static final String[][] TIPS = {
        {"Luz de ventana de lado. 50M activado.",
         "Lámpara de lado, no de frente.",
         "Seca el brillo con papel. Luz de lado.",
         "Temporizador 2 s. Si sale oscura: S 1/30."},
        {"Mantén el dedo en el tatuaje: AE/AF bloqueado.",
         "Mantén el dedo en el tatuaje: AE/AF bloqueado."},
        {"Se configuran una sola vez en ⚙ de la cámara."},
    };
    private static final String HISTO = "Histograma: que no toque el borde derecho. Si ISO pasa de 800, agrega luz.";

    private WindowManager wm;
    private View panel, bubble;
    private WindowManager.LayoutParams lp;
    private LinearLayout rowsBox;
    private TextView tipView;
    private LinearLayout modesBox;
    private TextView[] tabBtns = new TextView[TABS.length];
    private TextView[] modeBtns = new TextView[0];
    private int tab = 0, mode = 0;
    private final float[] alphas = {0.92f, 0.7f, 0.45f};
    private int alphaIdx = 0;
    private SharedPreferences prefs;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        tab = prefs.getInt("tab", 0);
        mode = prefs.getInt("mode", 0);
        alphaIdx = prefs.getInt("alpha", 0);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("x", dp(12));
        lp.y = prefs.getInt("y", dp(120));

        panel = buildPanel();
        bubble = buildBubble();
        showPanel(!prefs.getBoolean("min", false));
    }

    private void startAsForeground() {
        String ch = "float";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(ch, "Ventana flotante",
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, ch)
                .setContentTitle("Cámara Tattoo")
                .setContentText("Ventana de ajustes activa")
                .setSmallIcon(R.drawable.ic_app)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    // ---------- UI ----------

    private View buildPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(6), dp(10), dp(8));
        box.setBackground(round(Color.argb(215, 15, 15, 15), dp(14)));
        box.setAlpha(alphas[alphaIdx]);

        // header: drag handle + buttons
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("⠿ PRO", 13, Color.parseColor("#FFC400"), true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(title);
        TextView op = text("◐", 18, Color.WHITE, false);
        op.setPadding(dp(10), 0, dp(10), 0);
        op.setOnClickListener(v -> {
            alphaIdx = (alphaIdx + 1) % alphas.length;
            panel.setAlpha(alphas[alphaIdx]);
            prefs.edit().putInt("alpha", alphaIdx).apply();
        });
        head.addView(op);
        TextView min = text("—", 18, Color.WHITE, true);
        min.setPadding(dp(10), 0, dp(10), 0);
        min.setOnClickListener(v -> showPanel(false));
        head.addView(min);
        TextView close = text("✕", 16, Color.parseColor("#FF6B6B"), true);
        close.setPadding(dp(10), 0, dp(4), 0);
        close.setOnClickListener(v -> stopSelf());
        head.addView(close);
        drag(head);
        box.addView(head);

        // tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, dp(4), 0, dp(2));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = chip(TABS[i]);
            t.setOnClickListener(v -> setTab(idx));
            tabBtns[i] = t;
            tabs.addView(t);
        }
        box.addView(tabs);

        // sub-modes of the current tab
        modesBox = new LinearLayout(this);
        modesBox.setPadding(0, dp(2), 0, dp(4));
        box.addView(modesBox);

        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(rowsBox);

        tipView = text("", 11, Color.parseColor("#BBBBBB"), false);
        tipView.setPadding(0, dp(4), 0, 0);
        tipView.setMaxWidth(dp(230));
        box.addView(tipView);

        setTab(tab, mode);
        return box;
    }

    private TextView chip(String label) {
        TextView b = text(label, 11, Color.WHITE, true);
        b.setPadding(dp(7), dp(3), dp(7), dp(3));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(0, 0, dp(4), 0);
        b.setLayoutParams(p);
        return b;
    }

    private void paint(TextView b, boolean on, int onColor) {
        b.setBackground(round(on ? onColor : Color.argb(60, 255, 255, 255), dp(8)));
        b.setTextColor(on ? Color.BLACK : Color.WHITE);
    }

    private void setTab(int t) { setTab(t, 0); }

    private void setTab(int t, int m) {
        tab = t;
        for (int i = 0; i < tabBtns.length; i++) paint(tabBtns[i], i == t, Color.parseColor("#FFC400"));
        modesBox.removeAllViews();
        modeBtns = new TextView[MODES[t].length];
        for (int i = 0; i < MODES[t].length; i++) {
            final int idx = i;
            TextView b = chip(MODES[t][i]);
            b.setOnClickListener(v -> setMode(idx));
            modeBtns[i] = b;
            modesBox.addView(b);
        }
        modesBox.setVisibility(MODES[t].length > 1 ? View.VISIBLE : View.GONE);
        setMode(Math.min(m, MODES[t].length - 1));
    }

    private void setMode(int m) {
        mode = m;
        prefs.edit().putInt("tab", tab).putInt("mode", m).apply();
        for (int i = 0; i < modeBtns.length; i++) paint(modeBtns[i], i == m, Color.parseColor("#80DEEA"));
        rowsBox.removeAllViews();
        for (String[] r : ROWS[tab][m]) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(1), 0, dp(1));
            TextView k = text(r[0], 12, Color.parseColor("#FFC400"), true);
            k.setMinWidth(dp(tab == 2 ? 86 : 52));
            row.addView(k);
            TextView v = text(r[1], 12, Color.WHITE, true);
            v.setMaxWidth(dp(190));
            row.addView(v);
            rowsBox.addView(row);
        }
        tipView.setText(TIPS[tab][m] + (tab == 2 ? "" : "\n" + HISTO));
    }

    private View buildBubble() {
        TextView b = text("PRO", 12, Color.BLACK, true);
        b.setGravity(Gravity.CENTER);
        int s = dp(44);
        b.setWidth(s);
        b.setHeight(s);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.argb(200, 255, 196, 0));
        b.setBackground(g);
        drag(b);
        return b;
    }

    private void showPanel(boolean show) {
        prefs.edit().putBoolean("min", !show).apply();
        View add = show ? panel : bubble;
        View rem = show ? bubble : panel;
        if (rem.isAttachedToWindow()) wm.removeView(rem);
        if (!add.isAttachedToWindow()) wm.addView(add, lp);
    }

    // drag; a tap on the bubble expands it
    private void drag(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty; boolean moved;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = lp.x; sy = lp.y; tx = e.getRawX(); ty = e.getRawY(); moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - tx), dy = (int) (e.getRawY() - ty);
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        lp.x = sx + dx; lp.y = sy + dy;
                        View cur = panel.isAttachedToWindow() ? panel : bubble;
                        wm.updateViewLayout(cur, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                        if (!moved && view == bubble) showPanel(true);
                        return true;
                }
                return false;
            }
        });
    }

    // ---------- helpers ----------

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        if (panel != null && panel.isAttachedToWindow()) wm.removeView(panel);
        if (bubble != null && bubble.isAttachedToWindow()) wm.removeView(bubble);
        super.onDestroy();
    }
}
