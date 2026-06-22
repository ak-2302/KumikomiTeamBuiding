package com.example.kumikomiteambuiding;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.List;

public final class FocusTimerView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint distractedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final RectF segmentBounds = new RectF();

    private long totalDurationMs = 1L;
    private long elapsedMs;
    private FocusSessionManager.Phase phase = FocusSessionManager.Phase.FOCUS;
    private List<FocusSessionManager.Segment> segments = Collections.emptyList();

    public FocusTimerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStrokeWidth(18f * density);
        trackPaint.setColor(ContextCompat.getColor(context, R.color.track));

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeWidth(18f * density);

        distractedPaint.setStyle(Paint.Style.STROKE);
        distractedPaint.setStrokeCap(Paint.Cap.BUTT);
        distractedPaint.setStrokeWidth(7f * density);
        distractedPaint.setColor(ContextCompat.getColor(context, R.color.distracted));
    }

    public void setSession(FocusSessionManager.Snapshot snapshot) {
        totalDurationMs = Math.max(1L, snapshot.totalDurationMs);
        elapsedMs = snapshot.elapsedMs;
        phase = snapshot.phase;
        segments = snapshot.distractedSegments;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float baseRadius = Math.min(getWidth(), getHeight()) * 0.36f;
        float segmentRadius = baseRadius + distractedPaint.getStrokeWidth() * 2.2f;

        arcBounds.set(
                centerX - baseRadius,
                centerY - baseRadius,
                centerX + baseRadius,
                centerY + baseRadius
        );
        segmentBounds.set(
                centerX - segmentRadius,
                centerY - segmentRadius,
                centerX + segmentRadius,
                centerY + segmentRadius
        );

        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint);
        progressPaint.setColor(ContextCompat.getColor(
                getContext(),
                phase == FocusSessionManager.Phase.FOCUS ? R.color.focus : R.color.break_color
        ));
        float elapsedSweep = 360f * Math.min(1f, elapsedMs / (float) totalDurationMs);
        if (elapsedSweep > 0f) {
            canvas.drawArc(arcBounds, -90f, elapsedSweep, false, progressPaint);
        }

        if (phase != FocusSessionManager.Phase.FOCUS) {
            return;
        }
        for (FocusSessionManager.Segment segment : segments) {
            float start = -90f + 360f * segment.startMs / totalDurationMs;
            float sweep = 360f * (segment.endMs - segment.startMs) / totalDurationMs;
            if (sweep > 0f) {
                canvas.drawArc(segmentBounds, start, sweep, false, distractedPaint);
            }
        }
    }
}
