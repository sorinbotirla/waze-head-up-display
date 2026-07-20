package com.interfaz.wazespeedoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public class WazeSpeedOverlayManager {
    private static WazeSpeedOverlayManager instance;
    private Context context;
    private WindowManager windowManager;
    private FrameLayout rootView;
    private SpeedSignView speedSignView;
    private TextView debugView;
    private WindowManager.LayoutParams params;
    private boolean showing = false;
    private boolean debugExpanded = false;
    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private long downTime;

    public static WazeSpeedOverlayManager get(Context ctx) {
        if (instance == null) {
            instance = new WazeSpeedOverlayManager(ctx.getApplicationContext());
        }
        return instance;
    }

    private WazeSpeedOverlayManager(Context ctx) {
        context = ctx;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean canDraw() {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    public void show() {
        if (showing) {
            return;
        }

        if (!canDraw()) {
            return;
        }

        rootView = new FrameLayout(context);
        speedSignView = new SpeedSignView(context);
        debugView = new TextView(context);

        FrameLayout.LayoutParams signParams = new FrameLayout.LayoutParams(dp(96), dp(96));
        signParams.gravity = Gravity.TOP | Gravity.LEFT;
        rootView.addView(speedSignView, signParams);

        FrameLayout.LayoutParams debugParams = new FrameLayout.LayoutParams(dp(250), WindowManager.LayoutParams.WRAP_CONTENT);
        debugParams.leftMargin = dp(104);
        debugParams.topMargin = dp(10);
        debugView.setTextColor(Color.WHITE);
        debugView.setTextSize(12);
        debugView.setPadding(dp(8), dp(6), dp(8), dp(6));
        debugView.setBackgroundColor(0xcc111111);
        debugView.setVisibility(View.GONE);
        rootView.addView(debugView, debugParams);

        rootView.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                return handleTouch(event);
            }
        });

        int type;
        if (Build.VERSION.SDK_INT >= 26) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
            dp(360),
            dp(120),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = dp(20);
        params.y = dp(240);

        windowManager.addView(rootView, params);
        showing = true;
    }

    public void hide() {
        if (!showing) {
            return;
        }
        try {
            windowManager.removeView(rootView);
        } catch (Exception ignored) {}
        showing = false;
    }

    public void setSpeed(String speed, String debug) {
        show();
        if (!showing) {
            return;
        }
        speedSignView.setSpeed(speed);
        debugView.setText(debug == null ? "" : debug);
    }

    private boolean handleTouch(MotionEvent event) {
        if (params == null) {
            return false;
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = event.getRawX();
            downY = event.getRawY();
            startX = params.x;
            startY = params.y;
            downTime = System.currentTimeMillis();
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            params.x = startX + (int) (event.getRawX() - downX);
            params.y = startY + (int) (event.getRawY() - downY);
            windowManager.updateViewLayout(rootView, params);
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_UP) {
            float dx = Math.abs(event.getRawX() - downX);
            float dy = Math.abs(event.getRawY() - downY);
            long dt = System.currentTimeMillis() - downTime;
            if (dx < 8 && dy < 8 && dt > 450) {
                debugExpanded = !debugExpanded;
                debugView.setVisibility(debugExpanded ? View.VISIBLE : View.GONE);
            }
            return true;
        }

        return false;
    }

    private int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static class SpeedSignView extends View {
        private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String speed = "--";

        public SpeedSignView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        public void setSpeed(String s) {
            speed = s == null || s.length() == 0 ? "--" : s;
            invalidate();
        }

        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            int size = Math.min(w, h);
            float cx = w / 2f;
            float cy = h / 2f;
            float r = size * 0.43f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, r, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.10f);
            paint.setColor(0xffff0000);
            canvas.drawCircle(cx, cy, r - paint.getStrokeWidth() / 2f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);

            if (speed.length() <= 2) {
                paint.setTextSize(size * 0.36f);
            } else {
                paint.setTextSize(size * 0.30f);
            }

            Paint.FontMetrics fm = paint.getFontMetrics();
            float y = cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(speed, cx, y, paint);
        }
    }
}
