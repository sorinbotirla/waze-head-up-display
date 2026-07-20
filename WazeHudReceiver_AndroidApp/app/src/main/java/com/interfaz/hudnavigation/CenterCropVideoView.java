package com.interfaz.hudnavigation;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.VideoView;

public class CenterCropVideoView extends VideoView {
    private int videoWidth;
    private int videoHeight;

    public CenterCropVideoView(Context context) {
        super(context);
    }

    public CenterCropVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CenterCropVideoView(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
    }

    public void setVideoDimensions(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);

        if (
            videoWidth <= 0
            || videoHeight <= 0
            || parentWidth <= 0
            || parentHeight <= 0
        ) {
            setMeasuredDimension(parentWidth, parentHeight);
            return;
        }

        float videoAspect = videoWidth / (float) videoHeight;
        float parentAspect = parentWidth / (float) parentHeight;

        int measuredWidth;
        int measuredHeight;

        if (videoAspect > parentAspect) {
            measuredHeight = parentHeight;
            measuredWidth = Math.round(parentHeight * videoAspect);
        } else {
            measuredWidth = parentWidth;
            measuredHeight = Math.round(parentWidth / videoAspect);
        }

        setMeasuredDimension(measuredWidth, measuredHeight);
    }
}
