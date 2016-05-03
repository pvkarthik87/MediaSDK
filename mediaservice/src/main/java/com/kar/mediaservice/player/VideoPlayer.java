package com.kar.mediaservice.player;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import com.google.android.exoplayer.AspectRatioFrameLayout;

/**
 * Created by Karthik on 5/3/2016.
 */
public class VideoPlayer extends FrameLayout {

    private static final float MAX_ASPECT_RATIO_DEFORMATION_FRACTION = 0.01f;

    private float videoAspectRatio;

    public VideoPlayer(Context context) {
        super(context);
    }

    public VideoPlayer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Set the aspect ratio that this view should satisfy.
     *
     * @param widthHeightRatio The width to height ratio.
     */
    public void setAspectRatio(float widthHeightRatio) {
        if (this.videoAspectRatio != widthHeightRatio) {
            this.videoAspectRatio = widthHeightRatio;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (videoAspectRatio == 0) {
            // Aspect ratio not set.
            return;
        }

        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        float viewAspectRatio = (float) width / height;
        float aspectDeformation = videoAspectRatio / viewAspectRatio - 1;
        if (Math.abs(aspectDeformation) <= MAX_ASPECT_RATIO_DEFORMATION_FRACTION) {
            // We're within the allowed tolerance.
            return;
        }

        if (aspectDeformation > 0) {
            height = (int) (width / videoAspectRatio);
        } else {
            width = (int) (height * videoAspectRatio);
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

}
