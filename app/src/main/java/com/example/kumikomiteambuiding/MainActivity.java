package com.example.kumikomiteambuiding;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String WIFI_INTERFACE_PREFIX = "wlan";

    private HttpAnalyzer httpAnalyzer;
    private TextView ipAddressText;

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
            return;
        }

        ipAddressText.setText(getString(R.string.ip_address_format, ipAddress, port));
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
