import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const pin = JSON.parse(readFileSync(resolve(root, 'upstream.lock.json'), 'utf8'));
const directory = resolve(root, 'upstream');
const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
if (!existsSync(directory)) {
  git('clone', '--depth', '1', '--filter=blob:none', '--no-checkout', pin.repository, directory);
  git('-C', directory, 'fetch', '--depth', '1', 'origin', pin.commit);
  git('-C', directory, 'checkout', '--detach', pin.commit);
}
if (git('-C', directory, 'rev-parse', 'HEAD') !== pin.commit)
  throw new Error(
    'Existing upstream differs from upstream.lock.json; resolve it explicitly. No reset was performed.',
  );
if (git('-C', directory, 'status', '--porcelain', '--untracked-files=no'))
  throw new Error(
    'Upstream contains local changes. Keep platform adaptations in web/; no files were reset.',
  );
console.log(`Pinned upstream ready: ${pin.commit}`);
