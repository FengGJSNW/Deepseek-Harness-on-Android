// Collect signed Alpine package closures against the same base used by the app.
// No Android build, emulator installation, host mount or privileged command is performed.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { V86 } from 'v86';

// v86 emits many small WebAssembly modules. Avoid the optimizing compiler's
// unbounded transient Zone allocation while the guest runs Node/npm probes.
if (!process.execArgv.includes('--liftoff-only')) {
  const child = spawnSync(
    process.execPath,
    ['--liftoff-only', '--max-old-space-size=2048', import.meta.filename],
    {
      stdio: 'inherit',
      env: process.env,
    },
  );
  if (child.error) throw child.error;
  process.exit(child.status ?? 1);
}
const root = path.resolve(import.meta.dirname, '..');
const target = path.join(root, '.tmp/sandbox-bundle/runtime-bundle');
const hash = (data) => createHash('sha256').update(data).digest('hex');
const recipe = hash(
  JSON.stringify({
    builder: hash(fs.readFileSync(import.meta.filename)),
    worker: hash(fs.readFileSync(path.join(root, 'web/linux-worker.js'))),
    helper: hash(fs.readFileSync(path.join(root, 'app/src/main/assets/linux/dsha-guest'))),
    engine: JSON.parse(fs.readFileSync(path.join(root, 'node_modules/v86/package.json'))).version,
  }),
);
const specs = [
  [
    'kernel.bin',
    'https://i.copy.sh/buildroot-bzimage68.bin',
    '507a759c70ab7a490a233be454d0b5b88bc667956a410b531cb4edc091e2eb1c',
    10068480,
    'buildroot-linux68.bin',
  ],
  [
    'seabios.bin',
    'https://copy.sh/v86/bios/seabios.bin',
    '73e3f359102e3a9982c35fce98eb7cd08f18303ac7f1ba6ebfbe6cdc1c244d98',
    131072,
    'seabios.bin',
  ],
  [
    'vgabios.bin',
    'https://copy.sh/v86/bios/vgabios.bin',
    'a4bc0d80cc3ca028c73dafa8fee396b8d054ce87ebd8abfbd31b06b437607880',
    36352,
    'vgabios.bin',
  ],
  [
    'alpine-3.24.3.tar.gz',
    'https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86/alpine-minirootfs-3.24.3-x86.tar.gz',
    'a73bd9ed20a5ed1503a0688957add539e10340c7ca1d9e8c06c34a76ddc5f715',
    3545963,
    'alpine-minirootfs-3.24.3-x86.tar.gz',
  ],
];
const free = fs.statfsSync(root);
if (free.bavail * free.bsize < 4 * 1024 ** 3)
  throw new Error('Free at least 4 GiB before preparing Sandbox images.');
