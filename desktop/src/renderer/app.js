const { ipcRenderer } = require('electron');
const QRCode = require('qrcode');

// Elements
const connectionPanel = document.getElementById('connectionPanel');
const videoContainer = document.getElementById('videoContainer');
const qrCanvas = document.getElementById('qrCanvas');
const roomCodeEl = document.getElementById('roomCode');
const ipListEl = document.getElementById('ipList');
const remoteVideo = document.getElementById('remoteVideo');
const statusBar = document.getElementById('statusBar');
const controls = document.getElementById('controls');
const loading = document.getElementById('loading');
const zoomInfo = document.getElementById('zoomInfo');
const shortcutsHint = document.getElementById('shortcutsHint');

let currentIp = '';
let currentPort = 8080;
let currentRoomCode = '';
let peerConnection = null;
let remoteStream = null;

// 缩放控制
let currentZoom = 1.0;
const ZOOM_STEP = 0.1;
const ZOOM_MIN = 0.3;
const ZOOM_MAX = 5.0;

// 音频播放
let audioContext = null;
let audioSource = null;
let audioProcessor = null;
let audioGainNode = null;
let nextPlayTime = 0;
const AUDIO_SAMPLE_RATE = 48000;

// Load connection info on start
async function init() {
    const info = await ipcRenderer.invoke('get-connection-info');
    if (info) displayNetworkInfo(info);
}

ipcRenderer.on('server-ready', (event, info) => displayNetworkInfo(info));

function displayNetworkInfo(info) {
    currentRoomCode = info.roomCode;
    currentPort = info.port;
    roomCodeEl.textContent = info.roomCode;

    ipListEl.innerHTML = '';
    if (info.allIps && info.allIps.length > 0) {
        info.allIps.forEach((ip) => {
            const item = document.createElement('div');
            item.className = 'ip-item' + (ip.address === info.bestIp ? ' active' : '');
            item.innerHTML = `
                <div>
                    <div class="ip-name">${ip.name}</div>
                    <div class="ip-addr">${ip.address}:${currentPort}</div>
                </div>
                ${ip.address === info.bestIp ? '<span class="ip-badge">Recommended</span>' : ''}
                ${ip.isVirtual ? '<span class="ip-badge" style="background:#ffa500">Virtual</span>' : ''}
            `;
            item.onclick = () => {
                currentIp = ip.address;
                document.querySelectorAll('.ip-item').forEach(i => i.classList.remove('active'));
                item.classList.add('active');
                generateQR();
            };
            ipListEl.appendChild(item);
        });
        currentIp = info.bestIp;
        generateQR();
    }
}

function generateQR() {
    QRCode.toCanvas(qrCanvas, JSON.stringify({
        signalingUrl: 'ws://' + currentIp + ':' + currentPort,
        roomCode: currentRoomCode
    }), { width: 160, margin: 1 }, function(error) {
        if (error) console.error('QR error:', error);
    });
}

// WebRTC
ipcRenderer.on('signaling-message', (event, message) => handleSignalingMessage(message));

function handleSignalingMessage(message) {
    if (!peerConnection) createPeerConnection();
    switch (message.type) {
        case 'offer': handleOffer(message); break;
        case 'ice-candidate': handleIceCandidate(message); break;
    }
}

function createPeerConnection() {
    peerConnection = new RTCPeerConnection({
        iceServers: [
            { urls: 'stun:stun.l.google.com:19302' },
            { urls: 'stun:stun1.l.google.com:19302' }
        ],
        iceCandidatePoolSize: 10,
        bundlePolicy: 'max-bundle',
        rtcpMuxPolicy: 'require'
    });

    peerConnection.ontrack = (event) => {
        if (event.streams && event.streams[0]) {
            remoteStream = event.streams[0];
            remoteVideo.srcObject = remoteStream;
            showVideo();
        }
    };

    peerConnection.onicecandidate = (event) => {
        if (event.candidate) {
            sendSignalingMessage({
                type: 'ice-candidate',
                candidate: event.candidate.candidate,
                sdpMid: event.candidate.sdpMid,
                sdpMLineIndex: event.candidate.sdpMLineIndex
            });
        }
    };

    peerConnection.onconnectionstatechange = () => {
        const statusText = document.getElementById('statusText');
        switch (peerConnection.connectionState) {
            case 'connected': statusText.textContent = '已连接'; startAudioContext(); break;
            case 'connecting': statusText.textContent = '连接中...'; break;
            case 'disconnected': statusText.textContent = '已断开'; stopAudioContext(); break;
            case 'failed': statusText.textContent = '连接失败'; stopAudioContext(); break;
        }
    };

    // 接收 DataChannel（Android 端创建的音频通道）
    peerConnection.ondatachannel = (event) => {
        const channel = event.channel;
        console.log('DataChannel received:', channel.label, channel.readyState);
        
        if (channel.label === 'audio-data') {
            setupAudioDataChannel(channel);
        }
    };

    // FPS 监控
    setInterval(async () => {
        if (peerConnection && peerConnection.connectionState === 'connected') {
            try {
                const stats = await peerConnection.getStats();
                stats.forEach(report => {
                    if (report.type === 'inbound-rtp' && report.kind === 'video') {
                        document.getElementById('fpsText').textContent = 
                            Math.round(report.framesPerSecond || 0) + ' FPS';
                    }
                });
            } catch (e) {}
        }
    }, 500);
}

