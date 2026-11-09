import fs from 'node:fs';
import path from 'node:path';
import { build } from 'esbuild';
import ts from 'typescript';
import { execFileSync } from 'node:child_process';
const root = path.resolve(import.meta.dirname, '..');
const upstream = path.join(root, 'upstream');
const pin = JSON.parse(fs.readFileSync(path.join(root, 'upstream.lock.json'), 'utf8'));
if (
  execFileSync('git', ['-C', upstream, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim() !==
  pin.commit
)
  throw new Error('Upstream revision differs from upstream.lock.json');
const config = ts.readConfigFile(path.join(upstream, 'tsconfig.base.json'), ts.sys.readFile).config;
const aliases = config.compilerOptions.paths;
const output = path.join(root, 'app/src/main/assets');
fs.mkdirSync(output, { recursive: true });
const resolver = {
  name: 'upstream-source',
  setup(b) {
    b.onResolve({ filter: /^@deepseek-ai\// }, (args) => {
      let target = aliases[args.path]?.[0];
      if (!target) {
        for (const [key, values] of Object.entries(aliases)) {
          if (!key.includes('*')) continue;
          const [prefix, suffix] = key.split('*');
          if (args.path.startsWith(prefix) && args.path.endsWith(suffix)) {
            target = values[0].replace(
              '*',
              args.path.slice(prefix.length, suffix ? -suffix.length : undefined),
            );
            break;
          }
        }
      }
      if (!target) throw new Error(`No source mapping for ${args.path}`);
      let file = path.resolve(upstream, target);
      if (fs.existsSync(file) && fs.statSync(file).isDirectory())
        file = path.join(file, 'index.ts');
      if (!fs.existsSync(file) && fs.existsSync(file + '.ts')) file += '.ts';
      return { path: file };
    });
    b.onResolve({ filter: /^node:/ }, (args) => {
      if (args.path === 'node:async_hooks')
        return {
          path: path.join(
            upstream,
            'packages/experimental/webworker-runtime/src/node/builtin_modules/implemented/async_hooks.ts',
          ),
        };
      if (args.path === 'node:buffer')
        return { path: path.join(root, 'node_modules/buffer/index.js') };
      if (['node:crypto', 'node:path', 'node:util/types', 'node:module'].includes(args.path))
        return { path: path.join(root, 'scripts/browser-node.ts') };
      throw new Error(`Unsupported Node dependency: ${args.path}`);
    });
  },
};
await build({
  entryPoints: [path.join(root, 'web/harness.ts')],
  outfile: path.join(output, 'harness.js'),
  bundle: true,
  format: 'iife',
  globalName: 'MobileHarness',
  target: 'chrome120',
  platform: 'browser',
  nodePaths: [path.join(root, 'node_modules')],
  plugins: [resolver],
  metafile: true,
  tsconfigRaw: {
    compilerOptions: { experimentalDecorators: false, useDefineForClassFields: true },
  },
  define: {
    'process.env.NODE_ENV': '"production"',
    'import.meta.url': '"https://appassets.androidplatform.net/assets/harness.js"',
  },
  logLevel: 'info',
}).then((result) =>
  fs.writeFileSync(
    path.join(root, 'build-inputs.json'),
    JSON.stringify(Object.keys(result.metafile.inputs), null, 2),
  ),
);
fs.copyFileSync(path.join(upstream, 'LICENSE'), path.join(output, 'HARNESS-LICENSE.txt'));
fs.copyFileSync(path.join(root, 'node_modules/zod/LICENSE'), path.join(output, 'ZOD-LICENSE.txt'));
