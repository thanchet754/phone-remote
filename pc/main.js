// Phone Remote (PC) - tiến trình chính
// Khởi động "trình trợ giúp" trên điện thoại bằng quyền shell (adb shell app_process ...).
// Trình này tự quay màn hình (không cần hộp thoại xin quyền, không cần mở app trên điện thoại)
// và bơm chạm/phím. PC nối tới nó qua cáp USB bằng "adb forward".
const { app, BrowserWindow, ipcMain, clipboard } = require('electron');
const { execFile, spawn } = require('child_process');
const net = require('net');
const fs = require('fs');
const path = require('path');

const VIDEO_PORT = 27183;
const INPUT_PORT = 27184;
const AUDIO_PORT = 27185;
const AUDIO_SOCKET = 'phoneremote_audio';
const VIDEO_SOCKET = 'phoneremote_video';
const INPUT_SOCKET = 'phoneremote_input';
const PACKAGE = 'com.ngoc.phoneremote';
const HELPER_CLASS = 'com.ngoc.phoneremote.ShellInput';

// Bản đóng gói mang theo adb và APK trong thư mục resources
const RES = process.resourcesPath || '';
const BUNDLED_APK = [path.join(RES, 'PhoneRemote.apk'),
  path.join(__dirname, '..', 'android', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')]
  .find(p => fs.existsSync(p)) || null;

const ADB_CANDIDATES = [
  path.join(RES, 'platform-tools', 'adb.exe'),
  path.join(__dirname, '..', 'platform-tools', 'adb.exe'),
  'C:\\Android\\Sdk\\platform-tools\\adb.exe',
  path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe')
];
const ADB = ADB_CANDIDATES.find(p => p && fs.existsSync(p)) || 'adb';

function adb(args, timeout = 15000) {
  return new Promise((resolve, reject) => {
    execFile(ADB, args, { timeout, windowsHide: true }, (err, stdout, stderr) => {
      if (err) return reject(new Error((stderr && stderr.trim()) || err.message));
      resolve(stdout);
    });
  });
}

let win = null;
let videoSock = null;
let inputSock = null;
let audioSock = null;
let helper = null;
let serialNow = null;
let screenOff = false;
let savedStayOn = null;
let savedRefreshMode = null;   // chế độ tần số quét của vivo trước khi ép 120Hz

// Máy vivo để "thông minh" sẽ tự hạ xuống 60-90Hz → ép 120Hz trong lúc kết nối
async function forceRefresh120(serial) {
  try {
    const cur = (await adb(['-s', serial, 'shell', 'settings', 'get', 'global', 'vivo_screen_refresh_rate_mode'])).trim();
    if (!/^\d+$/.test(cur)) return;            // không phải máy vivo → bỏ qua
    if (cur !== '120') {
      savedRefreshMode = cur;
      await adb(['-s', serial, 'shell', 'settings', 'put', 'global', 'vivo_screen_refresh_rate_mode', '120']);
    }
  } catch (e) {}
}

async function restoreRefresh() {
  if (savedRefreshMode !== null && serialNow) {
    try { await adb(['-s', serialNow, 'shell', 'settings', 'put', 'global', 'vivo_screen_refresh_rate_mode', savedRefreshMode]); } catch (e) {}
  }
  savedRefreshMode = null;
}

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

function createWindow() {
  win = new BrowserWindow({
    width: 760,
    height: 1000,
    backgroundColor: '#0f172a',
    title: 'Phone Remote',
    icon: path.join(__dirname, 'icon.ico'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false
    }
  });
  win.loadFile(path.join(__dirname, 'index.html'));
}

// Không giới hạn tốc độ vẽ theo 60Hz → cho phép hiển thị 120 FPS
app.commandLine.appendSwitch('disable-frame-rate-limit');
app.commandLine.appendSwitch('disable-gpu-vsync');

app.whenReady().then(createWindow);

let quitting = false;
app.on('window-all-closed', async () => {
  if (quitting) return;
  quitting = true;
  await disconnect();
  app.quit();
});

function sendInput(cmd) {
  if (inputSock && !inputSock.destroyed) inputSock.write(JSON.stringify(cmd) + '\n');
}

// ---------------- Tắt/bật tấm nền màn hình điện thoại ----------------

// Khi tấm nền đang tắt: theo dõi cảm ứng thật của điện thoại (getevent).
// Lệnh chạm do PC bơm vào không đi qua đây, nên có tín hiệu = có người chạm vào máy → bật màn hình lại.
let touchWatch = null;

function startTouchWatch() {
  stopTouchWatch();
  if (!serialNow) return;
  const child = spawn(ADB, ['-s', serialNow, 'shell', 'getevent', '-lq'], { windowsHide: true });
  touchWatch = child;
  child.stdout.on('data', (d) => {
    if (touchWatch !== child) return;
    if (/ABS_MT_|BTN_TOUCH|KEY_POWER/.test(d.toString())) {
      stopTouchWatch();
      setScreen(false).then(() => send('screenState', { off: false })).catch(() => {});
    }
  });
  child.on('exit', () => { if (touchWatch === child) touchWatch = null; });
}

function stopTouchWatch() {
  if (touchWatch) { const c = touchWatch; touchWatch = null; try { c.kill(); } catch (e) {} }
}

async function setScreen(off) {
  if (!serialNow) return;
  if (!off) stopTouchWatch();
  if (off) {
    // Giữ máy thức khi cắm USB để không tự khoá trong lúc tấm nền tắt
    if (savedStayOn === null) {
      try { savedStayOn = (await adb(['-s', serialNow, 'shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in'])).trim(); } catch (e) { savedStayOn = '0'; }
    }
    try { await adb(['-s', serialNow, 'shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '7']); } catch (e) {}
    sendInput({ t: 'keycode', code: 224 }); // đảm bảo máy đang thức
    await new Promise(r => setTimeout(r, 300));
    sendInput({ t: 'screen', on: false });
    screenOff = true;
    setTimeout(() => { if (screenOff) startTouchWatch(); }, 600);
  } else {
    sendInput({ t: 'screen', on: true });
    screenOff = false;
    await restoreStayOn();
  }
}

async function restoreStayOn() {
  if (savedStayOn !== null && serialNow) {
    const v = /^\d+$/.test(savedStayOn) ? savedStayOn : '0';
    try { await adb(['-s', serialNow, 'shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', v]); } catch (e) {}
  }
  savedStayOn = null;
}


// ---------------- Đồng bộ bộ nhớ tạm (clipboard) hai chiều: chữ và ảnh ----------------
const os = require('os');
const crypto = require('crypto');
const { nativeImage } = require('electron');
const REMOTE_CLIP_DIR = '/sdcard/Pictures/PhoneRemote';

let clipTimer = null;
let lastClipText = '';
let lastClipImg = null;       // mã băm ảnh đang có trong clipboard PC
let lastRemoteClipFile = null;
let pushingImage = false;

function imgHash(img) {
  return crypto.createHash('md5').update(img.toBitmap()).digest('hex');
}

function pcClipboardImage() {
  if (!clipboard.availableFormats().some(f => f.startsWith('image/'))) return null;
  const img = clipboard.readImage();
  return img.isEmpty() ? null : img;
}

// Điện thoại vừa copy/cut → đưa vào clipboard PC
async function onPhoneClip(msg) {
  try {
    if (msg.clip === 'text' && typeof msg.s === 'string') {
      if (msg.s === clipboard.readText()) return;
      lastClipText = msg.s;
      lastClipImg = null;
      clipboard.writeText(msg.s);
      send('notice', { ok: true, msg: 'Đã chép chữ từ điện thoại vào PC.' });
    } else if (msg.clip === 'uri' && /^content:\/\/[\w.\-\/%@:~+=]+$/.test(msg.uri || '') && String(msg.mime || '').startsWith('image/')) {
      const buf = await new Promise((resolve, reject) => {
        execFile(ADB, ['-s', serialNow, 'exec-out', 'content', 'read', '--uri', msg.uri],
          { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024, timeout: 20000, windowsHide: true },
          (err, stdout) => err ? reject(err) : resolve(stdout));
      });
      const img = nativeImage.createFromBuffer(buf);
      if (img.isEmpty()) throw new Error('không đọc được ảnh');
      lastClipImg = imgHash(img);
      lastClipText = clipboard.readText();
      clipboard.writeImage(img);
      send('notice', { ok: true, msg: 'Đã chép ảnh từ điện thoại vào PC.' });
    }
  } catch (e) {
    send('notice', { ok: false, msg: 'Không lấy được nội dung copy từ điện thoại: ' + e.message });
  }
}

// PC vừa copy ảnh → đưa ảnh vào thư viện điện thoại rồi đặt vào clipboard điện thoại
async function pushImageToPhone(img) {
  if (pushingImage || !serialNow) return false;
  pushingImage = true;
  const serial = serialNow;
  const name = `clip_${Date.now()}.png`;
  const local = path.join(os.tmpdir(), 'phoneremote_' + name);
  const remote = `${REMOTE_CLIP_DIR}/${name}`;
  try {
    fs.writeFileSync(local, img.toPNG());
    await adb(['-s', serial, 'shell', 'mkdir', '-p', REMOTE_CLIP_DIR]);
    await adb(['-s', serial, 'push', local, remote], 30000);
    await adb(['-s', serial, 'shell', 'content', 'call', '--uri', 'content://media', '--method', 'scan_file', '--arg', remote]);
    const q = await adb(['-s', serial, 'shell',
      `content query --uri content://media/external/images/media --projection _id --where "_data='/storage/emulated/0/Pictures/PhoneRemote/${name}'"`]);
    const id = (q.match(/_id=(\d+)/) || [])[1];
    if (!id) throw new Error('điện thoại chưa nhận ảnh');
    sendInput({ t: 'clipuri', uri: `content://media/external/images/media/${id}`, mime: 'image/png' });
    // Chỉ giữ ảnh mới nhất trong thư mục PhoneRemote của điện thoại
    if (lastRemoteClipFile) {
      const old = lastRemoteClipFile;
      adb(['-s', serial, 'shell', 'rm', '-f', old])
        .then(() => adb(['-s', serial, 'shell', 'content', 'call', '--uri', 'content://media', '--method', 'scan_file', '--arg', old]))
        .catch(() => {});
    }
    lastRemoteClipFile = remote;
    send('notice', { ok: true, msg: 'Đã chép ảnh từ PC vào điện thoại.' });
    return true;
  } catch (e) {
    send('notice', { ok: false, msg: 'Không đưa được ảnh sang điện thoại: ' + e.message });
    return false;
  } finally {
    pushingImage = false;
    try { fs.unlinkSync(local); } catch (e) {}
  }
}

// Kiểm tra clipboard PC; có nội dung mới thì đẩy sang điện thoại. Trả về khi đã đẩy xong.
async function syncPcClipboard() {
  if (!inputSock || inputSock.destroyed) return;
  const img = pcClipboardImage();
  if (img) {
    const h = imgHash(img);
    if (h !== lastClipImg) {
      lastClipImg = h;
      lastClipText = clipboard.readText();
      await pushImageToPhone(img);
    }
    return;
  }
  lastClipImg = null;
  const text = clipboard.readText();
  if (text && text !== lastClipText) {
    lastClipText = text;
    sendInput({ t: 'clip', s: text });
  }
}

function startClipWatch() {
  stopClipWatch();
  // Nội dung có sẵn trước khi kết nối không tự gửi đi
  lastClipText = clipboard.readText();
  const img = pcClipboardImage();
  lastClipImg = img ? imgHash(img) : null;
  let busy = false;
  clipTimer = setInterval(async () => {
    if (busy) return;
    busy = true;
    try { await syncPcClipboard(); } catch (e) {} finally { busy = false; }
  }, 700);
}

function stopClipWatch() {
  if (clipTimer) { clearInterval(clipTimer); clipTimer = null; }
}

// Ctrl+V trong cửa sổ: đảm bảo clipboard PC đã sang điện thoại rồi mới dán
ipcMain.handle('paste', async () => {
  lastClipText = '';      // ép gửi lại nội dung hiện tại
  lastClipImg = null;
  await syncPcClipboard();
  await new Promise(r => setTimeout(r, 120));
  sendInput({ t: 'keycode', code: 279 });
  return true;
});

// Điện thoại gửi về qua kênh điều khiển: mỗi dòng một JSON (hiện dùng cho clipboard)
function attachInputReader(s) {
  let pending = '';
  s.setEncoding('utf8');
  s.on('data', (d) => {
    pending += d;
    let i;
    while ((i = pending.indexOf(String.fromCharCode(10))) >= 0) {
      const line = pending.slice(0, i);
      pending = pending.slice(i + 1);
      if (!line.trim()) continue;
      try { const m = JSON.parse(line); if (m.clip) onPhoneClip(m); } catch (e) {}
    }
    if (pending.length > 8 * 1024 * 1024) pending = '';
  });
}

// ---------------- Kết nối ----------------

async function disconnect() {
  stopTouchWatch();
  if (screenOff) {
    sendInput({ t: 'screen', on: true });
    screenOff = false;
    await new Promise(r => setTimeout(r, 250));
  }
  stopClipWatch();
  await restoreStayOn();
  await restoreRefresh();
  if (videoSock) { try { videoSock.destroy(); } catch (e) {} videoSock = null; }
  if (inputSock) { try { inputSock.destroy(); } catch (e) {} inputSock = null; }
  closeAudio();
  if (helper) { try { helper.kill(); } catch (e) {} helper = null; }
  if (serialNow) {
    try { await adb(['-s', serialNow, 'shell', 'pkill', '-f', HELPER_CLASS]); } catch (e) {}
  }
  serialNow = null;
}

async function startHelper(serial) {
  try { await adb(['-s', serial, 'shell', 'pkill', '-f', HELPER_CLASS]); } catch (e) {}
  const findApk = async () => {
    let pm = '';
    try { pm = await adb(['-s', serial, 'shell', 'pm', 'path', PACKAGE]); } catch (e) {}
    return (pm.match(/package:(\S+)/) || [])[1];
  };
  let apk = await findApk();
  if (!apk && BUNDLED_APK) {
    // Lần đầu: tự cài app lên điện thoại (máy vivo sẽ hỏi xác nhận trên màn hình)
    send('notice', { ok: true, msg: 'Đang cài app Phone Remote lên điện thoại, hãy mở khóa máy và bấm đồng ý cài đặt…' });
    try { await adb(['-s', serial, 'install', '-r', BUNDLED_APK], 180000); }
    catch (e) { throw new Error('Không cài được app lên điện thoại: ' + e.message); }
    apk = await findApk();
  }
  if (!apk) throw new Error('Chưa cài app Phone Remote trên điện thoại');

  await new Promise((resolve, reject) => {
    const child = spawn(ADB, ['-s', serial, 'shell', `CLASSPATH=${apk} app_process / ${HELPER_CLASS}`], { windowsHide: true });
    helper = child;
    let out = '';
    let done = false;
    const finish = (err) => { if (done) return; done = true; clearTimeout(timer); err ? reject(err) : resolve(); };
    const timer = setTimeout(() => finish(new Error('Trình trợ giúp trên điện thoại không khởi động được: ' + out.trim())), 10000);
    child.stdout.on('data', (d) => {
      out += d.toString();
      if (out.includes('READY') && out.includes('VIDEO_READY')) finish();
      else if (/ERROR/.test(out)) finish(new Error(out.trim()));
    });
    child.stderr.on('data', (d) => { out += d.toString(); });
    child.on('exit', () => {
      if (helper === child) helper = null;
      finish(new Error('Trình trợ giúp đã dừng: ' + out.trim()));
      send('closed', true);
    });
  });
}

function openInput() {
  return new Promise((resolve, reject) => {
    const s = net.connect({ host: '127.0.0.1', port: INPUT_PORT });
    s.setNoDelay(true);
    s.once('connect', () => { inputSock = s; attachInputReader(s); resolve(); });
    s.once('error', (e) => reject(new Error('Không nối được kênh điều khiển: ' + e.message)));
    s.on('close', () => { if (inputSock === s) inputSock = null; });
  });
}

// Kênh âm thanh: PCM 16 bit, 48 kHz, stereo. Khi kênh mở, điện thoại không phát ra loa nữa.
function openAudio() {
  closeAudio();
  return new Promise((resolve, reject) => {
    const s = net.connect({ host: '127.0.0.1', port: AUDIO_PORT });
    s.setNoDelay(true);
    s.once('connect', () => { audioSock = s; resolve(); });
    s.once('error', (e) => reject(new Error('Không nối được kênh âm thanh: ' + e.message)));
    s.on('data', (chunk) => send('audio', new Uint8Array(chunk)));
    s.on('close', () => { if (audioSock === s) audioSock = null; });
  });
}

function closeAudio() {
  if (audioSock) { try { audioSock.destroy(); } catch (e) {} audioSock = null; }
}

function openVideo() {
  return new Promise((resolve, reject) => {
    const s = net.connect({ host: '127.0.0.1', port: VIDEO_PORT });
    s.setNoDelay(true);
    let buf = Buffer.alloc(0);
    let settled = false;
    const timer = setTimeout(() => {
      if (!settled) { settled = true; s.destroy(); reject(new Error('Điện thoại không gửi hình. Hãy mở khoá điện thoại rồi kết nối lại.')); }
    }, 6000);

    s.on('data', (chunk) => {
      if (!settled) { settled = true; clearTimeout(timer); videoSock = s; resolve(); }
      buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
      // Gói tin: [1 byte loại][4 byte độ dài][dữ liệu]
      while (buf.length >= 5) {
        const type = buf[0];
        const len = buf.readUInt32BE(1);
        if (len > 32 * 1024 * 1024) { s.destroy(); return; }
        if (buf.length < 5 + len) break;
        const payload = buf.subarray(5, 5 + len);
        buf = buf.subarray(5 + len);
        if (type === 1 && payload.length >= 9) {
          send('video', {
            pts: Number(payload.readBigInt64BE(0)),
            flags: payload[8],
            data: new Uint8Array(payload.subarray(9))
          });
        } else if (type === 0) {
          try { send('config', JSON.parse(payload.toString('utf8'))); } catch (e) {}
        }
      }
    });
    s.on('error', (err) => {
      clearTimeout(timer);
      if (!settled) { settled = true; reject(new Error('Không nối được kênh hình: ' + err.message)); }
    });
    s.on('close', () => {
      clearTimeout(timer);
      if (videoSock === s) { videoSock = null; send('closed', true); }
      if (!settled) { settled = true; reject(new Error('Kênh hình bị đóng ngay sau khi nối.')); }
    });
  });
}

ipcMain.handle('devices', async () => {
  const out = await adb(['devices', '-l']);
  return out.split(/\r?\n/).slice(1).map(l => l.trim()).filter(Boolean).map(l => {
    const parts = l.split(/\s+/);
    const model = (l.match(/model:(\S+)/) || [])[1] || '';
    return { id: parts[0], state: parts[1], model };
  });
});

ipcMain.handle('connect', async (_e, serial, settings) => {
  await disconnect();
  if (!/^[\w.:\-]+$/.test(String(serial || ''))) throw new Error('Chưa chọn điện thoại');
  try {
    await startHelper(serial);
    serialNow = serial;
    await adb(['-s', serial, 'forward', `tcp:${VIDEO_PORT}`, `localabstract:${VIDEO_SOCKET}`]);
    await adb(['-s', serial, 'forward', `tcp:${INPUT_PORT}`, `localabstract:${INPUT_SOCKET}`]);
    await adb(['-s', serial, 'forward', `tcp:${AUDIO_PORT}`, `localabstract:${AUDIO_SOCKET}`]);
    await forceRefresh120(serial);
    await openInput();
    if (settings) sendInput({ t: 'video', ...settings });   // áp dụng trước khi mở kênh hình
    await new Promise(r => setTimeout(r, 150));
    await openVideo();
    send('input', { ready: true });
    startClipWatch();
    // Âm thanh: lỗi thì vẫn dùng được hình và điều khiển
    openAudio().then(() => send('audioState', { on: true })).catch((e) => send('audioState', { on: false, error: e.message }));
    return true;
  } catch (e) {
    serialNow = serial;
    await disconnect();
    throw e;
  }
});

ipcMain.handle('disconnect', async () => { await disconnect(); return true; });

ipcMain.handle('screen', async (_e, off) => { await setScreen(!!off); return screenOff; });

ipcMain.on('cmd', (_e, cmd) => {
  if (!cmd) return;
  if (cmd.t === 'keyframe') {
    // Bất kỳ gói nào trên kênh hình = xin khung hình chính
    if (videoSock && !videoSock.destroyed) videoSock.write(Buffer.from([0, 0, 0, 0, 0]));
    return;
  }
  sendInput(cmd);
});

// Bật: phát trên PC (điện thoại im). Tắt: trả âm thanh về loa điện thoại.
ipcMain.handle('audio', async (_e, on) => {
  if (on) { await openAudio(); return true; }
  closeAudio();
  return false;
});

ipcMain.handle('clipboard', async () => clipboard.readText());
