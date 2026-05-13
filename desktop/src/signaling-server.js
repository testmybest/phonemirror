const WebSocket = require('ws');
const http = require('http');
const { EventEmitter } = require('events');
const os = require('os');
const crypto = require('crypto');

class SignalingServer extends EventEmitter {
  constructor() {
    super();
    this.server = null;
    this.wss = null;
    this.clients = new Map();
    this.roomCode = null;
    this.localIp = '0.0.0.0';
    this.port = 8080;
  }

  // Get all available local IPv4 addresses
  getAllLocalIps() {
    const interfaces = os.networkInterfaces();
    const ips = [];
    for (const name of Object.keys(interfaces)) {
      for (const iface of interfaces[name]) {
        if (iface.family === 'IPv4' && !iface.internal) {
          ips.push({
            name: name,
            address: iface.address,
            // Skip virtual adapters
            isVirtual: name.toLowerCase().includes('virtual') ||
                       name.toLowerCase().includes('vmware') ||
                       name.toLowerCase().includes('vbox') ||
                       name.toLowerCase().includes('hyper-v') ||
                       name.toLowerCase().includes('bluetooth')
          });
        }
      }
    }
    return ips;
  }

  // Get the best IP for WiFi connection
  getBestIp() {
    const ips = this.getAllLocalIps();
    // Prefer WiFi adapters
    const wifi = ips.find(ip =>
      !ip.isVirtual &&
      (ip.name.toLowerCase().includes('wi-fi') ||
       ip.name.toLowerCase().includes('wlan') ||
       ip.name.toLowerCase().includes('wireless'))
    );
    if (wifi) return wifi.address;

    // Fallback: first non-virtual adapter
    const fallback = ips.find(ip => !ip.isVirtual);
    if (fallback) return fallback.address;

    // Last resort
    return ips.length > 0 ? ips[0].address : '127.0.0.1';
  }

  generateRoomCode() {
    return crypto.randomBytes(3).toString('hex').toUpperCase();
  }

  async start(port = 8080, bindAddress = '0.0.0.0') {
    this.port = port;
    this.localIp = bindAddress === '0.0.0.0' ? this.getBestIp() : bindAddress;
    this.roomCode = this.generateRoomCode();

    this.server = http.createServer();
    this.wss = new WebSocket.Server({ server: this.server });

    this.wss.on('connection', (ws, req) => {
      this.handleConnection(ws, req);
    });

    return new Promise((resolve, reject) => {
      // Bind to 0.0.0.0 to accept connections from all interfaces
      this.server.listen(port, '0.0.0.0', () => {
        console.log('Signaling server running on 0.0.0.0:' + port);
        console.log('Access URL: ws://' + this.localIp + ':' + port);
        console.log('Room Code: ' + this.roomCode);
        resolve();
      });
      this.server.on('error', (err) => {
        console.error('Server error:', err.message);
        reject(err);
      });
    });
  }

  handleConnection(ws, req) {
    const clientId = crypto.randomUUID();
    const clientType = req.url.includes('mobile') ? 'mobile' : 'desktop';
    this.clients.set(clientId, { ws, type: clientType });

    console.log('Client connected: ' + clientId + ' (' + clientType + ')');

    ws.on('message', (data) => {
      try {
        const message = JSON.parse(data);
        this.handleMessage(clientId, message);
      } catch (error) {
        console.error('Invalid message:', error);
      }
    });

    ws.on('close', () => {
      this.clients.delete(clientId);
      console.log('Client disconnected: ' + clientId);
    });

    ws.on('error', (error) => {
      console.error('WebSocket error:', error.message);
    });

    if (clientType === 'mobile') {
      ws.send(JSON.stringify({
        type: 'room-info',
        roomCode: this.roomCode
      }));
    }
  }

  handleMessage(clientId, message) {
    const client = this.clients.get(clientId);
    if (!client) return;

    switch (message.type) {
      case 'join-room':
        if (message.roomCode === this.roomCode) {
          client.ws.send(JSON.stringify({ type: 'room-joined', success: true }));
          this.emit('mobile-connected', clientId);
        } else {
          client.ws.send(JSON.stringify({
            type: 'room-joined',
            success: false,
            error: 'Invalid room code'
          }));
        }
        break;

      case 'offer':
      case 'answer':
      case 'ice-candidate':
        for (const [id, c] of this.clients) {
          if (id !== clientId && c.ws.readyState === WebSocket.OPEN) {
            c.ws.send(JSON.stringify(message));
            this.emit('mobile-message', message);
          }
        }
        break;
    }
  }

  getUrl() {
    return 'ws://' + this.localIp + ':' + this.port;
  }

  getRoomCode() {
    return this.roomCode;
  }

  getAllNetworkInfo() {
    return {
      bestIp: this.localIp,
      allIps: this.getAllLocalIps(),
      port: this.port,
      roomCode: this.roomCode
    };
  }

  stop() {
    if (this.wss) this.wss.close();
    if (this.server) this.server.close();
    this.clients.clear();
  }

  // Broadcast message to all mobile clients
  broadcastToMobile(message) {
    const data = typeof message === 'string' ? message : JSON.stringify(message);
    for (const [id, client] of this.clients) {
      if (client.type === 'mobile' && client.ws.readyState === WebSocket.OPEN) {
        client.ws.send(data);
      }
    }
  }
}

module.exports = SignalingServer;
