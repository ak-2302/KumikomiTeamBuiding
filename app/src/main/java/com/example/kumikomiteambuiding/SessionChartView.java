package com.example.kumikomiteambuiding;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public final class SessionChartView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint distractedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private float[] bins = new float[0];

    public SessionChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setColor(ContextCompat.getColor(context, R.color.track));
        focusedPaint.setColor(ContextCompat.getColor(context, R.color.focus));
        distractedPaint.setColor(ContextCompat.getColor(context, R.color.distracted));
        labelPaint.setColor(ContextCompat.getColor(context, R.color.ink_muted));
        labelPaint.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setBins(float[] bins) {
        this.bins = bins != null ? bins.clone() : new float[0];
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bins.length == 0) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float chartTop = 12f * density;
        float chartBottom = getHeight() - 34f * density;
        float chartHeight = chartBottom - chartTop;
        float gap = 9f * density;
        float barWidth = (getWidth() - gap * (bins.length - 1)) / bins.length;
        float radius = Math.min(6f * density, barWidth / 3f);

        for (int index = 0; index < bins.length; index++) {
            float left = index * (barWidth + gap);
            float ratio = bins[index];
            if (ratio < 0f) {
                bar.set(left, chartBottom - 5f * density, left + barWidth, chartBottom);
                canvas.drawRoundRect(bar, radius, radius, trackPaint);
                continue;
            }

            bar.set(left, chartTop, left + barWidth, chartBottom);
            canvas.drawRoundRect(bar, radius, radius, trackPaint);
            float focusedTop = chartBottom - chartHeight * ratio;
            bar.set(left, focusedTop, left + barWidth, chartBottom);
            canvas.drawRoundRect(bar, radius, radius, focusedPaint);
            if (ratio < 1f) {
                bar.set(left, chartTop, left + barWidth, focusedTop);
                canvas.drawRoundRect(bar, radius, radius, distractedPaint);
            }
        }

        canvas.drawText("開始", 0f, getHeight() - 6f * density, labelPaint);
        String endLabel = "現在";
        canvas.drawText(
                endLabel,
                getWidth() - labelPaint.measureText(endLabel),
                getHeight() - 6f * density,
                labelPaint
        );
    }
}
