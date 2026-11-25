// Phone Remote (PC) - cửa sổ: giải mã H.264 bằng WebCodecs, vẽ lên canvas, gửi chuột/bàn phím về điện thoại
const canvas = document.getElementById('screen');
const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
const $ = (id) => document.getElementById(id);
const devicesSel = $('devices');
const msgEl = $('msg');

const FLAG_KEY = 1, FLAG_CONFIG = 2;
let decoder = null;
let csd = null;            // SPS/PPS (Annex-B)
let waitKey = true;        // chờ khung hình chính đầu tiên
let connected = false;
let frameCount = 0;
const pendingTs = new Map();   // pts → thời điểm đưa vào bộ giải mã
let lagSum = 0, lagN = 0;

function setMsg(text, isError = true) {
  msgEl.textContent = text || '';
  msgEl.style.color = isError ? 'var(--bad)' : 'var(--muted)';
}
function setState(on) {
  connected = on;
  if (!on && typeof phoneScreenOff !== 'undefined') { phoneScreenOff = false; syncScreenBtn(); }
  $('state').textContent = on ? 'đã kết nối' : 'chưa kết nối';
  $('state').className = on ? 'ok' : 'bad';
  $('connect').textContent = on ? 'Ngắt' : 'Kết nối';
  $('empty').style.display = on ? 'none' : '';
  if (!on) { $('fps').textContent = '–'; }
}

// ---------------- Giải mã video ----------------

function codecFromSps(bytes) {
  // Tìm NAL loại 7 (SPS) sau mã bắt đầu 00 00 01, lấy profile/constraint/level
  for (let i = 0; i + 4 < bytes.length; i++) {
    if (bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1 && (bytes[i + 3] & 0x1f) === 7) {
      const hex = (n) => n.toString(16).padStart(2, '0');
      return 'avc1.' + hex(bytes[i + 4]) + hex(bytes[i + 5]) + hex(bytes[i + 6]);
    }
  }
  return 'avc1.640033';
}

function resetDecoder() {
  if (decoder) { try { decoder.close(); } catch (e) {} }
  decoder = new VideoDecoder({
    output: (frame) => {
      if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
        canvas.width = frame.displayWidth;
        canvas.height = frame.displayHeight;
        $('res').textContent = `${frame.displayWidth}×${frame.displayHeight}`;
      }
      ctx.drawImage(frame, 0, 0);
      const t0 = pendingTs.get(frame.timestamp);
      if (t0 !== undefined) { pendingTs.delete(frame.timestamp); lagSum += performance.now() - t0; lagN++; }
      frame.close();
      frameCount++;
    },
    error: (e) => {
      setMsg('Lỗi giải mã video: ' + e.message);
      waitKey = true;
      window.remote.cmd({ t: 'keyframe' });
    }
  });
  decoder.configure({
    codec: csd ? codecFromSps(csd) : 'avc1.640033',
    optimizeForLatency: true,
    hardwareAcceleration: 'prefer-hardware'
  });
  waitKey = true;
}

function concat(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0); out.set(b, a.length);
  return out;
}

window.remote.on('video', (p) => {
  if (p.flags & FLAG_CONFIG) {
    csd = p.data;
    resetDecoder();
    return;
  }
  if (!decoder || decoder.state !== 'configured') {
    if (!csd) return;
    resetDecoder();
  }
  const isKey = (p.flags & FLAG_KEY) !== 0;
  if (waitKey && !isKey) return;
  // Nếu bị dồn khung (máy chậm) thì bỏ tới khung chính kế tiếp để không trễ dần
  if (decoder.decodeQueueSize > 8 && !isKey) { waitKey = true; window.remote.cmd({ t: 'keyframe' }); return; }
  waitKey = false;
  pendingTs.set(p.pts, performance.now());
  if (pendingTs.size > 300) pendingTs.clear();
  try {
    decoder.decode(new EncodedVideoChunk({
      type: isKey ? 'key' : 'delta',
      timestamp: p.pts,
      data: isKey && csd ? concat(csd, p.data) : p.data
    }));
  } catch (e) {
    waitKey = true;
  }
});

window.remote.on('config', () => {});
window.remote.on('input', (s) => {
  $('acc').textContent = s.ready ? 'sẵn sàng' : 'chưa sẵn sàng';
  $('acc').className = s.ready ? 'ok' : 'bad';
  if (!s.ready && s.error) setMsg('Chưa điều khiển được: ' + s.error);
});
window.remote.on('notice', (n) => setMsg(n.msg, !n.ok));
window.remote.on('closed', () => { setState(false); setMsg('Mất kết nối với điện thoại.'); });

