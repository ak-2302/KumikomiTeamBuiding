package com.example.kumikomiteambuiding;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String WIFI_INTERFACE_PREFIX = "wlan";
    private static final int QR_CODE_SIZE = 768;
    private static final long UI_UPDATE_INTERVAL_MS = 250L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdate = new Runnable() {
        @Override
        public void run() {
            updateDashboard();
            uiHandler.postDelayed(this, UI_UPDATE_INTERVAL_MS);
        }
    };

    private HttpAnalyzer httpAnalyzer;
    private FocusSessionManager sessionManager;
    private DashboardPagerAdapter pagerAdapter;
    private String connectionUrl;
    private Bitmap connectionQrBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        sessionManager = FocusSessionManager.get(this);
        ViewPager2 dashboardPager = findViewById(R.id.dashboardPager);
        pagerAdapter = new DashboardPagerAdapter();
        dashboardPager.setAdapter(pagerAdapter);
        dashboardPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        dashboardPager.setOffscreenPageLimit(1);

        updateConnectionInfo(HttpAnalyzer.DEFAULT_PORT);
        httpAnalyzer = new HttpAnalyzer(getApplicationContext(), new HttpAnalyzer.Listener() {
            @Override
            public void onDataReceived(Object data) {
                updateDashboard();
            }

            @Override
            public void onServerStarted(int port) {
                Log.i(TAG, "HTTP server started on port " + port);
                updateConnectionInfo(port);
            }

            @Override
            public void onQrCodeScanned(String deviceName) {
                Intent intent = new Intent(MainActivity.this, ScanCompleteActivity.class)
                        .putExtra(ScanCompleteActivity.EXTRA_DEVICE_NAME, deviceName)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            }

            @Override
            public void onServerError(Exception error) {
                Log.e(TAG, "HTTP server error", error);
            }
        });
        httpAnalyzer.start();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (httpAnalyzer != null && !httpAnalyzer.isRunning()) {
            httpAnalyzer.start();
        }
        uiHandler.removeCallbacks(uiUpdate);
        uiHandler.post(uiUpdate);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int port = httpAnalyzer != null ? httpAnalyzer.getPort() : HttpAnalyzer.DEFAULT_PORT;
        updateConnectionInfo(port);
        updateDashboard();
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiHandler.removeCallbacks(uiUpdate);
    }

    private void updateDashboard() {
        if (pagerAdapter == null) {
            return;
        }
        pagerAdapter.update(
                sessionManager.getSnapshot(),
                sessionManager.getStatsSnapshot()
        );
    }

    private void updateConnectionInfo(int port) {
        String ipAddress = getLocalIpAddress();
        if (ipAddress == null) {
            connectionUrl = null;
            connectionQrBitmap = null;
            return;
        }

        String newConnectionUrl = "http://" + ipAddress + ":" + port;
        if (newConnectionUrl.equals(connectionUrl)) {
            return;
        }
        connectionUrl = newConnectionUrl;
        connectionQrBitmap = createQrBitmap(newConnectionUrl);
    }

    private Bitmap createQrBitmap(String value) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.MARGIN, 4);

        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    value,
                    BarcodeFormat.QR_CODE,
                    QR_CODE_SIZE,
                    QR_CODE_SIZE,
                    hints
            );
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[rowOffset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (WriterException error) {
            Log.e(TAG, "Failed to generate connection QR code", error);
            return null;
        }
    }

    private void showConnectionSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View content = LayoutInflater.from(this).inflate(R.layout.sheet_connection, null, false);
        dialog.setContentView(content);

        TextView addressText = content.findViewById(R.id.ipAddressText);
        ImageView qrCode = content.findViewById(R.id.connectionQrCode);
        TextView mediaStatus = content.findViewById(R.id.mediaAccessStatus);
        if (connectionUrl == null) {
            addressText.setText(R.string.ip_address_unavailable);
            qrCode.setVisibility(View.GONE);
        } else {
            addressText.setText(connectionUrl);
            qrCode.setImageBitmap(connectionQrBitmap);
            qrCode.setVisibility(connectionQrBitmap != null ? View.VISIBLE : View.GONE);
        }
        mediaStatus.setText(myModule.hasMediaControlAccess(this)
                ? R.string.media_access_enabled
                : R.string.media_access_required);
        content.findViewById(R.id.mediaAccessButton).setOnClickListener(view -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            }
        });
        dialog.show();
    }

    private String getLocalIpAddress() {
        String fallbackIpAddress = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    String hostAddress = address.getHostAddress();
                    if (networkInterface.getName().startsWith(WIFI_INTERFACE_PREFIX)) {
                        return hostAddress;
                    }
                    if (fallbackIpAddress == null) {
                        fallbackIpAddress = hostAddress;
                    }
                }
            }
        } catch (SocketException error) {
            Log.w(TAG, "Failed to get local IP address", error);
        }
        return fallbackIpAddress;
    }

    private static String formatDuration(long durationMs, boolean roundUp) {
        long seconds = roundUp
                ? Math.max(0L, (durationMs + 999L) / 1000L)
                : Math.max(0L, durationMs / 1000L);
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private final class DashboardPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TIMER_PAGE = 0;
        private static final int STATS_PAGE = 1;

        private TimerPageHolder timerHolder;
        private StatsPageHolder statsHolder;
        private FocusSessionManager.Snapshot snapshot;
        private FocusSessionManager.StatsSnapshot statsSnapshot;

        @Override
        public int getItemCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent,
                int viewType
        ) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TIMER_PAGE) {
                timerHolder = new TimerPageHolder(
                        inflater.inflate(R.layout.page_timer, parent, false)
                );
                return timerHolder;
            }
            statsHolder = new StatsPageHolder(
                    inflater.inflate(R.layout.page_stats, parent, false)
            );
            return statsHolder;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof TimerPageHolder && snapshot != null) {
                ((TimerPageHolder) holder).bind(snapshot);
            } else if (holder instanceof StatsPageHolder && statsSnapshot != null) {
                ((StatsPageHolder) holder).bind(statsSnapshot);
            }
        }

        private void update(
                FocusSessionManager.Snapshot snapshot,
                FocusSessionManager.StatsSnapshot statsSnapshot
        ) {
            this.snapshot = snapshot;
            this.statsSnapshot = statsSnapshot;
            if (timerHolder != null) {
                timerHolder.bind(snapshot);
            }
            if (statsHolder != null) {
                statsHolder.bind(statsSnapshot);
            }
        }
    }

    private final class TimerPageHolder extends RecyclerView.ViewHolder {
        private final FocusTimerView timerView;
        private final TextView phaseText;
        private final TextView timeText;
        private final TextView statusText;
        private final MaterialButton toggleButton;

        private TimerPageHolder(@NonNull View itemView) {
            super(itemView);
            timerView = itemView.findViewById(R.id.focusTimerView);
            phaseText = itemView.findViewById(R.id.phaseText);
            timeText = itemView.findViewById(R.id.timeText);
            statusText = itemView.findViewById(R.id.timerStatusText);
            toggleButton = itemView.findViewById(R.id.toggleTimerButton);
            toggleButton.setOnClickListener(view -> {
                sessionManager.toggleTimer();
                updateDashboard();
            });
            itemView.findViewById(R.id.resetButton).setOnClickListener(view -> {
                sessionManager.resetTimer();
                updateDashboard();
            });
            itemView.findViewById(R.id.connectionButton)
                    .setOnClickListener(view -> showConnectionSheet());
        }

        private void bind(FocusSessionManager.Snapshot snapshot) {
            timerView.setSession(snapshot);
            phaseText.setText(snapshot.phase == FocusSessionManager.Phase.FOCUS
                    ? R.string.focus_phase
                    : R.string.break_phase);
            timeText.setText(formatDuration(snapshot.remainingMs, true));
            if (snapshot.running) {
                statusText.setText(R.string.timer_running);
                toggleButton.setText(R.string.timer_pause);
                toggleButton.setIconResource(R.drawable.ic_pause);
            } else {
                statusText.setText(snapshot.elapsedMs > 0L
                        ? R.string.timer_paused
                        : R.string.timer_ready);
                toggleButton.setText(R.string.timer_start);
                toggleButton.setIconResource(R.drawable.ic_play);
            }
        }
    }

    private final class StatsPageHolder extends RecyclerView.ViewHolder {
        private final TextView rateText;
        private final TextView focusedText;
        private final TextView distractedText;
        private final SessionChartView chartView;

        private StatsPageHolder(@NonNull View itemView) {
            super(itemView);
            rateText = itemView.findViewById(R.id.focusRateText);
            focusedText = itemView.findViewById(R.id.focusedTimeText);
            distractedText = itemView.findViewById(R.id.distractedTimeText);
            chartView = itemView.findViewById(R.id.sessionChart);
        }

        private void bind(FocusSessionManager.StatsSnapshot stats) {
            rateText.setText(String.format(Locale.getDefault(), "%d%%", stats.focusRate));
            focusedText.setText(formatDuration(stats.focusedMs, false));
            distractedText.setText(formatDuration(stats.distractedMs, false));
            chartView.setBins(stats.focusBins);
        }
    }
}
