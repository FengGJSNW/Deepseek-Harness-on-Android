import fs from 'node:fs';
import path from 'node:path';
const root = path.resolve(import.meta.dirname, '..');
const target = path.join(root, 'app/src/main/assets/linux');
fs.mkdirSync(target, { recursive: true });
fs.writeFileSync(
  path.join(target, 'linux-worker.js'),
  fs.readFileSync(path.join(root, 'node_modules/v86/build/libv86.js'), 'utf8') +
    '\n' +
    fs.readFileSync(path.join(root, 'web/linux-worker.js'), 'utf8'),
);
for (const file of ['linux-page.js', 'linux.html'])
  fs.copyFileSync(path.join(root, 'web', file), path.join(target, file));
fs.copyFileSync(path.join(root, 'node_modules/v86/build/v86.wasm'), path.join(target, 'v86.wasm'));
fs.copyFileSync(path.join(root, 'node_modules/v86/LICENSE'), path.join(target, 'v86-LICENSE.txt'));
console.log(
  'Packaged v86 0.5.473 worker, WASM and license. Runtime distributions are verified downloads.',
);