setInterval(() => {
  if (connected) {
    $('fps').textContent = frameCount + ' khung/giây';
    $('lag').textContent = lagN ? (lagSum / lagN).toFixed(1) + ' ms' : '–';
  }
  frameCount = 0; lagSum = 0; lagN = 0;
}, 1000);

// ---------------- Thiết bị ----------------

async function refreshDevices() {
  try {
    const list = await window.remote.devices();
    devicesSel.innerHTML = '';
    for (const d of list) {
      const o = document.createElement('option');
      o.value = d.id;
      o.textContent = `${d.model || d.id} (${d.state === 'device' ? 'sẵn sàng' : d.state})`;
      o.disabled = d.state !== 'device';
      devicesSel.appendChild(o);
    }
    if (!list.length) {
      const o = document.createElement('option');
      o.value = ''; o.textContent = 'Không thấy điện thoại';
      devicesSel.appendChild(o);
      setMsg('Cắm cáp USB, bật "Gỡ lỗi USB" và bấm "Cho phép" trên điện thoại.');
    } else if (list.some(d => d.state === 'unauthorized')) {
      setMsg('Điện thoại chưa cho phép gỡ lỗi USB. Hãy bấm "Cho phép" trên điện thoại.');
    } else {
      setMsg('');
    }
  } catch (e) {
    setMsg('Không chạy được ADB: ' + e.message);
  }
}

$('refresh').addEventListener('click', refreshDevices);
$('connect').addEventListener('click', async (ev) => {
  if (connecting) return;
  if (connected) {
    userDisconnected = true;          // bấm Ngắt → không tự nối lại cho tới khi bấm Kết nối
    await window.remote.disconnect();
    setState(false);
    return;
  }
  if (ev.isTrusted) userDisconnected = false;
  connecting = true;
  setMsg('Đang kết nối...', false);
  try { await initAudio(); } catch (e) { console.warn('audio init', e); }
  try {
    csd = null;
    await window.remote.connect(devicesSel.value, readSettings());
    setState(true);
    setMsg('');
    canvas.focus();
  } catch (e) {
    setState(false);
    setMsg(e.message.replace(/^Error invoking remote method '[^']+': Error: /, ''));
  } finally {
    connecting = false;
  }
});

// ---------------- Chuột ----------------

function norm(ev) {
  const r = canvas.getBoundingClientRect();
  const clamp = (v) => Math.max(0, Math.min(1, v));
  return { x: clamp((ev.clientX - r.left) / r.width), y: clamp((ev.clientY - r.top) / r.height) };
}

let pressing = false;
let pendingMove = null;

canvas.addEventListener('pointerdown', (e) => {
  canvas.focus();
  if (!connected) return;
  if (e.button === 2) { window.remote.cmd({ t: 'key', k: 'back' }); return; }
  if (e.button !== 0) return;
  pressing = true;
  canvas.setPointerCapture(e.pointerId);
  window.remote.cmd({ t: 'down', ...norm(e) });
});
canvas.addEventListener('pointermove', (e) => {
  if (!pressing) return;
  // Gom các lần di chuyển trong cùng một khung vẽ
  const first = pendingMove === null;
  pendingMove = norm(e);
  if (first) {
    requestAnimationFrame(() => {
      if (pendingMove && pressing) window.remote.cmd({ t: 'move', ...pendingMove });
      pendingMove = null;
    });
  }
});
function release(e) {
  if (!pressing) return;
  pressing = false;
  pendingMove = null;
  window.remote.cmd({ t: 'up', ...norm(e) });
}
canvas.addEventListener('pointerup', release);
canvas.addEventListener('pointercancel', release);
canvas.addEventListener('contextmenu', (e) => e.preventDefault());

// Lăn chuột → sự kiện cuộn thật trên điện thoại (gom theo khung vẽ)
let wheelAcc = 0;
let wheelPos = null;
canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  if (!connected || pressing) return;
  const first = wheelAcc === 0;
  wheelAcc += -e.deltaY / 100;
  wheelPos = norm(e);
  if (first) {
    requestAnimationFrame(() => {
      if (wheelAcc !== 0 && wheelPos) window.remote.cmd({ t: 'scroll', x: wheelPos.x, y: wheelPos.y, v: Math.max(-8, Math.min(8, wheelAcc)) });
      wheelAcc = 0;
    });
  }
}, { passive: false });

