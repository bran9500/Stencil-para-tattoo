package com.bran.camaratattoo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#111111"));
        int p = dp(28);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("Cámara Tattoo");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Ajustes del modo PRO en una ventana flotante.\nAbre la cámara después de activarla.");
        sub.setTextColor(Color.parseColor("#BBBBBB"));
        sub.setTextSize(15);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(12), 0, dp(24));
        root.addView(sub);

        Button start = new Button(this);
        start.setText("Mostrar ventana flotante");
        start.setOnClickListener(v -> startFloat());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Cerrar ventana");
        stop.setOnClickListener(v -> stopService(new Intent(this, FloatService.class)));
        root.addView(stop);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#FFB300"));
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(20), 0, 0);
        root.addView(status);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void startFloat() {
        if (!Settings.canDrawOverlays(this)) {
            status.setText("Activa \"Mostrar sobre otras apps\" para Cámara Tattoo y vuelve a tocar el botón.");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Intent i = new Intent(this, FloatService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        status.setText("Listo. Abre la cámara en modo PRO.");
        moveTaskToBack(true);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
