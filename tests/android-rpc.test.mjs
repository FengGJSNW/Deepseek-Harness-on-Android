import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../app/src/main/assets/app.js', import.meta.url), 'utf8');

function page(request, cancel, kernel = { create() {}, createTeam() {} }) {
  const events = [];
  const vmPage = vm.createContext({
    addEventListener() {},
    MobileHarness: kernel,
    events,
    Android: {
      ui(type) {
        events.push(type);
      },
      request(id, method, args) {
        if (method === 'status') vmPage.nativeResolve({ id, ok: true, value: {} });
        else request(id, method, args);
      },
      cancel,
    },
  });
  vmPage.window = vmPage;
  vm.runInContext(source, vmPage);
  return vmPage;
}

test('a failed kernel load cannot advertise a ready engine', async () => {
  const broken = page(null, null, null);
  const healthy = page(null, null);
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(broken.events, ['engine_unavailable']);
  assert.ok(!broken.appReady);
  assert.ok(healthy.appReady);
  assert.deepEqual(healthy.events, ['status', 'ready']);
});

test('a bridge dispatch exception releases its abort listener and is never replayed', async () => {
  let attempts = 0;
  let cancels = 0;
  const p = page(
    () => {
      attempts++;
      throw Error('bridge detached');
    },
    () => cancels++,
  );
  const controller = new AbortController();
  await assert.rejects(p.rpc('tool', {}, controller.signal), /bridge detached/);
  controller.abort();
  assert.equal(attempts, 1);
  assert.equal(cancels, 0);
});

test('loss of the native cancellation channel rejects locally and ignores a late tool response', async () => {
  const ids = [];
  const p = page(
    (id) => ids.push(id),
    () => {
      throw Error('bridge detached');
    },
  );
  const controller = new AbortController();
  const response = p.rpc('tool', {}, controller.signal);
  controller.abort();
  await assert.rejects(response, /未能确认设备端操作已取消/);
  assert.equal(ids.length, 1);
  p.nativeResolve({ id: ids[0], ok: true, value: 'late' });
});
