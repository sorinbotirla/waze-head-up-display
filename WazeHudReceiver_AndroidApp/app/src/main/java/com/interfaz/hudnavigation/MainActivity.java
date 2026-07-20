package com.interfaz.hudnavigation;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.VideoView;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

public class MainActivity extends Activity {
    private HudView hudView;
    private HudUdpReceiver receiver;
    private FrameLayout rootLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        rootLayout = new FrameLayout(this);

        hudView = new HudView(this);
        hudView.setBackgroundColor(Color.BLACK);
        rootLayout.addView(
            hudView,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        );

        setContentView(rootLayout);
        showOptionalStartupLogo();

        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() { hideSystemUi(); }
        });

        receiver = new HudUdpReceiver(this, hudView);
        receiver.start();
    }

    private void showOptionalStartupLogo() {
        hudView.setAlpha(0f);

        int videoId = getResources().getIdentifier(
            "startup_logo",
            "raw",
            getPackageName()
        );

        if(videoId != 0){
            showStartupVideo(videoId);
            return;
        }

        int imageId = getResources().getIdentifier(
            "startup_logo",
            "drawable",
            getPackageName()
        );

        if(imageId != 0){
            showStartupImage(imageId);
            return;
        }

        showHudInterface();
    }

    private void showStartupVideo(final int resourceId) {
        final CenterCropVideoView videoView = new CenterCropVideoView(this);
        videoView.setBackgroundColor(Color.BLACK);
        videoView.setVideoURI(
            Uri.parse("android.resource://" + getPackageName() + "/" + resourceId)
        );

        rootLayout.addView(
            videoView,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        );

        videoView.setOnPreparedListener(mediaPlayer -> {
            videoView.setVideoDimensions(
                mediaPlayer.getVideoWidth(),
                mediaPlayer.getVideoHeight()
            );

            try {
                mediaPlayer.setLooping(false);
                mediaPlayer.setVolume(0f, 0f);
                mediaPlayer.setVideoScalingMode(
                    MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                );
            } catch (Exception ignored) {
            }

            videoView.start();
        });

        videoView.setOnInfoListener((mediaPlayer, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                videoView.setBackgroundColor(Color.TRANSPARENT);
                return true;
            }
            return false;
        });

        videoView.setOnCompletionListener(mediaPlayer -> {
            safelyRemoveStartupVideo(videoView);
            showHudInterface();
        });

        videoView.setOnErrorListener((mediaPlayer, what, extra) -> {
            safelyRemoveStartupVideo(videoView);
            showHudInterface();
            return true;
        });

        videoView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!videoView.isPlaying()) {
                    safelyRemoveStartupVideo(videoView);
                    showHudInterface();
                }
            }
        }, 2500L);
    }

    private void safelyRemoveStartupVideo(VideoView videoView) {
        try {
            videoView.stopPlayback();
        } catch (Exception ignored) {
        }

        try {
            if (videoView.getParent() == rootLayout) {
                rootLayout.removeView(videoView);
            }
        } catch (Exception ignored) {
        }
    }

    private void showStartupImage(int resourceId) {
        final ImageView imageView = new ImageView(this);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        imageView.setImageResource(resourceId);

        rootLayout.addView(
            imageView,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        );

        Drawable drawable = imageView.getDrawable();
        long displayDuration = 3000L;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            && drawable instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable animated = (AnimatedImageDrawable) drawable;
            animated.setRepeatCount(0);
            animated.start();
        }

        imageView.postDelayed(
            new Runnable() {
                @Override
                public void run() {
                    rootLayout.removeView(imageView);
                    showHudInterface();
                }
            },
            displayDuration
        );
    }

    private void showHudInterface() {
        hudView.animate()
            .alpha(1f)
            .setDuration(1000L)
            .start();
    }

    private void hideSystemUi() {
        View decorView = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() { hideSystemUi(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() { hideSystemUi(); }
        });
    }

    @Override
    protected void onDestroy() {
        if (receiver != null) receiver.stop();
        super.onDestroy();
    }
}
