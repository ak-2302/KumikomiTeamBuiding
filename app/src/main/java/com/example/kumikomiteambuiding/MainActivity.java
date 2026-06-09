package com.example.kumikomiteambuiding;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private HttpAnalyzer httpAnalyzer;

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

        httpAnalyzer = new HttpAnalyzer(this, new HttpAnalyzer.Listener() {
            @Override
            public void onDataReceived(Object data) {
                Log.i(TAG, "Received data from Raspberry Pi: " + data);
            }

            @Override
            public void onServerStarted(int port) {
                Log.i(TAG, "HTTP server started on port " + port);
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
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onDestroy() {
        if (httpAnalyzer != null) {
            httpAnalyzer.stop();
        }
        super.onDestroy();
    }
}
