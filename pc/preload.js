const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('remote', {
  devices: () => ipcRenderer.invoke('devices'),
  connect: (serial, settings) => ipcRenderer.invoke('connect', serial, settings),
  disconnect: () => ipcRenderer.invoke('disconnect'),
  clipboard: () => ipcRenderer.invoke('clipboard'),
  screen: (off) => ipcRenderer.invoke('screen', off),
  audio: (on) => ipcRenderer.invoke('audio', on),
  paste: () => ipcRenderer.invoke('paste'),
  cmd: (c) => ipcRenderer.send('cmd', c),
  on: (channel, fn) => {
    if (!['video', 'config', 'notice', 'closed', 'input', 'audio', 'audioState', 'screenState'].includes(channel)) return;
    ipcRenderer.on(channel, (_e, payload) => fn(payload));
  }
});