// ---------------- Bàn phím ----------------

// Dán: đưa clipboard PC (chữ hoặc ảnh) sang điện thoại rồi bấm Dán trên điện thoại
async function pasteFromPc() {
  await window.remote.paste();
}
$('paste').addEventListener('click', pasteFromPc);

let typeBuf = '';
let typeTimer = null;
function flushType() {
  clearTimeout(typeTimer);
  if (!typeBuf) return;
  window.remote.cmd({ t: 'text', s: typeBuf });
  typeBuf = '';
}

canvas.addEventListener('keydown', (e) => {
  if (!connected) return;
  if (e.ctrlKey || e.metaKey) {
    const k = e.key.toLowerCase();
    if (k === 'v') { e.preventDefault(); flushType(); pasteFromPc(); }
    else if (k === 'c') { e.preventDefault(); flushType(); window.remote.cmd({ t: 'keycode', code: 278 }); }  // Copy trên điện thoại
    else if (k === 'x') { e.preventDefault(); flushType(); window.remote.cmd({ t: 'keycode', code: 277 }); }  // Cut trên điện thoại
    return;
  }
  if (e.altKey) return;
  if (e.key === 'Backspace') {
    e.preventDefault();
    // Bộ gõ (Unikey) xoá rồi gõ lại ký tự có dấu: nếu ký tự còn trong bộ đệm thì chỉ cần bỏ khỏi bộ đệm
    if (typeBuf) { typeBuf = Array.from(typeBuf).slice(0, -1).join(''); return; }
    window.remote.cmd({ t: 'bksp' });
    return;
  }
  if (e.key === 'Enter') { e.preventDefault(); flushType(); window.remote.cmd({ t: 'enter' }); return; }
  if (e.key === 'Escape') { e.preventDefault(); flushType(); window.remote.cmd({ t: 'key', k: 'back' }); return; }
  if (e.key === 'Tab') { e.preventDefault(); return; }
  if (e.key.length >= 1 && Array.from(e.key).length === 1) {
    e.preventDefault();
    typeBuf += e.key;
    clearTimeout(typeTimer);
    typeTimer = setTimeout(flushType, 60);
  }
});

document.querySelectorAll('button[data-key]').forEach(b => {
  b.addEventListener('click', () => { window.remote.cmd({ t: 'key', k: b.dataset.key }); canvas.focus(); });
});
document.querySelectorAll('button[data-vol]').forEach(b => {
  b.addEventListener('click', () => { window.remote.cmd({ t: 'vol', d: +b.dataset.vol }); canvas.focus(); });
});

// Bật màn hình và mở bàn phím nhập mật khẩu (phím WAKEUP rồi MENU)
$('wake').addEventListener('click', () => {
  window.remote.cmd({ t: 'keycode', code: 224 });
  setTimeout(() => window.remote.cmd({ t: 'keycode', code: 82 }), 300);
  canvas.focus();
});

// Tắt / bật tấm nền màn hình điện thoại (máy vẫn chạy, PC vẫn xem và điều khiển)
let phoneScreenOff = false;
function syncScreenBtn() {
  $('screenoff').textContent = phoneScreenOff ? 'Bật màn hình ĐT' : 'Tắt màn hình ĐT';
  $('screenoff').classList.toggle('primary', phoneScreenOff);
}
// Có người chạm vào màn hình thật của điện thoại → app đã tự bật màn hình lại
window.remote.on('screenState', (s) => {
  phoneScreenOff = !!s.off;
  syncScreenBtn();
  setMsg('Có chạm vào điện thoại nên màn hình đã bật lại.', false);
});

$('screenoff').addEventListener('click', async () => {
  if (!connected) return;
  phoneScreenOff = await window.remote.screen(!phoneScreenOff);
  syncScreenBtn();
  canvas.focus();
});

// ---------------- Âm thanh: phát tiếng của điện thoại trên loa PC ----------------
let audioCtx = null;
let audioNode = null;
let audioOn = true;
let audioTail = null;   // byte lẻ còn lại của khối trước (mỗi khung = 4 byte)

async function initAudio() {
  if (audioCtx) { if (audioCtx.state === 'suspended') await audioCtx.resume(); return; }
  audioCtx = new AudioContext({ sampleRate: 48000, latencyHint: 'interactive' });
  await audioCtx.audioWorklet.addModule('audio-worklet.js');
  audioNode = new AudioWorkletNode(audioCtx, 'pcm-player', { numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [2] });
  audioNode.connect(audioCtx.destination);
}

