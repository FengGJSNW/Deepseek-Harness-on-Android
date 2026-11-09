import fs from 'node:fs';
import path from 'node:path';
import { build } from 'esbuild';
const root = path.resolve(import.meta.dirname, '..');
const output = path.join(root, 'app/src/main/assets/math');
fs.mkdirSync(output, { recursive: true });
await build({
  entryPoints: [path.join(root, 'web/math.js')],
  outfile: path.join(output, 'math.js'),
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: 'chrome120',
  minify: true,
});
for (const file of ['math.html', 'math.css'])
  fs.copyFileSync(path.join(root, 'web', file), path.join(output, file));
fs.writeFileSync(
  path.join(output, 'katex.css'),
  fs
    .readFileSync(path.join(root, 'node_modules/katex/dist/katex.min.css'), 'utf8')
    .replaceAll('fonts/', ''),
);
for (const file of fs
  .readdirSync(path.join(root, 'node_modules/katex/dist/fonts'))
  .filter((file) => file.endsWith('.woff2')))
  fs.copyFileSync(path.join(root, 'node_modules/katex/dist/fonts', file), path.join(output, file));
fs.copyFileSync(
  path.join(root, 'node_modules/katex/LICENSE'),
  path.join(output, 'katex-LICENSE.txt'),
);
console.log('Packaged local KaTeX renderer and licensed fonts.');
