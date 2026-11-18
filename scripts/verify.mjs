import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

// One local/CI entry point. This script never installs, signs with a new key, or publishes.
const root = resolve(import.meta.dirname, '..');
process.chdir(root);
const flags = new Set(process.argv.slice(2));
if (
  [...flags].some((flag) => !['--release', '--web'].includes(flag)) ||
  (flags.has('--release') && flags.has('--web'))
)
  throw new Error('Usage: node scripts/verify.mjs [--release | --web]');
const release = flags.has('--release');
const gradle = readFileSync('app/build.gradle.kts', 'utf8');
const version = /versionName\s*=\s*"([^"]+)"/.exec(gradle)?.[1];
const versionCode = Number(/versionCode\s*=\s*(\d+)/.exec(gradle)?.[1]);
if (!version || !versionCode) throw new Error('Missing Android version');
const output = join(root, 'qa', `v${version.replaceAll('.', '')}`);
mkdirSync(output, { recursive: true });
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();

function run(command, args) {
  let executable = command;
  let parameters = args;
  if (process.platform === 'win32' && /\.(bat|cmd)$/i.test(command)) {
    const quote = (text) => `'${text.replaceAll("'", "''")}'`;
    executable = 'powershell.exe';
    parameters = [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      `& ${[command, ...args].map(quote).join(' ')}; exit $LASTEXITCODE`,
    ];
  }
  const result = spawnSync(executable, parameters, {
    cwd: root,
    stdio: 'inherit',
    env: process.env,
  });
  if (result.error || result.status !== 0)
    throw new Error(
      `${command} failed (${result.status}): ${result.error?.message || 'see output'}`,
    );
}

function testCounts(files) {
  const counts = { tests: 0, failures: 0, errors: 0, skipped: 0 };
  for (const file of files) {
    const xml = readFileSync(file, 'utf8');
    counts.tests += [...xml.matchAll(/<testcase\b/g)].length;
    counts.failures += [...xml.matchAll(/<failure\b/g)].length;
    counts.errors += [...xml.matchAll(/<error\b/g)].length;
    counts.skipped += [...xml.matchAll(/<skipped\b/g)].length;
  }
  if (!counts.tests || counts.failures || counts.errors)
    throw new Error(`Invalid test reports: ${JSON.stringify(counts)}`);
  return counts;
}