window.remote.on('audio', (chunk) => {
  if (!audioNode || !audioOn) return;
  let bytes = chunk;
  if (audioTail && audioTail.length) {
    const joined = new Uint8Array(audioTail.length + chunk.length);
    joined.set(audioTail, 0); joined.set(chunk, audioTail.length);
    bytes = joined;
  }
  const usable = bytes.length - (bytes.length % 4);
  audioTail = usable < bytes.length ? bytes.slice(usable) : null;
  if (!usable) return;
  const view = new DataView(bytes.buffer, bytes.byteOffset, usable);
  const f32 = new Float32Array(usable / 2);
  for (let i = 0; i < f32.length; i++) f32[i] = view.getInt16(i * 2, true) / 32768;
  audioNode.port.postMessage(f32, [f32.buffer]);
});

function syncAudioBtn() {
  $('audio').textContent = audioOn ? 'Âm thanh: PC' : 'Âm thanh: điện thoại';
  $('audio').classList.toggle('primary', audioOn);
}
window.remote.on('audioState', (s) => {
  audioOn = !!s.on;
  syncAudioBtn();
  if (!s.on && s.error) setMsg('Chưa lấy được âm thanh: ' + s.error);
});
$('audio').addEventListener('click', async () => {
  if (!connected) return;
  try {
    if (!audioOn) await initAudio();
    audioOn = await window.remote.audio(!audioOn);
    audioTail = null;
  } catch (e) {
    audioOn = false;
    setMsg('Chưa lấy được âm thanh: ' + e.message);
  }
  syncAudioBtn();
  canvas.focus();
});
syncAudioBtn();

// ---------------- Cài đặt hình: độ phân giải, khung hình/giây, bitrate ----------------
const PRESETS = {
  lowlag:   { maxSize: 1280, fps: 120, bitrate: 12000000 },  // ít điểm ảnh → nén và giải mã nhanh nhất
  balanced: { maxSize: 1920, fps: 120, bitrate: 20000000 },
  sharp:    { maxSize: 0,    fps: 120, bitrate: 32000000 }   // đúng độ phân giải gốc
};
function readSettings() {
  return { maxSize: +$('setSize').value, fps: +$('setFps').value, bitrate: +$('setBitrate').value };
}
function writeSettings(s) {
  $('setSize').value = String(s.maxSize);
  $('setFps').value = String(s.fps);
  $('setBitrate').value = String(s.bitrate);
}
function matchPreset(s) {
  for (const [name, p] of Object.entries(PRESETS)) {
    if (p.maxSize === s.maxSize && p.fps === s.fps && p.bitrate === s.bitrate) return name;
  }
  return 'custom';
}
function applySettings() {
  const s = readSettings();
  $('preset').value = matchPreset(s);
  try { localStorage.setItem('videoSettings', JSON.stringify(s)); } catch (e) {}
  if (connected) {
    window.remote.cmd({ t: 'video', ...s });   // điện thoại dựng lại bộ nén ngay
    waitKey = true;
  }
}
try {
  const saved = JSON.parse(localStorage.getItem('videoSettings') || 'null');
  writeSettings(saved && typeof saved.fps === 'number' ? saved : PRESETS.sharp);
} catch (e) { writeSettings(PRESETS.sharp); }
$('preset').value = matchPreset(readSettings());
$('preset').addEventListener('change', () => {
  const p = PRESETS[$('preset').value];
  if (p) { writeSettings(p); applySettings(); }
});
['setSize', 'setFps', 'setBitrate'].forEach(id => $(id).addEventListener('change', applySettings));

// ---------------- Tự kết nối khi cắm cáp ----------------
// Chỉ tự nối khi có đúng một điện thoại sẵn sàng và người dùng chưa chủ động bấm Ngắt.
let userDisconnected = false;
let connecting = false;
async function autoConnectTick() {
  if (connected || connecting || userDisconnected) return;
  let list = [];
  try { list = await window.remote.devices(); } catch (e) { return; }
  const ready = list.filter(d => d.state === 'device');
  if (ready.length !== 1) { if (!list.length) await refreshDevices(); return; }
  await refreshDevices();
  devicesSel.value = ready[0].id;
  $('connect').click();
}
setInterval(autoConnectTick, 3000);

setState(false);
refreshDevices().then(autoConnectTick);
