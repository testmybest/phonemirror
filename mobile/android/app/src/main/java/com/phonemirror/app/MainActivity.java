package com.phonemirror.app;

import android.Manifest;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.phonemirror.app.databinding.ActivityMainBinding;
import com.phonemirror.app.service.ScreenCaptureService;
import com.phonemirror.app.webrtc.WebRTCManager;
import com.permissionx.guolindev.PermissionX;
import com.permissionx.guolindev.callback.RequestCallback;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    
    private ActivityMainBinding binding;
    private WebSocketClient webSocketClient;
    private WebRTCManager webRTCManager;
    private String signalingUrl;
    private String roomCode;
    
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1001;
    private static final String[] PERMISSIONS = {
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA
    };
    
    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(
        new ScanContract(),
        result -> {
            if (result.getContents() != null) {
                parseQRCode(result.getContents());
            }
        }
    );
    
    private final ActivityResultLauncher<Intent> screenCaptureLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                startScreenCapture(result.getData());
            } else {
                Toast.makeText(this, "需要屏幕录制权限才能投屏", Toast.LENGTH_SHORT).show();
            }
        }
    );
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        checkPermissions();
        initViews();
        initWebRTC();
    }
    
    private void checkPermissions() {
        PermissionX.init(this)
            .permissions(PERMISSIONS)
            .request(new RequestCallback() {
                @Override
                public void onResult(boolean allGranted, List<String> grantedList, List<String> deniedList) {
                    if (!allGranted) {
                        StringBuilder msg = new StringBuilder("部分权限被拒绝：");
                        for (String denied : deniedList) {
                            msg.append(denied).append(" ");
                        }
                        Toast.makeText(MainActivity.this, msg.toString(), Toast.LENGTH_LONG).show();
                    }
                }
            });
    }
    
    private void initViews() {
        binding.btnScanQr.setOnClickListener(v -> startQRScan());
        binding.btnConnect.setOnClickListener(v -> connectWithCode());
        binding.btnDisconnect.setOnClickListener(v -> disconnect());
        
        binding.btnDisconnect.setEnabled(false);
    }
    
    private void initWebRTC() {
        webRTCManager = new WebRTCManager(this);
        webRTCManager.setCallback(new WebRTCManager.WebRTCCallback() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    binding.tvStatus.setText("状态: 已连接");
                    binding.btnDisconnect.setEnabled(true);
                    binding.btnConnect.setEnabled(false);
                    binding.btnScanQr.setEnabled(false);
                });
            }
            
            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    binding.tvStatus.setText("状态: 已断开");
                    binding.btnDisconnect.setEnabled(false);
                    binding.btnConnect.setEnabled(true);
                    binding.btnScanQr.setEnabled(true);
                    stopScreenCapture();
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "错误: " + error, Toast.LENGTH_SHORT).show();
                    binding.tvStatus.setText("状态: 错误");
                });
            }
            
            @Override
            public void onSignalMessage(String message) {
                if (webSocketClient != null && webSocketClient.isOpen()) {
                    webSocketClient.send(message);
                }
            }
        });
    }
    
    private void startQRScan() {
        try {
            ScanOptions options = new ScanOptions();
            options.setPrompt("扫描电脑上的二维码");
            options.setBeepEnabled(true);
            options.setOrientationLocked(false);
            options.setCaptureActivity(CaptureActivityPortrait.class);
            barcodeLauncher.launch(options);
        } catch (Exception e) {
            Toast.makeText(this, "启动扫码失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void parseQRCode(String qrContent) {
        try {
            JSONObject json = new JSONObject(qrContent);
            signalingUrl = json.getString("signalingUrl");
            roomCode = json.getString("roomCode");
            
            binding.etRoomCode.setText(roomCode);
            connectToSignalingServer();
        } catch (JSONException e) {
            Toast.makeText(this, "无效的二维码", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void connectWithCode() {
        roomCode = binding.etRoomCode.getText().toString().trim();
        if (roomCode.isEmpty()) {
            Toast.makeText(this, "请输入配对码", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String serverIp = getServerIpFromSettings();
        if (serverIp.isEmpty()) {
            Toast.makeText(this, "请输入电脑IP地址", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 如果用户输入已经包含端口，直接使用；否则添加默认端口8080
        if (serverIp.contains(":")) {
            signalingUrl = "ws://" + serverIp;
        } else {
            signalingUrl = "ws://" + serverIp + ":8080";
        }
        connectToSignalingServer();
    }
    
    private String getServerIpFromSettings() {
        return binding.etServerIp.getText().toString().trim();
    }
    
    private void connectToSignalingServer() {
        try {
            URI uri = new URI(signalingUrl + "/mobile");
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    runOnUiThread(() -> {
                        binding.tvStatus.setText("状态: 信令服务器已连接");
                        try {
                            JSONObject message = new JSONObject();
                            message.put("type", "join-room");
                            message.put("roomCode", roomCode);
                            send(message.toString());
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    });
                }
                
                @Override
                public void onMessage(String message) {
                    handleSignalingMessage(message);
                }
                
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    runOnUiThread(() -> binding.tvStatus.setText("状态: 信令服务器断开"));
                }
                
                @Override
                public void onError(Exception ex) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "连接错误: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                        binding.tvStatus.setText("状态: 连接错误");
                    });
                }
            };
            webSocketClient.connect();
        } catch (URISyntaxException e) {
            Toast.makeText(this, "无效的服务器地址", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void handleSignalingMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.getString("type");
            
            switch (type) {
                case "room-joined":
                    if (json.getBoolean("success")) {
                        runOnUiThread(() -> {
                            binding.tvStatus.setText("状态: 等待屏幕录制权限...");
                            requestScreenCapture();
                        });
                    } else {
                        runOnUiThread(() -> Toast.makeText(this, 
                            "加入房间失败: " + json.optString("error", "未知错误"), 
                            Toast.LENGTH_SHORT).show());
                    }
                    break;
                    
                case "answer":
                case "ice-candidate":
                    webRTCManager.handleSignalingMessage(json);
                    break;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
    
    private void requestScreenCapture() {
        MediaProjectionManager mediaProjectionManager = 
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent = mediaProjectionManager.createScreenCaptureIntent();
        screenCaptureLauncher.launch(intent);
    }
    
    private void startScreenCapture(Intent data) {
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        serviceIntent.putExtra("resultCode", RESULT_OK);
        serviceIntent.putExtra("data", data);
        startForegroundService(serviceIntent);
        
        webRTCManager.start(data);
        
        binding.tvStatus.setText("状态: 正在连接...");
    }
    
    private void stopScreenCapture() {
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        stopService(serviceIntent);
    }
    
    private void disconnect() {
        if (webRTCManager != null) {
            webRTCManager.stop();
        }
        if (webSocketClient != null) {
            webSocketClient.close();
        }
        stopScreenCapture();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}
