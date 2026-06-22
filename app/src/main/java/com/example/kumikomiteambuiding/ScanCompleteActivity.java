package com.example.kumikomiteambuiding;

import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public class ScanCompleteActivity extends AppCompatActivity {
    public static final String EXTRA_DEVICE_NAME = "deviceName";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true);
        setContentView(R.layout.activity_scan_complete);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scanComplete), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        String deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        if (deviceName == null || deviceName.trim().isEmpty()) {
            deviceName = "Raspberry Pi";
        }

        TextView message = findViewById(R.id.scanCompleteMessage);
        message.setText(getString(R.string.scan_complete_message, deviceName));
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
    }
}