fs.mkdirSync(target, { recursive: true });
const manifestFile = path.join(target, 'manifest.json');
if (fs.existsSync(manifestFile)) {
  const previous = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
  if (
    previous.recipe === recipe &&
    previous.files?.['tools/main/x86/APKINDEX.tar.gz'] &&
    Object.entries(previous.files).every(([name, spec]) => {
      const file = path.join(target, name);
      return (
        fs.existsSync(file) &&
        fs.statSync(file).size === spec.bytes &&
        hash(fs.readFileSync(file)) === spec.sha256
      );
    })
  ) {
    console.log('Verified existing Sandbox bundle.');
    process.exit(0);
  }
}
const files = {};
const sources = [];
function save(name, bytes, source) {
  if (!/^[a-zA-Z0-9._+~/\-]+$/.test(name) || name.split('/').includes('..'))
    throw new Error('Invalid bundle path');
  const file = path.join(target, name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file + '.part', bytes);
  fs.renameSync(file + '.part', file);
  files[name] = { bytes: bytes.length, sha256: hash(bytes), source };
}
async function download(url, maximum = 64 * 1024 ** 2) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await downloadOnce(url, maximum);
    } catch (error) {
      if (
        attempt >= 2 ||
        !(
          error instanceof TypeError ||
          error.name === 'TimeoutError' ||
          /Download failed: (429|5\d\d)/.test(error.message)
        )
      )
        throw error;
      console.log(`Retrying public package download (${attempt + 1}/2): ${url}`);
      await new Promise((resolve) => setTimeout(resolve, 1000 * (attempt + 1)));
    }
  }
}
async function downloadOnce(url, maximum) {
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(90000) });
  if (!response.ok) throw new Error(`Download failed: ${response.status} ${url}`);
  const parts = [];
  let count = 0;
  for await (const part of response.body) {
    count += part.length;
    if (count > maximum) throw new Error('Download exceeds limit');
    parts.push(part);
  }
  return Buffer.concat(parts);
}
for (const [name, url, sha, bytes, cached] of specs) {
  const old = [path.join(target, name), path.join(root, '.tmp/v080-research', cached)].find(
    (file) =>
      fs.existsSync(file) &&
      fs.statSync(file).size === bytes &&
      hash(fs.readFileSync(file)) === sha,
  );
  const data = old ? fs.readFileSync(old) : await download(url, bytes);
  if (data.length !== bytes || hash(data) !== sha)
    throw new Error(`Pinned checksum mismatch: ${name}`);
  save(name, data, url);
}

