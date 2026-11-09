import fs from 'node:fs';
import path from 'node:path';
import { build } from 'esbuild';
const root = path.resolve(import.meta.dirname, '..');
const output = path.join(root, 'app/src/main/assets/console');
fs.mkdirSync(output, { recursive: true });
await build({
  entryPoints: [path.join(root, 'web/console.js')],
  outfile: path.join(output, 'console.js'),
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: 'chrome120',
  minify: true,
});
for (const file of ['console.html', 'console.css'])
  fs.copyFileSync(path.join(root, 'web', file), path.join(output, file));
fs.copyFileSync(
  path.join(root, 'node_modules/@xterm/xterm/css/xterm.css'),
  path.join(output, 'xterm.css'),
);
for (const [pkg, name] of [
  ['xterm', 'xterm'],
  ['addon-fit', 'fit'],
])
  fs.copyFileSync(
    path.join(root, `node_modules/@xterm/${pkg}/LICENSE`),
    path.join(output, name + '-LICENSE.txt'),
  );
console.log('Packaged local xterm console, fit addon and licenses.');
