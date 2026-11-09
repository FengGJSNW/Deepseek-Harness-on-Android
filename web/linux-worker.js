/* DSHA guest control. The guest receives no Android filesystem or arbitrary native-call bridge. */
let vm,
  boot = '',
  loggingIn = false,
  configured = false,
  ready = false,
  control = '';
const processes = new Map();
let pty = [],
  flushTimer,
  netSocket,
  rpcSequence = 0;
const rpc = new Map();
const emit = (message) => postMessage(message);
const quote = (s) => "'" + String(s).replaceAll("'", "'\\''") + "'";
const to64 = (bytes) => {
  let text = '';
  for (let i = 0; i < bytes.length; i += 8192)
    text += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(text);
};
const utf64 = (s) => to64(new TextEncoder().encode(s));
const from64 = (s) => Uint8Array.from(atob(s.replace(/\s/g, '')), (c) => c.charCodeAt(0));
const call = (type, fields) =>
  new Promise((resolve, reject) => {
    const id = ++rpcSequence;
    const timer = setTimeout(() => {
      rpc.delete(id);
      reject(new Error(type + ' timed out'));
    }, 30000);
    rpc.set(id, { resolve, reject, timer });
    emit({ type, id, ...fields });
  });
// This WebSocket is an in-process Wisp protocol adapter. No remote relay is used.
self.WebSocket = class {
  constructor() {
    netSocket = this;
    this.readyState = 1;
    queueMicrotask(() => {
      this.onopen?.({});
      emit({ type: 'networkOpen' });
    });
  }
  send(data) {
    emit({ type: 'network', data: to64(new Uint8Array(data)) });
  }
  close() {
    this.readyState = 3;
  }
  static OPEN = 1;
};
// DNS is the only HTTP request made by the emulator itself. Guest TCP uses Wisp.
self.fetch = async (url, options) => {
  if (url !== 'https://cloudflare-dns.com/dns-query' || options?.method !== 'POST')
    throw new Error('Direct worker fetch denied');
  const answer = await call('dns', { data: to64(new Uint8Array(options.body)) });
  return { arrayBuffer: async () => from64(answer.data).buffer };
};
function serial(byte) {
  const c = String.fromCharCode(byte);
  boot = (boot + c).slice(-262144);
  if (!ready) {
    if (!loggingIn && /login:\s*$/.test(boot)) {
      loggingIn = true;
      vm.serial0_send('root\n');
    }
    if (!configured && /(?:~|\/)\s*[#%]\s*$/.test(boot)) {
      configured = true;
      vm.serial0_send(
        'stty -echo; mkdir -p /workspace /dsha /dev/pts; mount -t devpts devpts /dev/pts 2>/dev/null; mount --bind /mnt/workspace /workspace; chmod +x /mnt/dsha-guest; /mnt/dsha-guest /dev/ttyS1 >/tmp/dsha-broker.log 2>&1 &\n',
      );
    }
  }
}
function daemonByte(byte) {
  control += String.fromCharCode(byte);
  if (control.length > 65536) throw new Error('Guest protocol frame too large');
  if (byte !== 10) return;
  const line = control.trim();
  control = '';
  if (line === 'S DSHA_GUEST_080') {
    ready = true;
    emit({ type: 'ready', boot: boot.slice(-14000), pty: true });
    return;
  }
  const [kind, id, encoded] = line.split(' ');
  const job = processes.get(id);
  if (!job) return;
  const data = from64(encoded || '');
  if (kind === 'A') {
    if (job.pty) emit({ type: 'result', id, started: true, pty: true });
    else emit({ type: 'processStarted', id });
  }
  if (kind === 'T') emit({ type: 'pty', id, data: encoded });
  if (kind === 'O' || kind === 'E') {
    const key = kind === 'O' ? 'out' : 'err';
    const remaining = job.limit - job.bytes;
    if (remaining > 0) job[key].push(data.subarray(0, remaining));
    job.bytes += data.length;
    if (remaining > 0)
      emit({
        type: 'processOutput',
        id,
        channel: key,
        text: job[key + 'Decoder'].decode(data.subarray(0, remaining), { stream: true }),
      });
  }
  if (kind === 'F') {
    clearTimeout(job.timer);
    processes.delete(id);
    emit({ type: 'fault', id, error: new TextDecoder().decode(data) });
  }
  if (kind === 'D') {
    clearTimeout(job.timer);
    processes.delete(id);
    const merge = (chunks) => {
      const all = new Uint8Array(chunks.reduce((n, a) => n + a.length, 0));
      let at = 0;
      for (const chunk of chunks) {
        all.set(chunk, at);
        at += chunk.length;
      }
      return to64(all);
    };
    emit({
      type: job.pty ? 'ptyExit' : 'result',
      id,
      stdout: merge(job.out),
      stderr: merge(job.err),
      exitCode: Number(new TextDecoder().decode(data)),
      truncated: job.bytes > job.limit,
    });
  }
}
function controlSend(text) {
  vm.serial_send_bytes(1, new TextEncoder().encode(text + '\n'));
}

async function handle(m) {
  switch (m.type) {
    case 'boot': {
      vm = new V86({
        wasm_fn: async (imports) =>
          (await WebAssembly.instantiate(m.wasm, imports)).instance.exports,
        bios: { buffer: m.bios },
        vga_bios: { buffer: m.vgabios },
        bzimage: { buffer: m.kernel },
        memory_size: 128 * 1024 * 1024,
        vga_memory_size: 1024 * 1024,
        cmdline: 'console=ttyS0 tsc=reliable random.trust_cpu=on',
        filesystem: {},
        uart1: true,
        preserve_mac_from_state_image: true,
        network_relay_url: 'wisp://native.dsha.invalid/',
        disable_keyboard: true,
        disable_mouse: true,
        disable_speaker: true,
        autostart: false,
      });
      vm.add_listener('serial0-output-byte', serial);
      vm.add_listener('serial1-output-byte', daemonByte);
      await new Promise((resolve) => vm.add_listener('emulator-loaded', resolve));
      if (m.state) {
        await vm.restore_state(m.state);
        await vm.run();
        ready = configured = true;
        emit({ type: 'ready', restored: true, pty: true });
      } else {
        await vm.create_file('/dsha-guest', new Uint8Array(m.helper));
        await vm.create_file('/alpine.tar.gz', new Uint8Array(m.alpine));
        vm.fs9p.CreateDirectory('workspace', 0);
        vm.fs9p.CreateDirectory('worktrees', 0);
        vm.fs9p.CreateDirectory('alpine', 0);
        await vm.run();
      }
      break;
    }
    case 'exec':
    case 'ptyOpen': {
      if (!ready || processes.size >= 16) throw new Error('Guest not ready or busy');
      if (!/^[a-f0-9-]{36}$/.test(m.id)) throw new Error('Invalid request ID');
      const job = {
        id: m.id,
        pty: m.type === 'ptyOpen',
        out: [],
        err: [],
        outDecoder: new TextDecoder(),
        errDecoder: new TextDecoder(),
        bytes: 0,
        limit: Math.max(1024, Math.min(m.limit || 65536, 262144)),
      };
      if (!job.pty)
        job.timer = setTimeout(() => {
          controlSend('K ' + m.id);
        }, m.timeout || 30000);
      processes.set(m.id, job);
      controlSend(
        (job.pty ? 'P ' : 'E ') +
          m.id +
          ' ' +
          [m.root || '-', m.tree || '-', m.cwd || '/workspace', m.command || 'sh']
            .map(utf64)
            .join(' ') +
          ' ' +
          (m.rows || 32) +
          ' ' +
          (m.cols || 90),
      );
      break;
    }
    case 'put': {
      if (!/^[A-Za-z0-9_.-]{1,120}$/.test(m.name)) throw new Error('Invalid exchange name');
      await vm.create_file('/' + m.name, from64(m.data));
      emit({ type: 'result', id: m.id, ok: true });
      break;
    }
    case 'get': {
      if (!/^[A-Za-z0-9_.-]{1,120}$/.test(m.name)) throw new Error('Invalid exchange name');
      emit({ type: 'result', id: m.id, data: to64(await vm.read_file('/' + m.name)) });
      break;
    }
    case 'fs': {
      const fs = vm.fs9p;
      const path = String(m.path || '');
      if (
        !path.startsWith('/') ||
        path.includes('\\') ||
        path.split('/').some((p) => p === '..' || p === '.') ||
        path.includes('\0')
      )
        throw new Error('Invalid guest filesystem path');
      const locate = () => fs.SearchPath(path);
      const ensureDir = (dir) => {
        let parent = 0;
        for (const name of dir.split('/').filter(Boolean)) {
          let child = fs.Search(parent, name);
          if (child === -1) child = fs.CreateDirectory(name, parent);
          if ((fs.GetInode(child).mode & 61440) !== 16384)
            throw new Error('Guest path parent is not a directory');
          parent = child;
        }
        return parent;
      };
      let result = {};
      if (m.action === 'mkdir') ensureDir(path);
      if (m.action === 'list') {
        const found = locate();
        if (found.id === -1) throw new Error('Guest directory does not exist');
        result.entries = (fs.read_dir(path) || []).map((name) => {
          const inode = fs.GetInode(fs.Search(found.id, name));
          return { name, mode: inode.mode, size: inode.size, link: inode.symlink || '' };
        });
      }
      if (m.action === 'read') {
        const found = locate();
        if (found.id === -1) throw new Error('Guest file does not exist');
        const inode = fs.GetInode(found.id);
        if ((inode.mode & 61440) !== 32768) throw new Error('Guest path is not a regular file');
        const length = Math.min(196608, Math.max(0, inode.size - m.offset));
        result = { data: to64(await fs.Read(found.id, m.offset, length)), size: inode.size };
      }
      if (m.action === 'write' || m.action === 'symlink') {
        const parent = ensureDir(path.slice(0, path.lastIndexOf('/'))),
          name = path.slice(path.lastIndexOf('/') + 1);
        if (!name) throw new Error('Cannot replace filesystem root');
        let id = fs.Search(parent, name);
        if (m.action === 'symlink') {
          if (id !== -1) fs.Unlink(parent, name);
          fs.CreateSymlink(name, parent, m.link);
        } else {
          if (m.offset === 0 && id !== -1) {
            fs.Unlink(parent, name);
            id = -1;
          }
          if (id === -1) id = fs.CreateFile(name, parent);
          const data = from64(m.data);
          if (data.length > 196608 || m.offset < 0) throw new Error('Invalid guest file chunk');
          fs.GetInode(id).mode = 32768 | (m.executable ? 493 : 420);
          await fs.Write(id, m.offset, data.length, data);
        }
      }
      emit({ type: 'result', id: m.id, ...result });
      break;
    }
    case 'diagnostics':
      emit({
        type: 'result',
        id: m.id,
        console: boot.slice(-18000),
        activeProcesses: processes.size,
      });
      break;
    case 'snapshot': {
      if (processes.size) throw new Error('Close active commands and PTYs before saving');
      await vm.stop();
      try {
        const state = new Uint8Array(await vm.save_state());
        for (let offset = 0; offset < state.length; offset += 196608)
          await call('stateChunk', { offset, data: to64(state.subarray(offset, offset + 196608)) });
        await call('stateCommit', { size: state.length });
        emit({ type: 'result', id: m.id, size: state.length });
      } finally {
        await vm.run();
      }
      break;
    }
    case 'ptyInput':
      controlSend('I ' + m.id + ' ' + m.data);
      break;
    case 'ptyResize':
      controlSend('R ' + m.id + ' ' + m.rows + ' ' + m.cols);
      break;
    case 'ptyClose':
      controlSend('K ' + m.id);
      break;
    case 'interrupt':
      for (const id of processes.keys()) controlSend('C ' + id);
      break;
    case 'network':
      netSocket?.onmessage?.({ data: from64(m.data).buffer });
      break;
    case 'rpc': {
      const item = rpc.get(m.id);
      if (!item) return;
      clearTimeout(item.timer);
      rpc.delete(m.id);
      m.error ? item.reject(new Error(m.error)) : item.resolve(m);
      break;
    }
    case 'destroy':
      await vm?.destroy();
      close();
      break;
  }
}
onmessage = (e) =>
  handle(e.data).catch((error) =>
    emit({ type: 'fault', id: e.data.id, error: String(error?.stack || error) }),
  );
