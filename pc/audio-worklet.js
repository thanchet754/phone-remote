// Phát PCM stereo 48kHz nhận từ điện thoại, giữ bộ đệm ngắn để độ trễ thấp
class PcmPlayer extends AudioWorkletProcessor {
  constructor() {
    super();
    this.size = 48000 * 2;            // tối đa 1 giây (mẫu xen kẽ L/R)
    this.buf = new Float32Array(this.size);
    this.read = 0;
    this.write = 0;
    this.count = 0;                   // số mẫu đang chờ
    this.playing = false;
    this.startAt = 48000 * 2 * 0.04;  // gom đủ 40 ms mới phát
    this.maxLag = 48000 * 2 * 0.16;   // dồn quá 160 ms thì bỏ bớt cho kịp hình
    this.port.onmessage = (e) => {
      const d = e.data;
      for (let i = 0; i < d.length; i++) {
        this.buf[this.write] = d[i];
        this.write = (this.write + 1) % this.size;
      }
      this.count += d.length;
      if (this.count > this.maxLag) {
        const drop = (this.count - this.startAt) & ~1;
        this.read = (this.read + drop) % this.size;
        this.count -= drop;
      }
    };
  }

  process(_inputs, outputs) {
    const out = outputs[0];
    const left = out[0];
    const right = out[1] || out[0];
    const need = left.length * 2;
    if (!this.playing && this.count >= this.startAt) this.playing = true;
    if (!this.playing || this.count < need) {
      this.playing = false;           // hết dữ liệu → im lặng, chờ gom lại
      return true;
    }
    for (let i = 0; i < left.length; i++) {
      left[i] = this.buf[this.read];
      right[i] = this.buf[(this.read + 1) % this.size];
      this.read = (this.read + 2) % this.size;
    }
    this.count -= need;
    return true;
  }
}
registerProcessor('pcm-player', PcmPlayer);
