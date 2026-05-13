const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const SignalingServer = require('./signaling-server');

class PhoneMirrorApp {
  constructor() {
    this.mainWindow = null;
    this.signalingServer = null;
  }

  async init() {
    await app.whenReady();
    this.createWindow();
    this.setupIPC();
    this.startSignalingServer();

    app.on('window-all-closed', () => {
      this.cleanup();
      app.quit();
    });

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        this.createWindow();
      }
    });
  }

  createWindow() {
    this.mainWindow = new BrowserWindow({
      width: 1200,
      height: 800,
      minWidth: 800,
      minHeight: 600,
      webPreferences: {
        nodeIntegration: true,
        contextIsolation: false
      },
      titleBarStyle: 'hiddenInset',
      show: false
    });

    this.mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));

    this.mainWindow.once('ready-to-show', () => {
      this.mainWindow.show();
    });

    this.mainWindow.on('closed', () => {
      this.mainWindow = null;
    });
  }

  setupIPC() {
    // Return all network info including all IPs
    ipcMain.handle('get-connection-info', async () => {
      if (!this.signalingServer) return null;
      return this.signalingServer.getAllNetworkInfo();
    });

    // Allow changing the IP address
    ipcMain.handle('change-ip', async (event, newIp) => {
      if (this.signalingServer) {
        this.signalingServer.localIp = newIp;
        return this.signalingServer.getUrl();
      }
      return null;
    });

    // Forward signaling messages from renderer to mobile clients
    ipcMain.on('signaling-message', (event, message) => {
      if (this.signalingServer) {
        this.signalingServer.broadcastToMobile(message);
      }
    });
  }

  async startSignalingServer() {
    try {
      this.signalingServer = new SignalingServer();
      await this.signalingServer.start(8080);

      // Forward signaling messages to renderer
      this.signalingServer.on('mobile-message', (message) => {
        if (this.mainWindow) {
          this.mainWindow.webContents.send('signaling-message', message);
        }
      });

      // Notify renderer that server is ready
      if (this.mainWindow) {
        this.mainWindow.webContents.send('server-ready', this.signalingServer.getAllNetworkInfo());
      }
    } catch (error) {
      console.error('Failed to start signaling server:', error);
    }
  }

  cleanup() {
    if (this.signalingServer) {
      this.signalingServer.stop();
    }
  }
}

const phoneMirror = new PhoneMirrorApp();
phoneMirror.init().catch(console.error);
