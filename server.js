// Phone Remote - điều khiển điện thoại Android từ PC qua ADB (USB hoặc Wi-Fi)
// Chạy: node server.js  → mở http://127.0.0.1:5179
const http = require('http');
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');

const PORT = 5179;
const LOCAL_ADB = path.join(__dirname, 'platform-tools', 'adb.exe');
const ADB = fs.existsSync(LOCAL_ADB) ? LOCAL_ADB : 'adb';

function adb(args, opts = {}) {
  return new Promise((resolve, reject) => {
    execFile(ADB, args, {
      encoding: opts.binary ? 'buffer' : 'utf8',
      maxBuffer: 64 * 1024 * 1024,
      timeout: opts.timeout || 15000,
      windowsHide: true
    }, (err, stdout, stderr) => {
      if (err) return reject(new Error((stderr && stderr.toString().trim()) || err.message));
      resolve(stdout);
    });
  });
}

// Bọc chuỗi an toàn cho shell trên điện thoại
const shq = (s) => "'" + String(s).replace(/'/g, "'\\''") + "'";
const int = (v) => {
  const n = Math.round(Number(v));
  if (!Number.isFinite(n)) throw new Error('Số không hợp lệ');
  return String(n);
};

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > 1e5) req.destroy(); });
    req.on('end', () => {
      try { resolve(data ? JSON.parse(data) : {}); } catch (e) { reject(new Error('JSON không hợp lệ')); }
    });
    req.on('error', reject);
  });
}

const routes = {
  'GET /api/devices': async () => {
    const out = await adb(['devices']);
    const devices = out.split(/\r?\n/).slice(1)
      .map(l => l.trim().split(/\s+/))
      .filter(p => p.length >= 2)
      .map(([id, state]) => ({ id, state }));
    return { devices };
  },
  'POST /api/tap': async (b) => {
    await adb(['shell', 'input', 'tap', int(b.x), int(b.y)]);
    return { ok: true };
  },
  'POST /api/swipe': async (b) => {
    const ms = Math.max(50, Math.min(5000, Number(b.ms) || 300));
    await adb(['shell', 'input', 'swipe', int(b.x1), int(b.y1), int(b.x2), int(b.y2), int(ms)]);
    return { ok: true };
  },
  'POST /api/key': async (b) => {
    await adb(['shell', 'input', 'keyevent', int(b.code)]);
    return { ok: true };
  },
  'POST /api/text': async (b) => {
    const text = String(b.text || '');
    if (!text) return { ok: true };
    if (/[^\x20-\x7e]/.test(text)) {
      throw new Error('Lệnh "input text" của Android chỉ nhận ký tự không dấu (ASCII). Chữ có dấu hãy gõ bằng bàn phím trên điện thoại.');
    }
    // "input text" dùng %s cho dấu cách
    await adb(['shell', 'input', 'text', shq(text.replace(/ /g, '%s'))]);
    return { ok: true };
  },
  'POST /api/connect': async (b) => {
    const host = String(b.host || '').trim();
    if (!/^[\w.\-]+(:\d{1,5})?$/.test(host)) throw new Error('Địa chỉ không hợp lệ (ví dụ 192.168.1.20:5555)');
    return { result: (await adb(['connect', host])).trim() };
  }
};

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  // Chỉ nhận yêu cầu từ chính trang này (chặn trang web khác gọi vào)
  const origin = req.headers.origin;
  if (origin && origin !== `http://127.0.0.1:${PORT}` && origin !== `http://localhost:${PORT}`) {
    res.writeHead(403);
    return res.end('Forbidden');
  }
  try {
    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      return res.end(fs.readFileSync(path.join(__dirname, 'index.html')));
    }
    if (req.method === 'GET' && url.pathname === '/frame') {
      const png = await adb(['exec-out', 'screencap', '-p'], { binary: true });
      res.writeHead(200, { 'content-type': 'image/png', 'cache-control': 'no-store' });
      return res.end(png);
    }
    const handler = routes[`${req.method} ${url.pathname}`];
    if (!handler) {
      res.writeHead(404);
      return res.end('Not found');
    }
    const body = req.method === 'POST' ? await readBody(req) : {};
    const result = await handler(body);
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(result));
  } catch (e) {
    res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: e.message }));
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Phone Remote đang chạy: http://127.0.0.1:${PORT}`);
  console.log(`ADB: ${ADB}`);
});