const startedAt = new Date().toISOString();
// A failed verification must replace an older successful manifest, never leave it looking current.
const manifestPath = join(output, release ? 'release-manifest.json' : 'verification-manifest.json');
writeFileSync(
  manifestPath,
  JSON.stringify({ status: 'running', version, versionCode, startedAt }, null, 2),
);
try {
  run(process.execPath, ['scripts/setup-upstream.mjs']);
  for (const file of [
    'build-harness.mjs',
    'build-linux.mjs',
    'build-console.mjs',
    'build-math.mjs',
  ])
    run(process.execPath, [`scripts/${file}`]);
  const nodeReport = join(output, 'node-tests.xml');
  run(process.execPath, [
    '--test',
    '--test-reporter=spec',
    '--test-reporter=junit',
    '--test-reporter-destination=stdout',
    `--test-reporter-destination=${nodeReport}`,
    ...readdirSync('tests')
      .filter((name) => name.endsWith('.test.mjs'))
      .sort()
      .map((name) => `tests/${name}`),
  ]);
  const nodeTests = testCounts([nodeReport]);
  const apks = [];
  let unitTests;
  let lint;
  if (!flags.has('--web')) {
    run(process.execPath, ['scripts/verify-flclash.mjs']);
    const tasks = [
      ':flclash-common:testDebugUnitTest',
      ':flclash-service:testDebugUnitTest',
      ':flclash-native:testDebugUnitTest',
      ':app:testDebugUnitTest',
      ':app:assembleDebug',
      ':app:assembleDebugAndroidTest',
      ':app:lintDebug',
    ];
    if (release) tasks.push(':app:assembleRelease');
    if (process.platform === 'win32') run(join(root, 'gradlew.bat'), [...tasks, '--console=plain']);
    else run('sh', ['./gradlew', ...tasks, '--console=plain']);
    const testDir = 'app/build/test-results/testDebugUnitTest';
    unitTests = testCounts(
      readdirSync(testDir)
        .filter((name) => name.endsWith('.xml'))
        .map((name) => join(testDir, name)),
    );
    const lintXml = readFileSync('app/build/reports/lint-results-debug.xml', 'utf8');
    lint = {
      errors: [...lintXml.matchAll(/severity="(?:Error|Fatal)"/g)].length,
      warnings: [...lintXml.matchAll(/severity="Warning"/g)].length,
    };
    if (lint.errors) throw new Error('Lint contains errors');
    for (const type of release ? ['debug', 'release'] : ['debug']) {
      const dir = join('app/build/outputs/apk', type);
      const metadata = JSON.parse(readFileSync(join(dir, 'output-metadata.json'), 'utf8'));
      if (metadata.applicationId !== 'dev.deepseek.mobile')
        throw new Error('Unexpected application ID');
      for (const item of metadata.elements) {
        if (item.versionName !== version || item.versionCode !== versionCode)
          throw new Error('APK version mismatch');
        const file = join(dir, item.outputFile);
        apks.push({
          type,
          file,
          bytes: readFileSync(file).length,
          sha256: hash(readFileSync(file)),
        });
      }
    }
    if (release) {
      const local = existsSync('local.properties') ? readFileSync('local.properties', 'utf8') : '';
      const localSdk = /^sdk\.dir=(.+)$/m
        .exec(local)?.[1]
        ?.trim()
        .replace(/\\([\\:= ])/g, '$1');
      const sdk = localSdk || process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
      if (!sdk) throw new Error('Android SDK required for signature verification');
      const builds = join(sdk, 'build-tools');
      const signer = readdirSync(builds)
        .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))
        .map((name) =>
          join(builds, name, process.platform === 'win32' ? 'apksigner.bat' : 'apksigner'),
        )
        .find(existsSync);
      if (!signer) throw new Error('apksigner not found');
      for (const apk of apks.filter((apk) => apk.type === 'release')) {
        run(signer, ['verify', '--verbose', apk.file]);
        apk.signatureVerified = true;
      }
    }
  }
  const files = git('ls-files', '-c', '-o', '--exclude-standard', '-z')
    .split('\0')
    .filter(Boolean)
    .sort();
  const fingerprint = createHash('sha256');
  for (const file of new Set(files)) {
    if (existsSync(file))
      fingerprint
        .update(file)
        .update('\0')
        .update(hash(readFileSync(file)))
        .update('\0');
  }
  const manifest = {
    status: 'passed',
    version,
    versionCode,
    startedAt,
    completedAt: new Date().toISOString(),
    node: process.version,
    head: git('rev-parse', 'HEAD'),
    dirty: !!git('status', '--porcelain'),
    sourceFingerprint: fingerprint.digest('hex'),
    upstream: JSON.parse(readFileSync('upstream.lock.json', 'utf8')).commit,
    flclash: flags.has('--web')
      ? undefined
      : JSON.parse(
          readFileSync('.tmp/flclash-module/build/host/outputs/repo/dsha-build.json', 'utf8'),
        ),
    nodeTests,
    unitTests,
    lint,
    apks,
    androidUiTests: flags.has('--web') ? 'not compiled' : 'compiled, not run',
    installed: false,
  };
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
  console.log(`Verification passed: ${manifestPath}`);
} catch (error) {
  writeFileSync(
    manifestPath,
    JSON.stringify(
      {
        status: 'failed',
        version,
        versionCode,
        startedAt,
        failedAt: new Date().toISOString(),
        error: String(error.message),
      },
      null,
      2,
    ) + '\n',
  );
  throw error;
}
