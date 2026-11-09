/** Independent kernels share only the native permission boundary and explicit messages. */
export async function createTeam(create: any, rpc: any, notify: any, config: any) {
  const workers = new Map<string, any>();
  const max = Math.max(1, Math.min(8, config.maxAgents || 4));
  let closing = false;
  const owner = (worker: any, type: string, value: any) =>
    notify('agent_event', { id: worker.id, name: worker.name, parent: worker.parent, type, value });
  const start = async (worker: any) => {
    worker.status = 'running';
    owner(worker, 'status', 'running');
    try {
      while (worker.queue.length && !closing && !worker.stopped) {
        await worker.kernel.send(worker.queue.shift());
      }
      if (!worker.stopped) worker.status = worker.failed ? 'failed' : 'done';
    } catch (e: any) {
      worker.status = worker.stopped ? 'cancelled' : 'failed';
      owner(worker, 'error', e.message);
    } finally {
      owner(worker, 'status', worker.status);
    }
  };
  const delegate =
    (parent = '') =>
    async (method: string, args: any, signal: AbortSignal) => {
      signal?.throwIfAborted();
      if (closing) throw Error('任务已停止');
      if (
        parent ||
        config.isSubagent ||
        config.effort !== 'ultra' ||
        ['none', 'read'].includes(config.access)
      )
        throw Error('只有 Ultra 模式的主智能体可管理子智能体');
      if (method === 'spawn_agent') {
        if ([...workers.values()].filter((w) => w.status === 'running').length >= max)
          return { error: `最多同时运行 ${max} 个子智能体，请先收集现有结果。` };
        const id = crypto.randomUUID();
        const worker: any = {
          id,
          parent,
          name: String(args.name).slice(0, 40),
          queue: [args.task],
          result: '',
          status: 'running',
        };
        workers.set(id, worker);
        const model = ['deepseek-flash', 'deepseek-v4-pro'].includes(config.childModel)
          ? config.childModel
          : 'deepseek-flash';
        const effort = ['low', 'high', 'max'].includes(config.childEffort)
          ? config.childEffort
          : 'low';
        owner(worker, 'created', { task: args.task, model, effort });
        const childRpc = (method: string, args: any = {}, signal: any) =>
          ['begin', 'end', 'stop'].includes(method)
            ? Promise.resolve({})
            : rpc(method, { ...args, _agentId: id }, signal);
        try {
          worker.kernel = await create(
            childRpc,
            (type: string, value: any) => {
              if (type === 'assistant') worker.result += (worker.result ? '\n\n' : '') + value;
              if (type === 'turn_end' && value.reason?.kind === 'error') worker.failed = true;
              owner(worker, type, value);
            },
            {
              ...config,
              model,
              effort,
              isSubagent: true,
              seed: undefined,
              recoveredHistory: undefined,
              files: [],
              delegate: undefined,
            },
          );
        } catch (error: any) {
          worker.status = 'failed';
          owner(worker, 'error', error.message);
          throw error;
        }
        worker.done = start(worker);
        return { id, message: '子智能体已启动' };
      }
      const worker = workers.get(args.id);
      if (!worker || worker.parent !== parent) return { error: '子智能体不存在或不属于当前智能体' };
      if (method === 'send_agent') {
        if (worker.stopped) return { error: '子智能体已停止' };
        worker.queue.push(args.message);
        if (worker.status !== 'running') worker.done = start(worker);
        return { message: '说明已加入子智能体消息队列' };
      }
      if (method === 'stop_agent') {
        const stop = async (w: any) => {
          w.stopped = true;
          w.status = 'cancelled';
          w.queue = [];
          await Promise.all([...workers.values()].filter((c) => c.parent === w.id).map(stop));
          await w.kernel?.stop();
          owner(w, 'status', 'cancelled');
        };
        await stop(worker);
        return { message: '子智能体已停止' };
      }
      let onAbort: () => void;
      const abort = new Promise((_, reject) => {
        onAbort = () => reject(Error('等待已停止'));
        signal?.addEventListener('abort', onAbort, { once: true });
      });
      try {
        await Promise.race([worker.done, abort]);
      } finally {
        signal?.removeEventListener('abort', onAbort!);
      }
      return { id: worker.id, status: worker.status, result: worker.result };
    };
  const rootRpc = (method: string, args: any = {}, signal: any) =>
    method === 'end' ? Promise.resolve({}) : rpc(method, args, signal);
  const root = await create(rootRpc, notify, { ...config, delegate: delegate() });
  return {
    ...root,
    async send(text: string, images: any[]) {
      try {
        await root.send(text, images);
        // Never report the task complete while delegated work is still changing its files.
        while ([...workers.values()].some((w) => w.status === 'running')) {
          await Promise.all([...workers.values()].map((w) => w.done));
        }
      } finally {
        await rpc('end');
      }
    },
    async stopAgent(id: string) {
      const w = workers.get(id);
      if (w) return delegate(w.parent)('stop_agent', { id }, new AbortController().signal);
    },
    cancelFromNative() {
      closing = true;
      root.cancelFromNative();
      for (const w of workers.values()) {
        w.stopped = true;
        w.status = 'cancelled';
        w.kernel?.cancelFromNative();
      }
    },
    async dispose() {
      closing = true;
      for (const w of workers.values()) w.kernel?.cancelFromNative();
      await Promise.allSettled(
        [...workers.values()].map(async (w) => {
          await w.done;
          await w.kernel?.dispose();
        }),
      );
      await root.dispose();
    },
  };
}
