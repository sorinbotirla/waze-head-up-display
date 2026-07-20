package com.interfaz.wazespeedoverlay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements HudUdpTransport.Listener {
    private static final int REQUEST_CAPTURE = 701;
    private TextView status;

    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        HudUdpTransport.get(this).setListener(this);
        HudUdpTransport.get(this).start();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(32, 48, 32, 32);
        root.setBackgroundColor(Color.rgb(12, 15, 20));

        TextView title = new TextView(this);
        title.setText("Waze Raw Header Sender V22");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("Accessibility reads current speed and speed limit. Screen capture streams Waze's complete primary black header, preserving lane arrows, roundabouts, exits and road text. The secondary 'then' row is excluded.");
        description.setTextColor(Color.LTGRAY);
        description.setTextSize(15);
        description.setPadding(0, 20, 0, 20);
        root.addView(description);

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(14);
        status.setPadding(0, 12, 0, 20);
        status.setText("1. Enable Accessibility\n2. Allow floating overlay\n3. Start Waze Primary Header Stream\n4. Open Waze navigation");
        root.addView(status);

        root.addView(button("Enable Accessibility", new View.OnClickListener() {
            public void onClick(View view) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));

        root.addView(button("Allow Floating Overlay", new View.OnClickListener() {
            public void onClick(View view) {
                if (Build.VERSION.SDK_INT >= 23) {
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                    );
                    startActivity(intent);
                }
            }
        }));

        root.addView(button("Show Floating Speed Sign", new View.OnClickListener() {
            public void onClick(View view) {
                WazeSpeedOverlayManager.get(MainActivity.this).show();
            }
        }));

        root.addView(button("Start Waze Primary Header Stream", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
            }
        }));

        setContentView(root);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE || resultCode != RESULT_OK || data == null) return;

        Intent service = new Intent(this, MediaProjectionCaptureService.class);
        service.setAction(MediaProjectionCaptureService.ACTION_START);
        service.putExtra(MediaProjectionCaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(MediaProjectionCaptureService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 8, 0, 8);
        button.setLayoutParams(params);
        return button;
    }

    @Override
    public void onHudStatus(final String value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (status != null) status.setText(value);
            }
        });
    }
}