const pending = new Map();
let resolveBoot, rejectBoot, guest;
const boot = new Promise((resolve, reject) => {
  resolveBoot = resolve;
  rejectBoot = reject;
});
const ctx = vm.createContext({
  // Package downloads are performed by the host verifier; the builder guest has no network adapter.
  V86: class extends V86 {
    constructor(options) {
      super({ ...options, network_relay_url: '', net_device: undefined });
    }
  },
  console,
  TextEncoder,
  TextDecoder,
  WebAssembly,
  URL,
  Uint8Array,
  ArrayBuffer,
  setTimeout,
  clearTimeout,
  setInterval,
  clearInterval,
  queueMicrotask,
  btoa,
  atob,
  close() {},
  postMessage(message) {
    if (message.type === 'ready') resolveBoot(message);
    if (message.type === 'fault' && !pending.has(message.id)) rejectBoot(new Error(message.error));
    if (!['result', 'fault'].includes(message.type)) return;
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    clearTimeout(request.timer);
    message.type === 'fault' ? request.reject(new Error(message.error)) : request.resolve(message);
  },
});
ctx.self = ctx;
vm.runInContext(
  fs.readFileSync(path.join(root, 'web/linux-worker.js'), 'utf8') +
    '\n;globalThis.builderGuest = () => vm;',
  ctx,
);
function request(type, args = {}) {
  return new Promise((resolve, reject) => {
    const id = randomUUID();
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`Guest ${type} timed out`));
    }, 900000);
    pending.set(id, { resolve, reject, timer });
    ctx.onmessage({ data: { type, id, ...args } });
  });
}
async function execute(command, alpine = true) {
  const result = await request('exec', {
    command,
    root: alpine ? '/mnt/alpine' : '-',
    timeout: 840000,
    limit: 262144,
  });
  const stdout = Buffer.from(result.stdout, 'base64').toString();
  const stderr = Buffer.from(result.stderr, 'base64').toString();
  if (result.exitCode !== 0)
    throw new Error(`Guest command failed (${result.exitCode}): ${stdout}\n${stderr}`);
  return stdout;
}
async function write(path, bytes) {
  for (let offset = 0; offset < bytes.length || offset === 0; offset += 196608)
    await request('fs', {
      action: 'write',
      path,
      offset,
      data: bytes.subarray(offset, offset + 196608).toString('base64'),
    });
}
try {
  const read = (name) => {
    const b = fs.readFileSync(path.join(target, name));
    return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
  };
  ctx.onmessage({
    data: {
      type: 'boot',
      wasm: fs.readFileSync(path.join(root, 'node_modules/v86/build/v86.wasm')),
      bios: read('seabios.bin'),
      vgabios: read('vgabios.bin'),
      kernel: read('kernel.bin'),
      helper: fs.readFileSync(path.join(root, 'app/src/main/assets/linux/dsha-guest')),
      alpine: read('alpine-3.24.3.tar.gz'),
    },
  });
  await Promise.race([
    boot,
    new Promise((_, reject) => {
      const timer = setTimeout(() => reject(new Error('Guest boot timed out')), 120000);
      timer.unref();
    }),
  ]);
  guest = ctx.builderGuest();
  await execute(
    'set -e; gzip -dc /mnt/alpine.tar.gz | tar -xf - -C /mnt/alpine; mkdir -p /mnt/alpine/proc /mnt/alpine/dev /mnt/alpine/sys /mnt/alpine/workspace; mount -t proc proc /mnt/alpine/proc; mount --rbind /dev /mnt/alpine/dev; mount -t sysfs sysfs /mnt/alpine/sys',
    false,
  );
  const repository = '/var/cache/dsha-repo';
  for (const repo of ['main', 'community']) {
    const relative = `${repo}/x86/APKINDEX.tar.gz`;
    const url = `https://dl-cdn.alpinelinux.org/alpine/v3.24/${relative}`;
    const bytes = await download(url);
    save('tools/' + relative, bytes, url);
    sources.push({ url, bytes: bytes.length, sha256: hash(bytes) });
    await write(`/alpine${repository}/${relative}`, bytes);
  }
  await write(
    `/alpine${repository}/repositories`,
    Buffer.from(`${repository}/main\n${repository}/community\n`),
  );
  const options = `--no-network --repositories-file ${repository}/repositories`;
  const defaultGroups = ['bash git mc openssh-client curl', 'python3 py3-pip', 'nodejs npm'];
  for (const group of [...defaultGroups, 'build-base cmake', 'rust cargo', 'lua5.4']) {
    console.log('Resolving signed packages for ' + group);
    const plan = await execute(`apk ${options} fetch --recursive --url --simulate ${group}`);
    const paths = [
      ...new Set(
        [
          ...plan.matchAll(
            /\/var\/cache\/dsha-repo\/((?:main|community)\/x86\/[A-Za-z0-9._+~-]+\.apk)/g,
          ),
        ].map((m) => m[1]),
      ),
    ];
    if (!paths.length || paths.length > 128) throw new Error('Invalid signed package plan');
    const needsInstallation = defaultGroups.includes(group);
    for (const relative of paths) {
      const url = `https://dl-cdn.alpinelinux.org/alpine/v3.24/${relative}`;
      const name = 'tools/' + relative;
      if (!files[name]) save(name, await download(url, 128 * 1024 ** 2), url);
      if (needsInstallation) {
        await write(`/alpine${repository}/${relative}`, fs.readFileSync(path.join(target, name)));
      }
    }
    if (needsInstallation) {
      console.log(`Installing and verifying ${group}`);
      await execute(`apk ${options} add ${group}`);
      await execute(`rm -f ${repository}/main/x86/*.apk ${repository}/community/x86/*.apk`);
    }
  }
  const probes = await execute(
    "set -e; git --version; ssh -V; mc --version; python3 -c 'print(42)'; node -e 'console.log(42)'; npm --version",
  );
  console.log('Default tool probes passed; exporting the signed package collection');
  fs.writeFileSync(
    manifestFile + '.part',
    JSON.stringify(
      {
        schema: 1,
        recipe,
        builtAt: new Date().toISOString(),
        engine: 'v86-0.5.473',
        probes,
        sources,
        files,
      },
      null,
      2,
    ) + '\n',
  );
  fs.renameSync(manifestFile + '.part', manifestFile);
  console.log(`Sandbox bundle ready: ${target}`);
} finally {
  for (const item of pending.values()) clearTimeout(item.timer);
  await (guest || ctx.builderGuest())?.destroy();
}
