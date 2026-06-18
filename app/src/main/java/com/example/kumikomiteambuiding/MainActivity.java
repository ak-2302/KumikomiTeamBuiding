package com.example.kumikomiteambuiding;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String WIFI_INTERFACE_PREFIX = "wlan";
    private static final int QR_CODE_SIZE = 768;

    private HttpAnalyzer httpAnalyzer;
    private TextView ipAddressText;
    private ImageView connectionQrCode;
    private String displayedConnectionUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ipAddressText = findViewById(R.id.ipAddressText);
        connectionQrCode = findViewById(R.id.connectionQrCode);
        updateIpAddress(HttpAnalyzer.DEFAULT_PORT);

        httpAnalyzer = new HttpAnalyzer(getApplicationContext(), new HttpAnalyzer.Listener() {
            @Override
            public void onDataReceived(Object data) {
                Log.i(TAG, "Received data from Raspberry Pi: " + data);
            }

            @Override
            public void onServerStarted(int port) {
                Log.i(TAG, "HTTP server started on port " + port);
                updateIpAddress(port);
            }

            @Override
            public void onServerError(Exception error) {
                Log.e(TAG, "HTTP server error", error);
            }
        });
        httpAnalyzer.start();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (httpAnalyzer != null && !httpAnalyzer.isRunning()) {
            httpAnalyzer.start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        int port = httpAnalyzer != null ? httpAnalyzer.getPort() : HttpAnalyzer.DEFAULT_PORT;
        updateIpAddress(port);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Keep the server running while the app is backgrounded.
    }

    private void updateIpAddress(int port) {
        String ipAddress = getLocalIpAddress();
        if (ipAddress == null) {
            ipAddressText.setText(R.string.ip_address_unavailable);
            connectionQrCode.setImageDrawable(null);
            connectionQrCode.setVisibility(View.INVISIBLE);
            displayedConnectionUrl = null;
            return;
        }

        String connectionUrl = "http://" + ipAddress + ":" + port;
        ipAddressText.setText(getString(R.string.ip_address_format, ipAddress, port));
        showConnectionQrCode(connectionUrl);
    }

    private void showConnectionQrCode(String connectionUrl) {
        if (connectionUrl.equals(displayedConnectionUrl)) {
            return;
        }

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.MARGIN, 4);

        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    connectionUrl,
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
            connectionQrCode.setImageBitmap(bitmap);
            connectionQrCode.setVisibility(View.VISIBLE);
            displayedConnectionUrl = connectionUrl;
        } catch (WriterException e) {
            Log.e(TAG, "Failed to generate connection QR code", e);
            connectionQrCode.setImageDrawable(null);
            connectionQrCode.setVisibility(View.INVISIBLE);
            displayedConnectionUrl = null;
        }
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
        } catch (SocketException e) {
            Log.w(TAG, "Failed to get local IP address", e);
        }

        return fallbackIpAddress;
    }

}