/**
 * 设置音频 DataChannel - 接收 PCM 数据并播放
 */
function setupAudioDataChannel(channel) {
    channel.binaryType = 'arraybuffer';
    
    channel.onopen = () => {
        console.log('Audio DataChannel opened');
    };
    
    channel.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) {
            playPCMAudio(event.data);
        }
    };
    
    channel.onclose = () => {
        console.log('Audio DataChannel closed');
    };
}

/**
 * 使用 Web Audio API 播放 PCM 音频数据
 */
function startAudioContext() {
    if (audioContext) return;
    
    try {
        audioContext = new (window.AudioContext || window.webkitAudioContext)({
            sampleRate: AUDIO_SAMPLE_RATE
        });
        
        audioGainNode = audioContext.createGain();
        audioGainNode.gain.value = 1.0;
        audioGainNode.connect(audioContext.destination);
        
        nextPlayTime = audioContext.currentTime;
        console.log('AudioContext started, sampleRate:', audioContext.sampleRate);
    } catch (e) {
        console.error('Failed to create AudioContext:', e);
    }
}

function playPCMAudio(arrayBuffer) {
    if (!audioContext || audioContext.state === 'closed') return;
    
    if (audioContext.state === 'suspended') {
        audioContext.resume();
    }
    
    try {
        // 将 ArrayBuffer 转换为 Float32Array（PCM 16-bit -> Float32）
        const int16Array = new Int16Array(arrayBuffer);
        const float32Array = new Float32Array(int16Array.length);
        for (let i = 0; i < int16Array.length; i++) {
            float32Array[i] = int16Array[i] / 32768.0;
        }
        
        // 创建 AudioBuffer 并播放
        const audioBuffer = audioContext.createBuffer(2, float32Array.length / 2, AUDIO_SAMPLE_RATE);
        
        // 分离左右声道（交错数据：L R L R ...）
        const leftChannel = audioBuffer.getChannelData(0);
        const rightChannel = audioBuffer.getChannelData(1);
        
        for (let i = 0; i < float32Array.length / 2; i++) {
            leftChannel[i] = float32Array[i * 2];
            rightChannel[i] = float32Array[i * 2 + 1];
        }
        
        // 创建 BufferSource 并播放
        const source = audioContext.createBufferSource();
        source.buffer = audioBuffer;
        source.connect(audioGainNode);
        
        // 调度播放时间，确保连续
        const currentTime = audioContext.currentTime;
        if (nextPlayTime < currentTime) {
            nextPlayTime = currentTime;
        }
        
        source.start(nextPlayTime);
        // 每个样本的持续时间 = 样本数 / 采样率
        nextPlayTime += audioBuffer.duration;
        
        // 自动清理
        source.onended = () => source.disconnect();
        
    } catch (e) {
        // 偶尔的缓冲区错误可以忽略
    }
}

function stopAudioContext() {
    if (audioContext) {
        audioContext.close().catch(() => {});
        audioContext = null;
        audioGainNode = null;
        nextPlayTime = 0;
    }
}

async function handleOffer(message) {
    try {
        await peerConnection.setRemoteDescription(new RTCSessionDescription(message));
        const answer = await peerConnection.createAnswer({
            offerToReceiveAudio: true,
            offerToReceiveVideo: true
        });
        await peerConnection.setLocalDescription(answer);
        sendSignalingMessage({ type: 'answer', sdp: answer.sdp });
    } catch (error) {
        console.error('Handle offer error:', error);
    }
}

function handleIceCandidate(message) {
    try {
        if (peerConnection && peerConnection.remoteDescription) {
            peerConnection.addIceCandidate(new RTCIceCandidate({
                candidate: message.candidate,
                sdpMid: message.sdpMid,
                sdpMLineIndex: message.sdpMLineIndex
            }));
        }
    } catch (error) {}
}

function sendSignalingMessage(message) {
    ipcRenderer.send('signaling-message', message);
}

function showVideo() {
    connectionPanel.classList.add('hidden');
    videoContainer.classList.remove('hidden');
    statusBar.classList.add('visible');
    controls.classList.add('visible');
    loading.classList.remove('visible');
    
    shortcutsHint.classList.add('visible');
    setTimeout(() => shortcutsHint.classList.remove('visible'), 3000);
    
    remoteVideo.play().catch(e => console.log('Auto-play prevented'));
}

