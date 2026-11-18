import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const source = join(root, 'integrations/flclash');
const repository = join(root, '.tmp/flclash-module/build/host/outputs/repo');
const manifest = join(repository, 'dsha-build.json');
const lock = JSON.parse(readFileSync(join(root, 'integrations/flclash.lock.json')));
const git = (cwd, ...args) => execFileSync('git', ['-C', cwd, ...args]);
const sha = (value) => createHash('sha256').update(value).digest('hex');
const revision = (cwd) => git(cwd, 'rev-parse', 'HEAD').toString().trim();
if (revision(source) !== lock.commit) throw new Error('FlClash pin mismatch');
for (const [directory, commit] of Object.entries(lock.submodules)) {
  const path = join(source, directory);
  if (revision(path) !== commit || git(path, 'status', '--porcelain').length)
    throw new Error(`FlClash submodule changed: ${directory}`);
}
git(source, 'apply', '--reverse', '--check', join(root, 'integrations/flclash.patch'));

const fingerprint = createHash('sha256');
const files = new Set(
  git(source, 'ls-files', '-c', '-o', '--exclude-standard', '-z')
    .toString()
    .split('\0')
    .filter(Boolean),
);
for (const file of [...files].sort()) {
  const path = join(source, file);
  if (statSync(path).isFile())
    fingerprint
      .update(file)
      .update('\0')
      .update(sha(readFileSync(path)));
}
for (const file of [
  'integrations/flclash.lock.json',
  'integrations/flclash.patch',
  'scripts/build-flclash.ps1',
  'scripts/flclash-maven.init.gradle',
  'scripts/verify-flclash.mjs',
])
  fingerprint
    .update(file)
    .update('\0')
    .update(sha(readFileSync(join(root, file))));
const sourceHash = fingerprint.digest('hex');

function artifacts(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return artifacts(path);
    if (!/\.(aar|pom)$/.test(entry.name)) return [];
    return [
      { file: relative(repository, path).replaceAll('\\', '/'), sha256: sha(readFileSync(path)) },
    ];
  });
}
const built = artifacts(repository).sort((a, b) => a.file.localeCompare(b.file));
if (!built.some((item) => item.file.endsWith('/flutter_release-1.0.aar')))
  throw new Error('Embedded Flutter AAR is missing');
if (process.argv[2] === 'record') {
  writeFileSync(
    manifest,
    JSON.stringify({ commit: lock.commit, sourceHash, artifacts: built }, null, 2) + '\n',
  );
  console.log('Recorded embedded FlClash build inputs and AAR hashes');
} else {
  const recorded = JSON.parse(readFileSync(manifest));
  if (
    recorded.sourceHash !== sourceHash ||
    JSON.stringify(recorded.artifacts) !== JSON.stringify(built)
  )
    throw new Error('FlClash source or artifacts changed; run scripts/build-flclash.ps1 again');
  console.log(`Verified embedded FlClash ${lock.version}: ${built.length} artifacts`);
}
