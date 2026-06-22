package com.example.kumikomiteambuiding;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FocusSessionManager {
    public static final long FOCUS_DURATION_MS = 25L * 60L * 1000L;
    public static final long BREAK_DURATION_MS = 5L * 60L * 1000L;
    private static final int CHART_BIN_COUNT = 8;
    private static final String PREFERENCES_NAME = "focus_session";

    private static volatile FocusSessionManager instance;

    private final SharedPreferences preferences;
    private final List<Segment> currentSegments = new ArrayList<>();
    private final List<Segment> lastSegments = new ArrayList<>();

    private Phase phase = Phase.FOCUS;
    private boolean running;
    private long totalDurationMs = FOCUS_DURATION_MS;
    private long remainingMs = FOCUS_DURATION_MS;
    private long endTimeMs;
    private long activeDistractedStartMs = -1L;
    private String lastFocusStatus;
    private long lastStatsDurationMs;
    private long lastStatsElapsedMs;

    private FocusSessionManager(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
        restore();
    }

    public static FocusSessionManager get(Context context) {
        if (instance == null) {
            synchronized (FocusSessionManager.class) {
                if (instance == null) {
                    instance = new FocusSessionManager(context);
                }
            }
        }
        return instance;
    }

    public synchronized Snapshot getSnapshot() {
        advanceCompletedPhase();
        long remaining = getRemainingMs();
        long elapsed = Math.max(0L, totalDurationMs - remaining);
        return new Snapshot(
                phase,
                running,
                totalDurationMs,
                remaining,
                elapsed,
                snapshotCurrentSegments(elapsed)
        );
    }

    public synchronized StatsSnapshot getStatsSnapshot() {
        advanceCompletedPhase();
        long currentElapsed = totalDurationMs - getRemainingMs();
        boolean useCurrent = phase == Phase.FOCUS && currentElapsed > 0L;
        long duration = useCurrent ? totalDurationMs : lastStatsDurationMs;
        long elapsed = useCurrent ? currentElapsed : lastStatsElapsedMs;
        List<Segment> segments = useCurrent
                ? snapshotCurrentSegments(currentElapsed)
                : copySegments(lastSegments);

        long distracted = calculateDistractedMs(segments, elapsed);
        long focused = Math.max(0L, elapsed - distracted);
        int rate = elapsed > 0L ? (int) Math.round((focused * 100.0) / elapsed) : 0;
        return new StatsSnapshot(
                rate,
                focused,
                distracted,
                calculateBins(segments, duration, elapsed)
        );
    }

    public synchronized void toggleTimer() {
        advanceCompletedPhase();
        if (running) {
            remainingMs = getRemainingMs();
            closeActiveDistraction(totalDurationMs - remainingMs);
            running = false;
            endTimeMs = 0L;
        } else {
            if (remainingMs <= 0L) {
                remainingMs = totalDurationMs;
            }
            if (phase == Phase.FOCUS && remainingMs == totalDurationMs) {
                currentSegments.clear();
                activeDistractedStartMs = -1L;
                lastFocusStatus = null;
            }
            running = true;
            endTimeMs = System.currentTimeMillis() + remainingMs;
        }
        persist();
    }

    public synchronized void resetTimer() {
        long elapsed = totalDurationMs - getRemainingMs();
        if (phase == Phase.FOCUS && elapsed > 0L) {
            closeActiveDistraction(elapsed);
            saveCurrentAsLastStats(elapsed);
        }
        running = false;
        endTimeMs = 0L;
        remainingMs = totalDurationMs;
        activeDistractedStartMs = -1L;
        lastFocusStatus = null;
        currentSegments.clear();
        persist();
    }

    public synchronized boolean isFocusRunning() {
        advanceCompletedPhase();
        return running && phase == Phase.FOCUS;
    }

    public synchronized void recordFocusStatus(String status) {
        if (!isFocusRunning() || status == null) {
            return;
        }

        boolean distracted = "distracted".equals(status) || "not_detected".equals(status);
        long elapsed = totalDurationMs - getRemainingMs();
        boolean wasDistracted = activeDistractedStartMs >= 0L;
        if (distracted && !wasDistracted) {
            activeDistractedStartMs = elapsed;
        } else if (!distracted && wasDistracted) {
            closeActiveDistraction(elapsed);
        }
        lastFocusStatus = status;
        persist();
    }

    private long getRemainingMs() {
        if (!running) {
            return Math.max(0L, remainingMs);
        }
        return Math.max(0L, endTimeMs - System.currentTimeMillis());
    }

    private void advanceCompletedPhase() {
        if (!running || getRemainingMs() > 0L) {
            return;
        }

        running = false;
        endTimeMs = 0L;
        if (phase == Phase.FOCUS) {
            closeActiveDistraction(totalDurationMs);
            saveCurrentAsLastStats(totalDurationMs);
            phase = Phase.BREAK;
            totalDurationMs = BREAK_DURATION_MS;
        } else {
            phase = Phase.FOCUS;
            totalDurationMs = FOCUS_DURATION_MS;
        }
        remainingMs = totalDurationMs;
        currentSegments.clear();
        activeDistractedStartMs = -1L;
        lastFocusStatus = null;
        persist();
    }

    private void closeActiveDistraction(long elapsedMs) {
        if (activeDistractedStartMs < 0L) {
            return;
        }
        long end = Math.max(activeDistractedStartMs, Math.min(totalDurationMs, elapsedMs));
        if (end > activeDistractedStartMs) {
            currentSegments.add(new Segment(activeDistractedStartMs, end));
        }
        activeDistractedStartMs = -1L;
    }

    private List<Segment> snapshotCurrentSegments(long elapsedMs) {
        List<Segment> segments = copySegments(currentSegments);
        if (activeDistractedStartMs >= 0L && elapsedMs > activeDistractedStartMs) {
            segments.add(new Segment(activeDistractedStartMs, elapsedMs));
        }
        return segments;
    }

    private void saveCurrentAsLastStats(long elapsedMs) {
        lastSegments.clear();
        lastSegments.addAll(copySegments(currentSegments));
        lastStatsDurationMs = totalDurationMs;
        lastStatsElapsedMs = elapsedMs;
    }

    private static long calculateDistractedMs(List<Segment> segments, long elapsedMs) {
        long total = 0L;
        for (Segment segment : segments) {
            long start = Math.max(0L, Math.min(elapsedMs, segment.startMs));
            long end = Math.max(start, Math.min(elapsedMs, segment.endMs));
            total += end - start;
        }
        return Math.min(elapsedMs, total);
    }

    private static float[] calculateBins(List<Segment> segments, long durationMs, long elapsedMs) {
        float[] bins = new float[CHART_BIN_COUNT];
        if (durationMs <= 0L || elapsedMs <= 0L) {
            for (int index = 0; index < bins.length; index++) {
                bins[index] = -1f;
            }
            return bins;
        }

        double binSize = durationMs / (double) CHART_BIN_COUNT;
        for (int index = 0; index < bins.length; index++) {
            long binStart = Math.round(index * binSize);
            long binEnd = Math.min(elapsedMs, Math.round((index + 1) * binSize));
            if (binStart >= elapsedMs || binEnd <= binStart) {
                bins[index] = -1f;
                continue;
            }

            long distracted = 0L;
            for (Segment segment : segments) {
                distracted += Math.max(
                        0L,
                        Math.min(binEnd, segment.endMs) - Math.max(binStart, segment.startMs)
                );
            }
            bins[index] = Math.max(0f, Math.min(1f, 1f - distracted / (float) (binEnd - binStart)));
        }
        return bins;
    }

    private void restore() {
        phase = Phase.valueOf(preferences.getString("phase", Phase.FOCUS.name()));
        running = preferences.getBoolean("running", false);
        totalDurationMs = preferences.getLong("totalDurationMs", FOCUS_DURATION_MS);
        remainingMs = preferences.getLong("remainingMs", totalDurationMs);
        endTimeMs = preferences.getLong("endTimeMs", 0L);
        activeDistractedStartMs = preferences.getLong("activeDistractedStartMs", -1L);
        lastFocusStatus = preferences.getString("lastFocusStatus", null);
        lastStatsDurationMs = preferences.getLong("lastStatsDurationMs", 0L);
        lastStatsElapsedMs = preferences.getLong("lastStatsElapsedMs", 0L);
        readSegments(preferences.getString("currentSegments", "[]"), currentSegments);
        readSegments(preferences.getString("lastSegments", "[]"), lastSegments);
        advanceCompletedPhase();
    }

    private void persist() {
        preferences.edit()
                .putString("phase", phase.name())
                .putBoolean("running", running)
                .putLong("totalDurationMs", totalDurationMs)
                .putLong("remainingMs", running ? getRemainingMs() : remainingMs)
                .putLong("endTimeMs", endTimeMs)
                .putLong("activeDistractedStartMs", activeDistractedStartMs)
                .putString("lastFocusStatus", lastFocusStatus)
                .putLong("lastStatsDurationMs", lastStatsDurationMs)
                .putLong("lastStatsElapsedMs", lastStatsElapsedMs)
                .putString("currentSegments", writeSegments(currentSegments))
                .putString("lastSegments", writeSegments(lastSegments))
                .apply();
    }

    private static String writeSegments(List<Segment> segments) {
        JSONArray array = new JSONArray();
        for (Segment segment : segments) {
            JSONObject item = new JSONObject();
            try {
                item.put("start", segment.startMs);
                item.put("end", segment.endMs);
                array.put(item);
            } catch (JSONException ignored) {
                // Values are primitive longs and cannot fail in normal operation.
            }
        }
        return array.toString();
    }

    private static void readSegments(String json, List<Segment> target) {
        target.clear();
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                target.add(new Segment(item.getLong("start"), item.getLong("end")));
            }
        } catch (JSONException ignored) {
            target.clear();
        }
    }

    private static List<Segment> copySegments(List<Segment> source) {
        if (source.isEmpty()) {
            return new ArrayList<>();
        }
        List<Segment> copy = new ArrayList<>(source.size());
        for (Segment segment : source) {
            copy.add(new Segment(segment.startMs, segment.endMs));
        }
        return copy;
    }

    public enum Phase {
        FOCUS,
        BREAK
    }

    public static final class Segment {
        public final long startMs;
        public final long endMs;

        private Segment(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    public static final class Snapshot {
        public final Phase phase;
        public final boolean running;
        public final long totalDurationMs;
        public final long remainingMs;
        public final long elapsedMs;
        public final List<Segment> distractedSegments;

        private Snapshot(
                Phase phase,
                boolean running,
                long totalDurationMs,
                long remainingMs,
                long elapsedMs,
                List<Segment> distractedSegments
        ) {
            this.phase = phase;
            this.running = running;
            this.totalDurationMs = totalDurationMs;
            this.remainingMs = remainingMs;
            this.elapsedMs = elapsedMs;
            this.distractedSegments = Collections.unmodifiableList(distractedSegments);
        }
    }

    public static final class StatsSnapshot {
        public final int focusRate;
        public final long focusedMs;
        public final long distractedMs;
        public final float[] focusBins;

        private StatsSnapshot(
                int focusRate,
                long focusedMs,
                long distractedMs,
                float[] focusBins
        ) {
            this.focusRate = focusRate;
            this.focusedMs = focusedMs;
            this.distractedMs = distractedMs;
            this.focusBins = focusBins;
        }
    }
}
