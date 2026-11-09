'use strict';
const pending = new Map();
let sequence = 0,
  kernel = null,
  currentTask = null;
function publish(type, value) {
  Android.ui(type, JSON.stringify(value));
}
function rpc(method, args = {}, signal) {
  if (signal?.aborted) return Promise.reject(Error('任务已停止'));
  const id = `r${++sequence}`;
  return new Promise((resolve, reject) => {
    const abort = () => {
      Android.cancel(id);
      pending.delete(id);
      reject(Error('任务已停止'));
    };
    pending.set(id, {
      resolve,
      reject,
      cleanup: () => signal?.removeEventListener('abort', abort),
    });
    signal?.addEventListener('abort', abort, { once: true });
    Android.request(id, method, JSON.stringify(args));
  });
}
window.nativeResolve = (r) => {
  const p = pending.get(r.id);
  if (!p) return;
  pending.delete(r.id);
  p.cleanup();
  r.ok ? p.resolve(r.value) : p.reject(Error(r.error));
};
function event(type, value) {
  if (currentTask) publish('task_event', { taskId: currentTask.id, type, value });
}
window.nativeEvent = (type, value) => event(type, value);
window.stopFromNative = () => kernel?.cancelFromNative();
window.stopChild = (id) => kernel?.stopAgent(id);
window.refreshStatus = async () => {
  const s = await rpc('status');
  publish('status', s);
  return s;
};
window.runTask = async (task) => {
  if (currentTask) {
    publish('error', '执行器忙碌');
    return;
  }
  currentTask = task;
  let failed = false;
  try {
    const seed = await rpc('session_load', { sessionId: task.sessionId });
    const makeKernel = (options) =>
      MobileHarness.createTeam(
        MobileHarness.create,
        rpc,
        (type, value) => {
          if (type === 'turn_end' && value.reason?.kind === 'error') failed = true;
          event(type, value);
        },
        options,
      );
    try {
      kernel = await makeKernel({ ...task, seed: seed.length ? seed : undefined });
    } catch (error) {
      if (!seed.length || !/seed|surface|contiguous|session event/i.test(error.message))
        throw error;
      const history = await rpc('session_recover', { sessionId: task.sessionId });
      kernel = await makeKernel({ ...task, recoveredHistory: history });
    }
    const images = await rpc('attachment_refs', { ids: task.images });
    await kernel.send(task.text, images);
    await rpc('session_save', { sessionId: task.sessionId, events: kernel.snapshot() });
  } catch (e) {
    failed = true;
    event('error', e.message);
  } finally {
    try {
      await kernel?.dispose();
    } catch {}
    kernel = null;
    const id = task.id;
    currentTask = null;
    publish('task_done', { taskId: id, failed });
  }
};
window.addEventListener('error', (e) => publish('error', '运行错误：' + e.message));
window.addEventListener('unhandledrejection', (e) => {
  e.preventDefault();
  publish('error', e.reason?.message || '操作失败');
});
(async () => {
  await refreshStatus();
  window.appReady = true;
  publish('ready', true);
})();
