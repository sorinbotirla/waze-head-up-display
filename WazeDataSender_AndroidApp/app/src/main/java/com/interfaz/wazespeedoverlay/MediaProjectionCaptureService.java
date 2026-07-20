package com.interfaz.wazespeedoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import org.json.JSONObject;

public class MediaProjectionCaptureService extends Service {
    public static final String ACTION_START = "capture.start";
    public static final String ACTION_STOP = "capture.stop";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private long lastFrame;
    private long processedFrames;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private final HeaderArrowVectorExtractor arrowVectorExtractor = new HeaderArrowVectorExtractor();
    private int sourceScreenWidth;
    private int sourceScreenHeight;

    public IBinder onBind(Intent intent) { return null; }

    public void onCreate() {
        super.onCreate();
        HudUdpTransport.get(this).start();
        HudUdpTransport.get(this).setCaptureStatus("service created");
        captureThread = new HandlerThread("WazeHeaderCaptureWorker");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        createChannel();
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            startForeground(44, notification());
            int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startCapture(code, data);
        }
        return START_STICKY;
    }

    private void startCapture(int code, Intent data) {
        if (projection != null || data == null) {
            HudUdpTransport.get(this).setCaptureStatus("start ignored: projection exists or data missing");
            return;
        }

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(code, data);
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int srcW = dm.widthPixels;
        int srcH = dm.heightPixels;
        sourceScreenWidth = srcW;
        sourceScreenHeight = srcH;
        float scale = Math.min(1f, 900f / Math.max(srcW, srcH));
        int w = Math.max(360, (int) (srcW * scale));
        int h = Math.max(360, (int) (srcH * scale));

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay(
            "WazePrimaryHeaderCapture",
            w,
            h,
            dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.getSurface(),
            null,
            null
        );

        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Image image = imageReader.acquireLatestImage();
                if (image != null) processImage(image);
            }
        }, captureHandler);

        HudUdpTransport.get(this).setCaptureStatus("capturing primary Waze header " + w + "x" + h);
    }

    private void processImage(Image image) {
        if (image == null) return;

        Bitmap bitmap = null;
        Bitmap header = null;
        Bitmap processedHeader = null;
        Bitmap scaledHeader = null;

        try {
            long now = System.currentTimeMillis();
            if (now - lastFrame < 350) return;
            lastFrame = now;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();

            Bitmap padded = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888
            );
            padded.copyPixelsFromBuffer(buffer);
            bitmap = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            padded.recycle();

            float scaleX = bitmap.getWidth() / (float) Math.max(1, sourceScreenWidth);
            float scaleY = bitmap.getHeight() / (float) Math.max(1, sourceScreenHeight);

            int statusBottom = getScaledStatusBarHeight(scaleY);
            android.graphics.Rect distanceBounds = scaleBounds(
                TelemetryState.getRawDistanceBounds(),
                scaleX,
                scaleY
            );
            android.graphics.Rect roadBounds = scaleBounds(
                TelemetryState.getRawRoadBounds(),
                scaleX,
                scaleY
            );

            int headerBottom = calculateHeaderBottom(
                bitmap,
                statusBottom,
                distanceBounds,
                roadBounds
            );

            int headerHeight = headerBottom - statusBottom;
            if (headerHeight < 40) return;

            header = Bitmap.createBitmap(
                bitmap,
                0,
                statusBottom,
                bitmap.getWidth(),
                headerHeight
            );

            processedHeader = cleanAndMaskHeader(
                header,
                statusBottom,
                distanceBounds,
                roadBounds
            );

            JSONObject layout = buildRawHeaderLayout(
                processedHeader.getWidth(),
                processedHeader.getHeight(),
                statusBottom,
                distanceBounds,
                roadBounds
            );
            TelemetryState.setRawHeaderLayout(layout);
            TelemetryState.setArrowVector(null);

            int targetWidth = Math.min(920, processedHeader.getWidth());
            int targetHeight = Math.max(
                1,
                Math.round(
                    processedHeader.getHeight()
                    * (targetWidth / (float) processedHeader.getWidth())
                )
            );

            scaledHeader = Bitmap.createScaledBitmap(
                processedHeader,
                targetWidth,
                targetHeight,
                true
            );

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaledHeader.compress(Bitmap.CompressFormat.PNG, 100, output);

            HudUdpTransport.get(this).sendHeaderImage(
                output.toByteArray(),
                scaledHeader.getWidth(),
                scaledHeader.getHeight()
            );

            processedFrames++;
            if (processedFrames % 4 == 0) {
                HudUdpTransport.get(this).setCaptureStatus(
                    "raw header PNG, frames: " + processedFrames
                    + ", crop: " + bitmap.getWidth() + "x" + headerHeight
                );
            }
        } catch (Exception error) {
            HudUdpTransport.get(this).setCaptureStatus(
                "capture error: "
                + error.getClass().getSimpleName()
                + ": "
                + String.valueOf(error.getMessage())
            );
        } finally {
            if (scaledHeader != null && scaledHeader != processedHeader) {
                scaledHeader.recycle();
            }
            if (processedHeader != null && processedHeader != header) {
                processedHeader.recycle();
            }
            if (header != null) header.recycle();
            if (bitmap != null) bitmap.recycle();
            image.close();
        }
    }

    private int getScaledStatusBarHeight(float scaleY) {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int sourceHeight = id > 0
            ? getResources().getDimensionPixelSize(id)
            : Math.round(sourceScreenHeight * 0.035f);

        return Math.max(0, Math.round(sourceHeight * scaleY));
    }

    private android.graphics.Rect scaleBounds(
        android.graphics.Rect source,
        float scaleX,
        float scaleY
    ) {
        if (source == null) return null;

        return new android.graphics.Rect(
            Math.round(source.left * scaleX),
            Math.round(source.top * scaleY),
            Math.round(source.right * scaleX),
            Math.round(source.bottom * scaleY)
        );
    }

    private int calculateHeaderBottom(
        Bitmap bitmap,
        int statusBottom,
        android.graphics.Rect distanceBounds,
        android.graphics.Rect roadBounds
    ) {
        int textBottom = statusBottom;

        if (distanceBounds != null) {
            textBottom = Math.max(textBottom, distanceBounds.bottom);
        }
        if (roadBounds != null) {
            textBottom = Math.max(textBottom, roadBounds.bottom);
        }

        int padding = Math.max(8, Math.round(bitmap.getHeight() * 0.008f));
        int fromText = textBottom + padding;
        int heuristic = findPrimaryHeaderBottom(bitmap);

        int minimum = statusBottom + Math.max(60, Math.round(bitmap.getHeight() * 0.10f));
        int maximum = Math.min(
            bitmap.getHeight(),
            statusBottom + Math.max(100, Math.round(bitmap.getHeight() * 0.24f))
        );

        int result = Math.max(minimum, fromText);
        if (heuristic > statusBottom && heuristic < maximum) {
            result = Math.max(result, heuristic);
        }

        return Math.max(minimum, Math.min(result, maximum));
    }

    private Bitmap cleanAndMaskHeader(
        Bitmap source,
        int statusBottom,
        android.graphics.Rect distanceBounds,
        android.graphics.Rect roadBounds
    ) {
        Bitmap output = source.copy(Bitmap.Config.ARGB_8888, true);
        int width = output.getWidth();
        int height = output.getHeight();

        int[] pixels = new int[width * height];
        output.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int r = Color.red(color);
            int g = Color.green(color);
            int b = Color.blue(color);
            int luminance = (r * 30 + g * 59 + b * 11) / 100;

            // Only normalize the black background. Arrow pixels stay untouched.
            if (luminance <= 10) {
                pixels[i] = Color.BLACK;
            }
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height);

        Canvas canvas = new Canvas(output);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.BLACK);
        paint.setStyle(android.graphics.Paint.Style.FILL);

        drawMaskedRect(canvas, distanceBounds, statusBottom, width, height, paint);
        drawMaskedRect(canvas, roadBounds, statusBottom, width, height, paint);

        return output;
    }

    private void drawMaskedRect(
        Canvas canvas,
        android.graphics.Rect sourceBounds,
        int statusBottom,
        int width,
        int height,
        android.graphics.Paint paint
    ) {
        if (sourceBounds == null) return;

        int padX = Math.max(5, Math.round(width * 0.008f));
        int padY = Math.max(4, Math.round(height * 0.035f));

        float left = Math.max(0, sourceBounds.left - padX);
        float top = Math.max(0, sourceBounds.top - statusBottom - padY);
        float right = Math.min(width, sourceBounds.right + padX);
        float bottom = Math.min(height, sourceBounds.bottom - statusBottom + padY);

        if (right > left && bottom > top) {
            canvas.drawRect(left, top, right, bottom, paint);
        }
    }

    private JSONObject buildRawHeaderLayout(
        int width,
        int height,
        int statusBottom,
        android.graphics.Rect distanceBounds,
        android.graphics.Rect roadBounds
    ) {
        JSONObject layout = new JSONObject();

        try {
            layout.put("width", width);
            layout.put("height", height);

            JSONObject distance = normalizedRect(
                distanceBounds,
                statusBottom,
                width,
                height
            );
            JSONObject road = normalizedRect(
                roadBounds,
                statusBottom,
                width,
                height
            );

            if (distance != null) layout.put("distance", distance);
            if (road != null) layout.put("road", road);
        } catch (Exception ignored) {
        }

        return layout;
    }

    private JSONObject normalizedRect(
        android.graphics.Rect sourceBounds,
        int statusBottom,
        int width,
        int height
    ) {
        if (sourceBounds == null || width <= 0 || height <= 0) return null;

        float left = sourceBounds.left / (float) width;
        float top = (sourceBounds.top - statusBottom) / (float) height;
        float right = sourceBounds.right / (float) width;
        float bottom = (sourceBounds.bottom - statusBottom) / (float) height;

        JSONObject out = new JSONObject();

        try {
            out.put("left", clamp01(left));
            out.put("top", clamp01(top));
            out.put("right", clamp01(right));
            out.put("bottom", clamp01(bottom));
        } catch (Exception ignored) {
        }

        return out;
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private int findPrimaryHeaderBottom(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        boolean landscape = width > height;
        int minY = Math.max(30, Math.round(height * (landscape ? 0.12f : 0.07f)));
        int maxY = Math.min(height - 1, Math.round(height * (landscape ? 0.58f : 0.30f)));
        int startX = Math.round(width * 0.08f);
        int endX = Math.round(width * 0.92f);
        int xStep = Math.max(2, width / 180);
        int yStep = Math.max(1, height / 700);
        int consecutive = 0;

        for (int y = minY; y <= maxY; y += yStep) {
            long luminance = 0;
            int count = 0;
            int brightCount = 0;
            for (int x = startX; x < endX; x += xStep) {
                int color = bitmap.getPixel(x, y);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                int l = (r * 30 + g * 59 + b * 11) / 100;
                luminance += l;
                if (l > 38) brightCount++;
                count++;
            }
            float average = count == 0 ? 0 : luminance / (float) count;
            float brightRatio = count == 0 ? 0 : brightCount / (float) count;
            boolean outsideBlackHeader = average > 24f || brightRatio > 0.23f;
            if (outsideBlackHeader) consecutive++;
            else consecutive = 0;
            if (consecutive >= 5) return Math.max(minY, y - (consecutive - 1) * yStep);
        }

        return Math.round(height * (landscape ? 0.40f : 0.18f));
    }

    private void stopCapture() {
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        display = null;
        reader = null;
        projection = null;
        HudUdpTransport.get(this).setCaptureStatus("stopped");
    }

    public void onDestroy() {
        stopCapture();
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(
                "capture",
                "Waze primary header capture",
                NotificationManager.IMPORTANCE_LOW
            ));
        }
    }

    private Notification notification() {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, "capture")
                .setContentTitle("Waze primary header capture")
                .setContentText("Streaming maneuver and lane guidance to HUD")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
        }
        return new Notification.Builder(this)
            .setContentTitle("Waze primary header capture")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build();
    }
}