// 缩放控制
function setZoom(zoom) {
    currentZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom));
    remoteVideo.style.transform = `scale(${currentZoom})`;
    document.getElementById('zoomText').textContent = Math.round(currentZoom * 100) + '%';
    
    zoomInfo.textContent = Math.round(currentZoom * 100) + '%';
    zoomInfo.classList.add('visible');
    setTimeout(() => zoomInfo.classList.remove('visible'), 500);
}

function zoomIn() { setZoom(currentZoom + ZOOM_STEP); }
function zoomOut() { setZoom(currentZoom - ZOOM_STEP); }
function resetZoom() { setZoom(1.0); }

function fitContain() {
    remoteVideo.className = 'fit-contain';
    setZoom(1.0);
}

function fitOriginal() {
    remoteVideo.className = 'fit-original';
    setZoom(1.0);
}

// 按钮事件
document.getElementById('btnZoomIn').onclick = zoomIn;
document.getElementById('btnZoomOut').onclick = zoomOut;
document.getElementById('btnZoomReset').onclick = resetZoom;
document.getElementById('btnFitContain').onclick = fitContain;

document.getElementById('btnFullscreen').onclick = () => {
    if (remoteVideo.requestFullscreen) remoteVideo.requestFullscreen();
};

document.getElementById('btnDisconnect').onclick = () => {
    stopAudioContext();
    if (peerConnection) { peerConnection.close(); peerConnection = null; }
    if (remoteStream) { remoteStream.getTracks().forEach(t => t.stop()); remoteStream = null; }
    remoteVideo.srcObject = null;
    resetZoom();
    
    videoContainer.classList.add('hidden');
    connectionPanel.classList.remove('hidden');
    statusBar.classList.remove('visible');
    controls.classList.remove('visible');
};

// 键盘快捷键
document.addEventListener('keydown', (e) => {
    if (videoContainer.classList.contains('hidden')) return;
    switch (e.key) {
        case '+': case '=': e.preventDefault(); zoomIn(); break;
        case '-': case '_': e.preventDefault(); zoomOut(); break;
        case '0': e.preventDefault(); resetZoom(); break;
        case 'f': case 'F': e.preventDefault(); fitContain(); break;
        case '1': e.preventDefault(); fitOriginal(); break;
        case 'F11': e.preventDefault(); if (remoteVideo.requestFullscreen) remoteVideo.requestFullscreen(); break;
        case 'Escape': e.preventDefault(); document.getElementById('btnDisconnect').click(); break;
    }
});

// 鼠标滚轮缩放
videoContainer.addEventListener('wheel', (e) => {
    if (videoContainer.classList.contains('hidden')) return;
    e.preventDefault();
    if (e.deltaY < 0) zoomIn(); else zoomOut();
}, { passive: false });

// 视频拖动功能
let isDragging = false;
let startX, startY, scrollLeft, scrollTop;

videoContainer.addEventListener('mousedown', (e) => {
    if (videoContainer.classList.contains('hidden')) return;
    isDragging = true;
    videoContainer.style.cursor = 'grabbing';
    startX = e.pageX - videoContainer.offsetLeft;
    startY = e.pageY - videoContainer.offsetTop;
    scrollLeft = videoContainer.scrollLeft;
    scrollTop = videoContainer.scrollTop;
});

videoContainer.addEventListener('mouseleave', () => {
    isDragging = false;
    videoContainer.style.cursor = 'default';
});

videoContainer.addEventListener('mouseup', () => {
    isDragging = false;
    videoContainer.style.cursor = 'default';
});

videoContainer.addEventListener('mousemove', (e) => {
    if (!isDragging) return;
    e.preventDefault();
    const x = e.pageX - videoContainer.offsetLeft;
    const y = e.pageY - videoContainer.offsetTop;
    const walkX = (x - startX) * 2; // 拖动速度倍数
    const walkY = (y - startY) * 2;
    videoContainer.scrollLeft = scrollLeft - walkX;
    videoContainer.scrollTop = scrollTop - walkY;
});

// 触摸设备支持拖动
videoContainer.addEventListener('touchstart', (e) => {
    if (videoContainer.classList.contains('hidden')) return;
    isDragging = true;
    startX = e.touches[0].pageX - videoContainer.offsetLeft;
    startY = e.touches[0].pageY - videoContainer.offsetTop;
    scrollLeft = videoContainer.scrollLeft;
    scrollTop = videoContainer.scrollTop;
});

videoContainer.addEventListener('touchend', () => {
    isDragging = false;
});

videoContainer.addEventListener('touchmove', (e) => {
    if (!isDragging) return;
    const x = e.touches[0].pageX - videoContainer.offsetLeft;
    const y = e.touches[0].pageY - videoContainer.offsetTop;
    const walkX = (x - startX) * 2;
    const walkY = (y - startY) * 2;
    videoContainer.scrollLeft = scrollLeft - walkX;
    videoContainer.scrollTop = scrollTop - walkY;
});

init();
