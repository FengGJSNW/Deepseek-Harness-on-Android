import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { gunzipSync } from 'node:zlib';

const version = '1.19.33';
const assets = [
  ['arm64-v8a', 'arm64-v8', '2ddbd3aaa884f52b4568f406876ec81dddeef9f1bb67b90d4004d356e2c6a708'],
  ['x86_64', 'amd64', '14181cdc0c1e77d7a751ea851a93c2c086557af5cd69433004c9644f04eb9249'],
];
const output = resolve(process.argv[2]);
const cache = resolve('.tmp', 'mihomo', version);
mkdirSync(cache, { recursive: true });
for (const [abi, arch, sha256] of assets) {
  const name = `mihomo-android-${arch}-v${version}.gz`;
  const archive = join(cache, name);
  if (!existsSync(archive)) {
    execFileSync(
      'curl',
      [
        '--fail',
        '--location',
        '--retry',
        '2',
        '--max-time',
        '180',
        '--silent',
        '--show-error',
        `https://github.com/MetaCubeX/mihomo/releases/download/v${version}/${name}`,
        '--output',
        `${archive}.part`,
      ],
      { stdio: 'inherit' },
    );
    const digest = createHash('sha256')
      .update(readFileSync(`${archive}.part`))
      .digest('hex');
    if (digest !== sha256) throw new Error(`Mihomo hash mismatch: ${name}`);
    renameSync(`${archive}.part`, archive);
  }
  const bytes = readFileSync(archive);
  if (createHash('sha256').update(bytes).digest('hex') !== sha256)
    throw new Error(`Mihomo cache hash mismatch: ${name}`);
  const directory = join(output, abi);
  mkdirSync(directory, { recursive: true });
  writeFileSync(join(directory, 'libdsha_mihomo.so'), gunzipSync(bytes));
}
