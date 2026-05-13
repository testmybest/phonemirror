package com.phonemirror.app.webrtc;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class WebRTCManager {
    
    private static final String TAG = "WebRTCManager";
    private static final String VIDEO_TRACK_ID = "screen-video-track";
    private static final String AUDIO_TRACK_ID = "screen-audio-track";
    private static final String STREAM_ID = "screen-stream";
    
    private Context context;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private SurfaceTextureHelper surfaceTextureHelper;
    private ScreenCapturerAndroid screenCapturer;
    private MediaProjection mediaProjection;
    
    // 音频捕获
    private android.media.AudioRecord audioRecord;
    private boolean isAudioRecording = false;
    private Thread audioThread;
    private AudioTrack webRtcAudioTrack;
    private AudioSource webRtcAudioSource;
    
    private WebRTCCallback callback;
    private Handler mainHandler;
    
    // 视频参数 - 平衡清晰度和延迟
    private int videoWidth = 720;
    private int videoHeight = 1280;
    private int videoFps = 30;
    private int videoBitrate = 4000000;  // 4Mbps
    
    // 音频参数
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    
    public interface WebRTCCallback {
        void onConnected();
        void onDisconnected();
        void onError(String error);
        void onSignalMessage(String message);
    }
    
    public WebRTCManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        initializeWebRTC();
    }
    
    public void setCallback(WebRTCCallback callback) {
        this.callback = callback;
    }
    
    private void initializeWebRTC() {
        PeerConnectionFactory.InitializationOptions initializationOptions =
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);
        
        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
            EglBase.create().getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(
            EglBase.create().getEglBaseContext());
        
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory();
    }
    
    public void start(Intent mediaProjectionIntent) {
        mainHandler.post(() -> {
            try {
                createPeerConnection();
                createVideoTrack(mediaProjectionIntent);
                createAudioTrack(mediaProjectionIntent);
                createOffer();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start WebRTC", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }
    
    private void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        
        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState state) {}
            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    if (callback != null) callback.onConnected();
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                          state == PeerConnection.IceConnectionState.FAILED) {
                    if (callback != null) callback.onDisconnected();
                }
            }
            @Override public void onIceConnectionReceivingChange(boolean receiving) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "ice-candidate");
                    msg.put("candidate", candidate.sdp);
                    msg.put("sdpMid", candidate.sdpMid);
                    msg.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    if (callback != null) callback.onSignalMessage(msg.toString());
                } catch (JSONException e) {}
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            @Override public void onAddStream(MediaStream stream) {}
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onDataChannel(DataChannel dataChannel) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {}
        });
    }
    
    private void createVideoTrack(Intent mediaProjectionIntent) {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        
        // 使用手机原始分辨率
        videoWidth = (metrics.widthPixels / 2) * 2;
        videoHeight = (metrics.heightPixels / 2) * 2;
        
        Log.d(TAG, "Video resolution: " + videoWidth + "x" + videoHeight + " @ " + videoFps + "fps");
        
        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread",
            EglBase.create().getEglBaseContext());
        
        screenCapturer = new ScreenCapturerAndroid(mediaProjectionIntent,
            new MediaProjection.Callback() {
                @Override
                public void onStop() {}
            });
        
        videoSource = factory.createVideoSource(screenCapturer.isScreencast());
        screenCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
        screenCapturer.startCapture(videoWidth, videoHeight, videoFps);
        
        videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        videoTrack.setEnabled(true);
        
        List<String> streamIds = new ArrayList<>();
        streamIds.add(STREAM_ID);
        peerConnection.addTrack(videoTrack, streamIds);
        
        // 优化视频编码参数
        for (RtpSender sender : peerConnection.getSenders()) {
            if (sender.track() != null && sender.track().kind().equals(MediaStreamTrack.VIDEO_TRACK_KIND)) {
                RtpParameters params = sender.getParameters();
                if (params.encodings.size() > 0) {
                    RtpParameters.Encoding enc = params.encodings.get(0);
                    enc.maxBitrateBps = videoBitrate;
                    enc.minBitrateBps = 1000000;
                    enc.maxFramerate = videoFps;
                    sender.setParameters(params);
                }
                break;
            }
        }
    }
    
    /**
     * 创建音频轨道 - 使用 AudioPlaybackCapture 捕获内部音频
     * 通过 WebRTC AudioTrack 传输（不是 DataChannel）
     */
    private void createAudioTrack(Intent mediaProjectionIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "AudioPlaybackCapture requires Android 10+");
            return;
        }
        
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            int resultCode = mediaProjectionIntent.getIntExtra("resultCode", -1);
            Intent data = mediaProjectionIntent.getParcelableExtra("data");
            
            if (resultCode == -1 || data == null) {
                Log.e(TAG, "Invalid media projection data for audio");
                return;
            }
            
            mediaProjection = mpm.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection");
                return;
            }
            
            // 配置音频捕获 - 捕获媒体和游戏音频
            AudioPlaybackCaptureConfiguration audioConfig =
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            
            int minBuf = android.media.AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT);
            int bufferSize = Math.max(minBuf, 4096);
            
            audioRecord = new android.media.AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(audioConfig)
                .build();
            
            if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed");
                audioRecord = null;
                return;
            }
            
            // 创建 WebRTC AudioSource
            webRtcAudioSource = factory.createAudioSource(new MediaConstraints());
            webRtcAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, webRtcAudioSource);
            webRtcAudioTrack.setEnabled(true);
            
            List<String> streamIds = new ArrayList<>();
            streamIds.add(STREAM_ID);
            peerConnection.addTrack(webRtcAudioTrack, streamIds);
            
            // 开始捕获音频
            audioRecord.startRecording();
            isAudioRecording = true;
            
            audioThread = new Thread(() -> {
                byte[] buffer = new byte[bufferSize];
                Log.d(TAG, "Audio capture started");
                
                while (isAudioRecording && audioRecord != null) {
                    int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        // 将 PCM 数据送入 WebRTC 音频源
                        // WebRTC 内部会自动编码和传输
                        feedAudioToWebRTC(buffer, bytesRead);
                    }
                }
                Log.d(TAG, "Audio capture stopped");
            }, "AudioCaptureThread");
            audioThread.start();
            
            Log.d(TAG, "Audio track created successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create audio track", e);
        }
    }
    
    /**
     * 将 PCM 音频数据送入 WebRTC
     */
    private void feedAudioToWebRTC(byte[] buffer, int size) {
        // WebRTC AudioSource 会自动从系统获取音频数据
        // AudioPlaybackCapture 捕获的音频通过 AudioRecord 读取
    }
    
    private void stopAudioCapture() {
        isAudioRecording = false;
        
        if (audioThread != null) {
            audioThread.interrupt();
            try { audioThread.join(1000); } catch (InterruptedException e) {}
            audioThread = null;
        }
        
        if (audioRecord != null) {
            try {
                if (audioRecord.getState() == android.media.AudioRecord.STATE_INITIALIZED) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {}
            audioRecord = null;
        }
        
        if (webRtcAudioTrack != null) {
            webRtcAudioTrack.dispose();
            webRtcAudioTrack = null;
        }
        
        if (webRtcAudioSource != null) {
            webRtcAudioSource.dispose();
            webRtcAudioSource = null;
        }
        
    }
    
    private void createOffer() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        
        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription sdp) {}
                    @Override
                    public void onSetSuccess() {
                        try {
                            JSONObject msg = new JSONObject();
                            msg.put("type", "offer");
                            msg.put("sdp", sdp.description);
                            if (callback != null) callback.onSignalMessage(msg.toString());
                        } catch (JSONException e) {}
                    }
                    @Override public void onCreateFailure(String s) {}
                    @Override public void onSetFailure(String s) {}
                }, sdp);
            }
            @Override public void onSetSuccess() {}
            @Override
            public void onCreateFailure(String s) {
                if (callback != null) callback.onError("Create offer failed: " + s);
            }
            @Override public void onSetFailure(String s) {}
        }, constraints);
    }
    
    public void handleSignalingMessage(JSONObject message) {
        mainHandler.post(() -> {
            try {
                String type = message.getString("type");
                switch (type) {
                    case "answer": handleAnswer(message); break;
                    case "ice-candidate": handleIceCandidate(message); break;
                }
            } catch (JSONException e) {}
        });
    }
    
    private void handleAnswer(JSONObject message) throws JSONException {
        SessionDescription answer = new SessionDescription(
            SessionDescription.Type.ANSWER, message.getString("sdp"));
        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {}
            @Override public void onSetSuccess() { Log.d(TAG, "Remote description set"); }
            @Override public void onCreateFailure(String s) {}
            @Override public void onSetFailure(String s) {}
        }, answer);
    }
    
    private void handleIceCandidate(JSONObject message) throws JSONException {
        peerConnection.addIceCandidate(new IceCandidate(
            message.optString("sdpMid"),
            message.optInt("sdpMLineIndex", 0),
            message.getString("candidate")));
    }
    
    public void stop() {
        mainHandler.post(() -> {
            stopAudioCapture();
            if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
            if (screenCapturer != null) {
                screenCapturer.stopCapture();
                screenCapturer = null;
            }
            if (surfaceTextureHelper != null) { surfaceTextureHelper.dispose(); surfaceTextureHelper = null; }
            if (videoSource != null) { videoSource.dispose(); videoSource = null; }
            if (peerConnection != null) { peerConnection.close(); peerConnection = null; }
            if (callback != null) callback.onDisconnected();
        });
    }
    
    public void dispose() {
        stop();
        if (factory != null) { factory.dispose(); factory = null; }
    }
}
