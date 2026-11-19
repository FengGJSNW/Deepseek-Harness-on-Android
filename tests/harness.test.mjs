import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import { webcrypto } from 'node:crypto';

function loadRealm({ legacy = false } = {}) {
  const sandbox = {
    console,
    crypto: webcrypto,
    setTimeout,
    clearTimeout,
    queueMicrotask,
    AbortController,
    AbortSignal,
    DOMException,
    Event,
    EventTarget,
    TextEncoder,
    TextDecoder,
    structuredClone,
    performance,
    URL,
  };
  vm.createContext(sandbox);
  if (legacy) {
    // Isolate host objects too: never delete methods on Node's own browser APIs.
    sandbox.crypto = { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) };
    vm.runInContext(
      `
      delete Promise.withResolvers;
      delete Object.hasOwn;
      delete globalThis.AggregateError;
      delete globalThis.structuredClone;
      for (const key of ['at', 'with', 'toSpliced', 'toReversed', 'findLast']) delete Array.prototype[key];
      globalThis.AbortSignal = class extends EventTarget { aborted = false; };
      globalThis.AbortController = class {
        signal = new AbortSignal();
        abort() {
          if (this.signal.aborted) return;
          this.signal.aborted = true;
          this.signal.dispatchEvent(new Event('abort'));
        }
      };`,
      sandbox,
    );
  }
  // Model WebViews without the resource-management symbols.
  vm.runInContext(
    `{
    const original = Symbol;
    function BrowserSymbol(value) { return original(value); }
    for (const key of Object.getOwnPropertyNames(original)) {
      if (!['dispose','asyncDispose','length','name','prototype'].includes(key)) Object.defineProperty(BrowserSymbol,key,Object.getOwnPropertyDescriptor(original,key));
    }
    globalThis.Symbol=BrowserSymbol;
  }`,
    sandbox,
  );
  vm.runInContext(fs.readFileSync('app/src/main/assets/harness.js', 'utf8'), sandbox);
  return sandbox;
}

function load(options) {
  return loadRealm(options).MobileHarness;
}

test('a legacy WebView without Promise.withResolvers or newer session APIs sends the first message once', async () => {
  const replies = [];
  let requests = 0;
  const agent = await load({ legacy: true }).create(
    async (method) => {
      if (method !== 'complete') return {};
      requests++;
      return { choices: [{ message: { content: 'received' }, finish_reason: 'stop' }] };
    },
    (type, value) => {
      if (type === 'assistant') replies.push(value);
    },
    { access: 'none' },
  );
  try {
    await agent.send('hello');
    assert.deepEqual(replies, ['received']);
    assert.equal(requests, 1);
  } finally {
    await agent.dispose();
  }
});

test('legacy cancellation keeps the first reason even when multiple owners cancel', () => {
  const realm = loadRealm({ legacy: true });
  const { AbortController: Controller, AbortSignal: Signal } = realm;
  const first = new Controller();
  const second = new Controller();
  const combined = Signal.any([first.signal, second.signal, first.signal]);
  const nested = Signal.any([Signal.any([combined])]);
  let notifications = 0;
  combined.addEventListener('abort', () => notifications++);
  const stop = Error('owner stopped this turn');
  second.abort(stop);
  first.abort(Error('later'));
  assert.equal(notifications, 1);
  assert.throws(
    () => combined.throwIfAborted(),
    (error) => error === stop,
  );
  assert.equal(Signal.any([second.signal]).reason, stop);
  assert.equal(nested.reason, stop);
  first.abort();
  assert.notEqual(first.signal.reason, stop);
  const defaultStop = new Controller();
  defaultStop.abort();
  assert.equal(defaultStop.signal.reason.name, 'AbortError');
});

test('legacy WebViews clone session snapshots faithfully and generate secure v4 IDs', () => {
  const realm = loadRealm({ legacy: true });
  const clone = vm.runInContext(
    `(() => {
    const record = { unchanged: undefined };
    record.self = record;
    const copy = structuredClone(record);
    return copy !== record && copy.self === copy && Object.hasOwn(copy, 'unchanged');
  })()`,
    realm,
  );
  assert.ok(clone);
  const id = realm.crypto.randomUUID();
  assert.match(id, /^[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/);
  assert.notEqual(realm.crypto.randomUUID(), id);
});

test('missing essential WebView capability fails before any bridge or provider request', async () => {
  const realm = loadRealm();
  vm.runInContext('delete globalThis.WeakRef', realm);
  let requests = 0;
  await assert.rejects(
    realm.MobileHarness.create(
      () => requests++,
      () => {},
    ),
    /Android System WebView/,
  );
  assert.equal(requests, 0);
});

test('chat-only removes all execution tools and terminal stays independently opt-in', async () => {
  for (const config of [
    {
      access: 'none',
      terminalEnabled: true,
      phoneTools: true,
      fileTools: true,
      terminalConfig: '{"level":"isolated"}',
    },
    { access: 'ask', terminalEnabled: false, phoneTools: false, fileTools: false },
    { access: 'auto', terminalEnabled: false, phoneTools: false, fileTools: false },
    {
      access: 'ask',
      terminalEnabled: true,
      phoneTools: false,
      fileTools: false,
      terminalConfig: '{"level":"isolated"}',
    },
  ]) {
    let names = [];
    const instance = await load().create(
      async (method, args) => {
        if (method === 'complete') {
          names = (args.tools || []).map((t) => t.function.name);
          return { choices: [{ message: { content: 'ready' }, finish_reason: 'stop' }] };
        }
        return {};
      },
      () => {},
      config,
    );
    try {
      await instance.send('test');
      assert.equal(
        names.includes('terminal_exec'),
        config.access !== 'none' && config.terminalEnabled,
      );
      assert.ok(!names.includes('observe'));
      assert.ok(!names.includes('workspace_write'));
      if (config.access === 'none') assert.equal(names.length, 0);
      if (config.access === 'auto') assert.ok(!names.includes('ask_user'));
    } finally {
      await instance.dispose();
    }
  }
});
test('terminal schema describes the selected backend and offers elevation only for restricted Sandbox', async () => {
  for (const [level, access, elevate] of [
    ['root', 'full', false],
    ['root', 'ask', false],
    ['shell', 'ask', false],
    ['sandbox', 'ask', true],
    ['sandbox', 'full', false],
    ['sandbox', 'auto', false],
  ]) {
    let functions;
    const instance = await load().create(
      async (method, args) => {
        if (method === 'complete') {
          functions = args.tools.map((tool) => tool.function);
          return { choices: [{ message: { content: 'ready' }, finish_reason: 'stop' }] };
        }
        return {};
      },
      () => {},
      { access, terminalEnabled: true, terminalConfig: JSON.stringify({ level }) },
    );
    try {
      await instance.send('test terminal tools');
      const exec = functions.find((tool) => tool.name === 'terminal_exec');
      assert.equal(exec.description.includes('/workspace'), level === 'sandbox');
      assert.equal(
        functions.some((tool) => tool.name === 'terminal_elevate'),
        elevate,
      );
      if (level === 'root') assert.match(exec.description, /Android 原生终端/);
    } finally {
      await instance.dispose();
    }
  }
});
test('forced Sandbox exposes only guest execution tools and routes explicit sandbox IDs', async () => {
  for (const sandboxOnly of [true, false]) {
    let functions;
    const instance = await load().create(
      async (method, args) => {
        if (method === 'complete') {
          functions = args.tools.map((tool) => tool.function);
          return { choices: [{ message: { content: 'ready' }, finish_reason: 'stop' }] };
        }
        return {};
      },
      () => {},
      {
        access: 'ask',
        terminalEnabled: true,
        phoneTools: true,
        fileTools: true,
        sandboxOnly,
        memoryEnabled: false,
        terminalConfig: JSON.stringify({ level: sandboxOnly ? 'sandbox' : 'app' }),
      },
    );
    try {
      await instance.send('Check available environment');
      const names = functions.map((tool) => tool.name);
      assert.ok(names.includes('sandbox_connect'));
      assert.ok(names.includes('workspace_read'));
      assert.ok(!names.includes('memory_read'));
      assert.equal(names.includes('observe'), !sandboxOnly);
      assert.equal(names.includes('mcp_call'), !sandboxOnly);
      assert.ok(!names.includes('terminal_elevate'));
      assert.equal(
        functions.find((tool) => tool.name === 'terminal_exec').parameters.properties.sandbox_id
          .type,
        'string',
      );
    } finally {
      await instance.dispose();
    }
  }
});
test('browser provider offers only the selected tools and sends official results back to the model', async () => {
  const local = ['browser_act', 'browser_open', 'browser_snapshot', 'browser_source'];
  for (const [options, expected] of [
    [{}, local],
    [{ browserProvider: 'official' }, ['browser_fetch', 'browser_search']],
    [{ browserProvider: 'both' }, [...local, 'browser_fetch', 'browser_search'].sort()],
    [{ browserProvider: 'blocked' }, []],
    [{ browserProvider: 'official', browserTools: false }, []],
    [{ browserProvider: 'official', access: 'none' }, []],
  ]) {
    let requested = false;
    let routed = 0;
    let query;
    let modelTools;
    let modelReceivedResult = false;
    const instance = await load().create(
      async (method, args) => {
        if (method === 'browser_search') {
          routed++;
          query = args.query;
          return { results: [{ title: 'official-web-test-result' }], untrusted: true };
        }
        if (method !== 'complete') return {};
        const names = Array.from(args.tools || [], (t) => t.function.name);
        modelTools = names.filter((n) => n.startsWith('browser_')).sort();
        if (expected.includes('browser_search')) {
          if (!requested) {
            requested = true;
            return {
              choices: [
                {
                  message: {
                    tool_calls: [
                      {
                        id: 'official-search',
                        function: {
                          name: 'browser_search',
                          arguments: '{"query":"DeepSeek 文档"}',
                        },
                      },
                    ],
                  },
                  finish_reason: 'tool_calls',
                },
              ],
            };
          }
          modelReceivedResult = args.messages.some(
            (m) =>
              m.role === 'tool' && JSON.stringify(m.content).includes('official-web-test-result'),
          );
        }
        return { choices: [{ message: { content: '完成' }, finish_reason: 'stop' }] };
      },
      () => {},
      { access: 'full', phoneTools: false, ...options },
    );
    try {
      await instance.send('核对网页');
      assert.deepEqual(modelTools, expected);
      assert.equal(routed, expected.includes('browser_search') ? 1 : 0);
      if (routed) {
        assert.equal(query, 'DeepSeek 文档');
        assert.ok(modelReceivedResult);
      }
    } finally {
      await instance.dispose();
    }
  }
});
test('Creator installs and calls a plugin during one turn; normal sessions can only call it', async () => {
  const manifest = {
    id: 'word-count',
    name: 'Word counter',
    version: '1.0.0',
    description: 'Count input words',
    tools: [{ name: 'count', description: 'Count input.text', code: 'return input.text.length;' }],
  };
  const methods = [];
  const inventory = [];
  const creatorEvents = [];
  let turn = 0;
  let creatorTools;
  let standardTools;
  let resultSeen = false;
  const response = (name, args) => ({
    choices: [
      {
        message: {
          tool_calls: [
            {
              id: 'call-' + turn,
              function: {
                name,
                arguments: JSON.stringify(args),
              },
            },
          ],
        },
        finish_reason: 'tool_calls',
      },
    ],
  });
  const creator = await load().create(
    async (method, args) => {
      if (method === 'plugin_manager') {
        methods.push(method);
        inventory.push(args.bundle);
        return { applied: true };
      }
      if (method === 'plugin_call') {
        methods.push(method);
        return { result: args.arguments.text.length, untrusted: true };
      }
      if (method === 'memory_context') return { plugins: inventory };
      if (method !== 'complete') return {};
      creatorTools = Array.from(args.tools || [], (tool) => tool.function.name);
      turn++;
      if (turn === 1)
        return response('plugin_manager', { action: 'install_bundle', bundle: manifest });
      if (turn === 2)
        return response('plugin_call', {
          plugin_id: manifest.id,
          tool: 'count',
          arguments: { text: 'abc' },
        });
      resultSeen = args.messages.some(
        (message) => message.role === 'tool' && message.content.includes('"result":3'),
      );
      return { choices: [{ message: { content: '安装并验证完成' }, finish_reason: 'stop' }] };
    },
    (type, data) => {
      if (type === 'tool_result' || type === 'turn_end') creatorEvents.push({ type, data });
    },
    { access: 'full', creatorMode: true, phoneTools: false, fileTools: false },
  );
  const standard = await load().create(
    async (method, args) => {
      if (method === 'complete') {
        standardTools = Array.from(args.tools, (tool) => tool.function.name);
        return { choices: [{ message: { content: '完成' }, finish_reason: 'stop' }] };
      }
      return {};
    },
    () => {},
    { access: 'full' },
  );
  try {
    await creator.send('写一个工具，安装并验证');
    await standard.send('查看可用工具');
    assert.deepEqual(methods, ['plugin_manager', 'plugin_call'], JSON.stringify(creatorEvents));
    assert.equal(inventory[0].tools[0].code, manifest.tools[0].code);
    assert.ok(creatorTools.includes('plugin_manager'));
    assert.ok(standardTools.includes('plugin_call'));
    assert.ok(!standardTools.includes('plugin_manager'));
    assert.ok(resultSeen);
  } finally {
    await creator.dispose();
    await standard.dispose();
  }
});
test('official kernel executes tool results then finishes a turn', async () => {
  const harness = load();
  const calls = [],
    events = [];
  let requests = 0;
  const rpc = async (method, args) => {
    calls.push(method);
    if (method === 'complete') {
      requests++;
      if (requests === 1)
        return {
          choices: [
            {
              message: {
                tool_calls: [{ id: 'c1', function: { name: 'observe', arguments: '{}' } }],
              },
              finish_reason: 'tool_calls',
            },
          ],
        };
      assert.ok(args.messages.some((m) => m.role === 'tool'));
      return { choices: [{ message: { content: '已读取手机界面' }, finish_reason: 'stop' }] };
    }
    return { message: '观察成功', package: 'com.android.settings', nodes: [] };
  };
  const instance = await harness.create(rpc, (type, data) => events.push({ type, data }));
  try {
    await instance.send('查看手机界面');
    assert.equal(requests, 2);
    assert.ok(calls.includes('observe'));
    assert.ok(events.some((x) => x.type === 'assistant' && x.data === '已读取手机界面'));
    assert.equal(calls.at(-1), 'end');
  } finally {
    await instance.dispose();
  }
});
test('cancellation aborts the live provider request and admits no later tool', async () => {
  const harness = load({ legacy: true });
  let started;
  const ready = new Promise((resolve) => (started = resolve));
  const methods = [];
  const instance = await harness.create(
    async (method, args, signal) => {
      methods.push(method);
      if (method === 'complete') {
        started();
        return new Promise((resolve, reject) =>
          signal.addEventListener('abort', () => reject(new Error('cancelled')), { once: true }),
        );
      }
      return {};
    },
    () => {},
  );
  try {
    const run = instance.send('开始任务');
    await ready;
    await instance.stop();
    await run;
    assert.equal(methods.filter((x) => x === 'complete').length, 1);
    assert.ok(methods.includes('stop'));
    assert.ok(!methods.includes('tap'));
  } finally {
    await instance.dispose();
  }
});
test('multiple tool replies remain adjacent before screenshot user content', async () => {
  const harness = load();
  const image = {
    type: 'image',
    attachment: { attachmentId: 'test-image', mediaType: 'image/jpeg' },
  };
  const wire = await harness.wireMessages(
    [
      {
        role: 'assistant',
        content: [
          { type: 'tool-call', id: 'a', name: 'observe', arguments: '{}' },
          { type: 'tool-call', id: 'b', name: 'list_apps', arguments: '{}' },
        ],
      },
      { role: 'tool', toolCallId: 'a', content: [{ type: 'text', text: 'screen' }, image] },
      { role: 'tool', toolCallId: 'b', content: [{ type: 'text', text: 'apps' }] },
    ],
    async () => 'aW1hZ2U=',
  );
  assert.equal(
    JSON.stringify(wire.map((x) => x.role)),
    JSON.stringify(['assistant', 'tool', 'tool', 'user']),
  );
  assert.equal(wire[3].content[1].image_url.url, 'data:image/jpeg;base64,aW1hZ2U=');
});

test('sessions restore complete canonical history without leaking another session', async () => {
  const h = load();
  let body;
  const rpc = async (method, args) => {
    if (method === 'complete') {
      body = args;
      return { choices: [{ message: { content: '只属于甲的回答' }, finish_reason: 'stop' }] };
    }
    return {};
  };
  const a = await h.create(rpc, () => {}, { sessionId: 'session-a', phoneTools: false });
  await a.send('甲的秘密口令：蓝鲸17');
  const seed = JSON.parse(JSON.stringify(a.snapshot()));
  assert.ok(seed.length > 3);
  assert.ok(seed.every((e, i) => e && e.seq === i));
  await a.dispose();
  const b = await h.create(rpc, () => {}, { sessionId: 'session-b', phoneTools: false });
  await b.send('乙的新对话');
  assert.ok(!JSON.stringify(body.messages).includes('蓝鲸17'));
  await b.dispose();
  const restored = await h.create(rpc, () => {}, {
    sessionId: 'session-a',
    seed,
    phoneTools: false,
  });
  await restored.send('记得我的口令吗？');
  assert.ok(JSON.stringify(body.messages).includes('蓝鲸17'));
  await restored.dispose();
});
test('thinking configuration and reasoning blocks survive tool roundtrip', async () => {
  const h = load();
  let n = 0;
  const agent = await h.create(
    async (method, args) => {
      if (method === 'complete') {
        assert.equal(args.model, 'deepseek-flash');
        assert.equal(args.thinking.type, 'enabled');
        assert.equal(args.reasoning_effort, 'low');
        if (n++ === 0)
          return {
            choices: [
              {
                message: {
                  reasoning_content: 'Think about the screen',
                  tool_calls: [
                    { id: 'think-tool', function: { name: 'observe', arguments: '{}' } },
                  ],
                },
                finish_reason: 'tool_calls',
              },
            ],
          };
        assert.ok(
          args.messages.some(
            (m) => m.role === 'assistant' && m.reasoning_content === 'Think about the screen',
          ),
        );
        return { choices: [{ message: { content: 'done' }, finish_reason: 'stop' }] };
      }
      return {};
    },
    () => {},
    { effort: 'low' },
  );
  await agent.send('看看');
  await agent.dispose();
});

test('UI effort aliases map to supported DeepSeek values', async () => {
  const h = load();
  for (const [effort, mapped] of [
    ['off', undefined],
    ['low', 'low'],
    ['medium', 'high'],
    ['high', 'high'],
    ['max', 'max'],
    ['ultra', 'max'],
  ]) {
    const agent = await h.create(
      async (method, args) => {
        if (method === 'complete') {
          assert.equal(args.thinking.type, effort === 'off' ? 'disabled' : 'enabled');
          assert.equal(args.reasoning_effort, mapped);
          return { choices: [{ message: { content: 'ok' }, finish_reason: 'stop' }] };
        }
        return {};
      },
      () => {},
      { effort },
    );
    try {
      await agent.send('test');
    } finally {
      await agent.dispose();
    }
  }
});

test('reasoning budget without a final answer reports an error instead of a silent success', async () => {
  const events = [];
  const agent = await load().create(
    async (method) =>
      method === 'complete'
        ? {
            choices: [
              {
                message: { content: null, reasoning_content: 'still thinking' },
                finish_reason: 'length',
              },
            ],
          }
        : {},
    (type, value) => events.push({ type, value }),
    { access: 'none', effort: 'max' },
  );
  try {
    await agent.send('test');
    const end = events.find((e) => e.type === 'turn_end');
    assert.equal(end.value.reason.kind, 'error');
    assert.match(end.value.reason.error.message, /输出预算/);
    assert.equal(
      events.some((e) => e.type === 'assistant'),
      false,
    );
  } finally {
    await agent.dispose();
  }
});

test('shared memories refresh every request without persisting stale context or leaking projects', async () => {
  const harness = load();
  let global = 'global-version-one';
  let body;
  const rpc = async (method, args) => {
    if (method === 'memory_context')
      return {
        global: { text: global },
        project: args.sessionId === 'a' ? { text: 'only-project-a' } : null,
      };
    if (method === 'attachment_context')
      return {
        files: [{ id: 'f', name: 'note.md', text: 'user-document-text', preview: 'dsha://file/f' }],
      };
    if (method === 'complete') {
      body = args;
      return { choices: [{ message: { content: '收到' }, finish_reason: 'stop' }] };
    }
    return {};
  };
  const a = await harness.create(rpc, () => {}, { sessionId: 'a', phoneTools: false });
  try {
    await a.send('first');
    assert.ok(JSON.stringify(body.messages).includes('global-version-one'));
    assert.ok(JSON.stringify(body.messages).includes('only-project-a'));
    assert.ok(JSON.stringify(body.messages).includes('user-document-text'));
    assert.ok(body.tools.some((t) => t.function.name === 'memory_write'));
    assert.ok(!JSON.stringify(a.snapshot()).includes('global-version-one'));
    global = 'global-version-two';
    await a.send('second');
    assert.ok(JSON.stringify(body.messages).includes('global-version-two'));
    assert.ok(!JSON.stringify(body.messages).includes('global-version-one'));
  } finally {
    await a.dispose();
  }
  const b = await harness.create(rpc, () => {}, { sessionId: 'b', phoneTools: false });
  try {
    await b.send('other');
    assert.ok(!JSON.stringify(body.messages).includes('only-project-a'));
  } finally {
    await b.dispose();
  }
});

test('attachment identifiers survive session restore without journaling extracted document text', async () => {
  const h = load();
  let request;
  const rpc = async (method, args) => {
    if (method === 'attachment_context')
      return {
        files: [
          {
            id: 'document-one',
            name: 'notes.md',
            mime: 'text/markdown',
            preview: 'dsha://file/document-one',
            text: 'temporary-extracted-document-text',
          },
        ],
      };
    if (method === 'memory_context') return {};
    if (method === 'complete') {
      request = args;
      return { choices: [{ message: { content: '收到附件' }, finish_reason: 'stop' }] };
    }
    return {};
  };
  const a = await h.create(rpc, () => {}, {
    sessionId: 'file-session',
    files: ['document-one'],
    phoneTools: false,
  });
  await a.send('请查看附件');
  const seed = JSON.parse(JSON.stringify(a.snapshot()));
  assert.ok(JSON.stringify(seed).includes('document-one'));
  assert.ok(JSON.stringify(seed).includes('notes.md'));
  assert.ok(!JSON.stringify(seed).includes('temporary-extracted-document-text'));
  await a.dispose();
  const restored = await h.create(rpc, () => {}, {
    sessionId: 'file-session',
    files: [],
    seed,
    phoneTools: false,
  });
  try {
    await restored.send('刚才那个文件叫什么？');
    assert.ok(JSON.stringify(request.messages).includes('notes.md'));
  } finally {
    await restored.dispose();
  }
});

test('restoring a restored session repeatedly never leaves seed-marker holes', async () => {
  const h = load({ legacy: true });
  const rpc = async (method) =>
    method === 'complete'
      ? { choices: [{ message: { content: 'continued' }, finish_reason: 'stop' }] }
      : {};
  let seed;
  for (let i = 0; i < 5; i++) {
    const kernel = await h.create(rpc, () => {}, {
      sessionId: 'repeat-seed',
      seed,
      access: 'none',
    });
    await kernel.send(`message-${i}`);
    seed = JSON.parse(JSON.stringify(kernel.snapshot()));
    assert.ok(seed.every((event, index) => event && event.seq === index));
    assert.ok(
      seed.some((e) => e.type === 'user/message' && JSON.stringify(e).includes('message-0')),
    );
    await kernel.dispose();
  }
});

test('a task continues beyond the previous forty-step limit', async () => {
  let count = 0;
  let answer = '';
  const kernel = await load().create(
    async (method) => {
      if (method !== 'complete') return {};
      count++;
      return {
        choices: [
          {
            message:
              count <= 43
                ? {
                    tool_calls: [
                      {
                        id: `step-${count}`,
                        function: { name: 'wait', arguments: '{"milliseconds":100}' },
                      },
                    ],
                  }
                : { content: 'all steps complete' },
            finish_reason: count <= 43 ? 'tool_calls' : 'stop',
          },
        ],
      };
    },
    (type, value) => {
      if (type === 'assistant') answer = value;
    },
  );
  try {
    await kernel.send('continue');
    assert.equal(count, 44);
    assert.equal(answer, 'all steps complete');
  } finally {
    await kernel.dispose();
  }
});

test('two delegated kernels work concurrently and finish before the shared native task ends', async () => {
  const h = load();
  let round = 0,
    begins = 0,
    ends = 0;
  const started = new Set(),
    releases = [];
  const events = [];
  const rpc = async (method, args) => {
    if (method === 'begin') {
      begins++;
      return {};
    }
    if (method === 'end') {
      ends++;
      return {};
    }
    if (method !== 'complete') return {};
    if (args._agentId) {
      assert.ok(!args.tools.some((t) => ['observe', 'spawn_agent'].includes(t.function.name)));
      assert.equal(args.model, 'deepseek-flash');
      assert.equal(args.reasoning_effort, 'low');
      started.add(args._agentId);
      const waiting = new Promise((resolve) => releases.push(resolve));
      if (started.size === 2) releases.forEach((resolve) => resolve());
      await waiting;
      return {
        choices: [
          { message: { content: `worker result ${args._agentId}` }, finish_reason: 'stop' },
        ],
      };
    }
    const tool = (name, data) => ({
      choices: [
        {
          message: {
            tool_calls: [
              { id: `parent-${round}`, function: { name, arguments: JSON.stringify(data) } },
            ],
          },
          finish_reason: 'tool_calls',
        },
      ],
    });
    round++;
    if (round <= 2)
      return tool('spawn_agent', { name: `worker-${round}`, task: `independent-${round}` });
    if (round <= 4) {
      const replies = args.messages
        .filter((m) => m.role === 'tool')
        .map((m) => {
          try {
            return JSON.parse(m.content);
          } catch {
            return {};
          }
        })
        .filter((v) => v.id);
      return tool('wait_agent', { id: replies[round - 3].id });
    }
    return { choices: [{ message: { content: 'collected' }, finish_reason: 'stop' }] };
  };
  const team = await h.createTeam(h.create, rpc, (type, value) => events.push({ type, value }), {
    sessionId: 'team-test',
    effort: 'ultra',
    childModel: 'deepseek-flash',
    childEffort: 'low',
    phoneTools: false,
  });
  try {
    await team.send('parallel', []);
    assert.equal(started.size, 2);
    assert.equal(begins, 1);
    assert.equal(ends, 1);
    assert.equal(
      events.filter(
        (e) => e.type === 'agent_event' && e.value.type === 'status' && e.value.value === 'done',
      ).length,
      2,
    );
  } finally {
    await team.dispose();
  }
});

test('legacy invalid envelopes recover readable history instead of failing the user turn', async () => {
  let options,
    attempts = 0,
    final;
  const history = [
    { role: 'user', content: 'earlier request' },
    { role: 'assistant', content: 'earlier reply' },
  ];
  const sandbox = {
    console,
    AbortController,
    Error,
    Map,
    window: null,
    addEventListener() {},
    MobileHarness: {
      create() {},
      async createTeam(_create, _rpc, _notify, config) {
        attempts++;
        if (config.seed) throw Error('seed event at index 11 has an invalid event envelope');
        options = config;
        return { send: async () => {}, snapshot: () => [], dispose: async () => {} };
      },
    },
  };
  sandbox.window = sandbox;
  sandbox.Android = {
    ui(type, value) {
      if (type === 'task_done') final = JSON.parse(value);
    },
    request(id, method) {
      const value =
        method === 'session_load'
          ? [null]
          : method === 'session_recover'
            ? history
            : method === 'attachment_refs'
              ? []
              : {};
      sandbox.nativeResolve({ id, ok: true, value });
    },
    cancel() {},
  };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync('app/src/main/assets/app.js', 'utf8'), sandbox);
  await sandbox.runTask({
    id: 'repair-task',
    sessionId: 'repair-session',
    text: 'new turn',
    images: [],
  });
  assert.equal(attempts, 2);
  assert.equal(options.seed, undefined);
  assert.deepEqual(options.recoveredHistory, history);
  assert.equal(final.failed, false);
});

test('only an Ultra root advertises delegation; child cannot spawn even with a delegate', async () => {
  for (const config of [
    { effort: 'low' },
    { effort: 'high' },
    { effort: 'max' },
    { effort: 'ultra', isSubagent: true },
    { effort: 'ultra', access: 'none' },
    { effort: 'ultra' },
  ]) {
    let body;
    const kernel = await load().create(
      async (method, args) => {
        if (method === 'complete') {
          body = args;
          return { choices: [{ message: { content: 'ok' }, finish_reason: 'stop' }] };
        }
        return {};
      },
      () => {},
      { ...config, phoneTools: false, delegate: async () => ({}) },
    );
    try {
      await kernel.send('test');
      const allowed = config.effort === 'ultra' && !config.isSubagent && config.access !== 'none';
      assert.equal(
        (body.tools || []).some((t) => t.function.name === 'spawn_agent'),
        allowed,
      );
      assert.equal(body.reasoning_effort, config.effort === 'ultra' ? 'max' : config.effort);
    } finally {
      await kernel.dispose();
    }
  }
});

test('three independent child results return to the Ultra root with selected settings', async () => {
  const h = load(),
    events = [],
    childBodies = [],
    childResults = ['2', '15', '0.75'];
  let rootRound = 0;
  const team = await h.createTeam(
    h.create,
    async (method, args) => {
      if (method !== 'complete') return {};
      if (args._agentId) {
        const result = childResults[childBodies.length];
        childBodies.push(args);
        return {
          choices: [
            {
              message: { reasoning_content: 'child reasoning', content: result },
              finish_reason: 'stop',
            },
          ],
        };
      }
      rootRound++;
      if (rootRound === 1)
        return {
          choices: [
            {
              message: {
                reasoning_content: 'delegate independent work',
                tool_calls: ['1+1', '3*5', '3/4'].map((task, i) => ({
                  id: 'spawn-' + i,
                  function: {
                    name: 'spawn_agent',
                    arguments: JSON.stringify({ name: 'worker-' + i, task }),
                  },
                })),
              },
              finish_reason: 'tool_calls',
            },
          ],
        };
      if (rootRound === 2) {
        const ids = args.messages
          .filter((m) => m.role === 'tool')
          .map((m) => JSON.parse(m.content).id);
        assert.equal(ids.length, 3);
        assert.equal(
          args.messages.find((m) => m.role === 'assistant').reasoning_content,
          'delegate independent work',
        );
        return {
          choices: [
            {
              message: {
                tool_calls: ids.map((id, i) => ({
                  id: 'wait-' + i,
                  function: { name: 'wait_agent', arguments: JSON.stringify({ id }) },
                })),
              },
              finish_reason: 'tool_calls',
            },
          ],
        };
      }
      const results = args.messages
        .filter((m) => m.role === 'tool')
        .map((m) => JSON.parse(m.content))
        .filter((m) => 'result' in m);
      assert.equal(results.length, 3);
      assert.deepEqual(Array.from(results, (m) => m.result).sort(), childResults.toSorted());
      return { choices: [{ message: { content: '2,15,0.75' }, finish_reason: 'stop' }] };
    },
    (type, value) => events.push({ type, value }),
    {
      sessionId: 'three-workers',
      effort: 'ultra',
      model: 'deepseek-flash',
      childModel: 'deepseek-v4-pro',
      childEffort: 'high',
      phoneTools: false,
    },
  );
  try {
    await team.send('delegate three', []);
    assert.equal(childBodies.length, 3);
    for (const body of childBodies) {
      assert.equal(body.model, 'deepseek-v4-pro');
      assert.equal(body.reasoning_effort, 'high');
      assert.ok(!body.tools.some((t) => t.function.name.endsWith('_agent')));
      assert.ok(!body.messages.some((m) => m.content === 'delegate three'));
    }
    assert.ok(
      !events.some((e) => e.type === 'turn_end' && e.value.reason?.kind === 'error'),
      JSON.stringify(events.filter((e) => e.type === 'turn_end')),
    );
    assert.equal(events.filter((e) => e.type === 'assistant').at(-1).value, '2,15,0.75');
    const tools = events.filter((e) => e.type === 'tool').map((e) => e.value.id);
    assert.equal(new Set(tools).size, 6);
    assert.deepEqual(
      events
        .filter((e) => e.type === 'tool_result')
        .map((e) => e.value.id)
        .toSorted(),
      tools.toSorted(),
    );
  } finally {
    await team.dispose();
  }
});

test('verified terminal identity refreshes native context and chat-only never requests it', async () => {
  let uid = 2000,
    body,
    statusCalls = 0;
  const rpc = async (method, args) => {
    if (method === 'terminal_status') {
      statusCalls++;
      return { connection: { connected: true, uid }, requesterUid: 10440 };
    }
    if (method === 'complete') {
      body = args;
      return { choices: [{ message: { content: '已核对' }, finish_reason: 'stop' }] };
    }
    return {};
  };
  const agent = await load().create(rpc, () => {}, {
    sessionId: 'terminal-session',
    terminalEnabled: true,
    terminalConfig: '{"level":"shell"}',
    access: 'full',
    phoneTools: false,
  });
  try {
    await agent.send('检查权限');
    assert.ok(
      body.messages.some((m) => typeof m.content === 'string' && m.content.includes('"uid":2000')),
    );
    assert.ok(body.tools.some((t) => t.function.name === 'terminal_status'));
    uid = 0;
    await agent.send('再次检查');
    assert.ok(
      body.messages.some((m) => typeof m.content === 'string' && m.content.includes('"uid":0')),
    );
    assert.ok(!JSON.stringify(agent.snapshot()).includes('requesterUid'));
  } finally {
    await agent.dispose();
  }
  const before = statusCalls;
  const chat = await load().create(rpc, () => {}, {
    sessionId: 'chat-only',
    terminalEnabled: true,
    terminalConfig: '{"level":"root"}',
    access: 'none',
    phoneTools: false,
  });
  try {
    await chat.send('你好');
    assert.equal(statusCalls, before);
    assert.equal((body.tools || []).length, 0);
  } finally {
    await chat.dispose();
  }
});

test('skills are discovered on demand and proxy management is opt-in', async () => {
  for (const [access, proxyTools] of [
    ['ask', true],
    ['ask', false],
    ['none', true],
  ]) {
    let request;
    const instance = await load().create(
      async (method, args) => {
        if (method === 'memory_context')
          return access === 'none'
            ? {}
            : {
                skills: [{ name: 'fixture-skill', description: 'fixture description' }],
              };
        if (method === 'complete') {
          request = args;
          return { choices: [{ message: { content: 'done' }, finish_reason: 'stop' }] };
        }
        return {};
      },
      () => {},
      { sessionId: 'skill-fixture', access, proxyTools },
    );
    try {
      await instance.send('hello');
      const tools = (request.tools || []).map(({ function: tool }) => tool.name);
      assert.equal(tools.includes('skill_read'), access !== 'none');
      assert.equal(tools.includes('proxy_control'), proxyTools && access !== 'none');
      assert.equal(JSON.stringify(request.messages).includes('fixture-skill'), access !== 'none');
      assert.ok(!JSON.stringify(instance.snapshot()).includes('fixture-skill'));
    } finally {
      await instance.dispose();
    }
  }
});

test('DSHA work bootstrap precedes completion and usage retains provider cache measurements', async () => {
  const events = [];
  const calls = [];
  const agent = await load().create(
    async (method, args) => {
      calls.push(method);
      if (method === 'memory_context')
        return {
          global: { scope: 'global', text: 'complete-global-memory-after-summary' },
          project: { scope: 'project', text: 'complete-project-memory-after-summary' },
          instructions: [{ scope: 'project', text: 'project-work-instruction' }],
          skills: [{ name: 'project-skill' }],
          hookContext: 'hook-without-session-summary',
        };
      if (method === 'complete') {
        const messages = JSON.stringify(args.messages);
        for (const required of [
          'Deepseek Harness on Android',
          '全部工具',
          'complete-global-memory-after-summary',
          'complete-project-memory-after-summary',
          'project-work-instruction',
          'project-skill',
          'hook-without-session-summary',
        ])
          assert.ok(messages.includes(required), required);
        assert.ok(calls.indexOf('memory_context') < calls.indexOf('complete'));
        return {
          choices: [{ message: { content: 'done' }, finish_reason: 'stop' }],
          usage: {
            prompt_tokens: 100,
            completion_tokens: 25,
            total_tokens: 125,
            prompt_cache_hit_tokens: 70,
          },
        };
      }
      return {};
    },
    (type, value) => events.push({ type, value }),
    { sessionId: 'work-bootstrap', access: 'ask' },
  );
  try {
    await agent.send('inspect this project');
    const usage = events.find((e) => e.type === 'usage').value;
    assert.equal(usage.prompt_cache_hit_tokens, 70);
    assert.equal(usage.prompt_tokens, 100);
    assert.equal(usage.context_budget, 1000000);
    assert.ok(!JSON.stringify(agent.snapshot()).includes('complete-global-memory-after-summary'));
  } finally {
    await agent.dispose();
  }
});

test('planning and review expose read-only project tools without mutation or delegation routes', async () => {
  for (const workMode of ['plan', 'review', 'unknown-mode']) {
    let names;
    const agent = await load().create(
      async (method, args) => {
        if (method === 'complete') {
          names = args.tools.map((t) => t.function.name);
          return { choices: [{ message: { content: '只读检查完成' }, finish_reason: 'stop' }] };
        }
        return {};
      },
      () => {},
      {
        workMode,
        access: 'full',
        effort: 'ultra',
        terminalEnabled: true,
        fileTools: true,
        phoneTools: true,
        browserProvider: 'both',
        creatorMode: true,
        delegate: async () => ({}),
      },
    );
    try {
      await agent.send('inspect');
      for (const name of [
        'workspace_context',
        'workspace_search',
        'workspace_read',
        'work_plan',
        'work_status',
        'worktree_review',
      ])
        assert.ok(names.includes(name), `${workMode}: ${name}`);
      for (const name of [
        'terminal_exec',
        'verification_run',
        'workspace_write',
        'workspace_edit',
        'memory_write',
        'mcp_call',
        'plugin_call',
        'plugin_manager',
        'spawn_agent',
        'worktree_merge',
        'worktree_discard',
        'tap',
        'browser_act',
        'browser_open',
      ])
        assert.ok(!names.includes(name), `${workMode}: blocked ${name}`);
    } finally {
      await agent.dispose();
    }
  }
});

test('workflow bootstrap loads project rules before first completion and refreshes plan state', async () => {
  const calls = [];
  let revision = 1;
  let completions = 0;
  let staticPrefix;
  const agent = await load().create(
    async (method, args) => {
      calls.push(method);
      if (method === 'work_status') return { plan: { revision, goal: 'current-plan' } };
      if (method === 'workspace_context')
        return { rules: [{ path: 'AGENTS.md', text: 'root-project-rules' }] };
      if (method === 'complete') {
        completions++;
        const first = JSON.stringify(args.messages[0]);
        if (staticPrefix) assert.equal(first, staticPrefix);
        staticPrefix = first;
        assert.ok(!first.includes('root-project-rules') && !first.includes('current-plan'));
        assert.equal(args.messages[0].role, 'system');
        const messages = JSON.stringify(args.messages);
        assert.ok(messages.includes('root-project-rules'));
        assert.ok(messages.includes(`\\"revision\\":${revision}`));
        return { choices: [{ message: { content: 'ready' }, finish_reason: 'stop' }] };
      }
      return {};
    },
    () => {},
    {
      workMode: 'execute',
      sessionId: 'workflow',
      access: 'ask',
      fileTools: true,
      workspace: 'test-workspace',
    },
  );
  try {
    await agent.send('first');
    revision = 2;
    await agent.send('continue');
    assert.equal(completions, 2);
    assert.equal(calls.filter((m) => m === 'workspace_context').length, 1);
    assert.equal(calls.filter((m) => m === 'work_status').length, 2);
    assert.ok(calls.indexOf('workspace_context') < calls.indexOf('complete'));
    assert.ok(!JSON.stringify(agent.snapshot()).includes('root-project-rules'));
  } finally {
    await agent.dispose();
  }
});

test('execution workflow respects disabled tools and Sandbox catalog boundaries', async () => {
  for (const sandboxOnly of [false, true]) {
    let names;
    let schemas;
    const agent = await load().create(
      async (method, args) => {
        if (method === 'complete') {
          names = args.tools.map((t) => t.function.name);
          schemas = args.tools;
          return { choices: [{ message: { content: 'done' }, finish_reason: 'stop' }] };
        }
        return {};
      },
      () => {},
      {
        workMode: 'execute',
        access: 'full',
        terminalEnabled: true,
        fileTools: true,
        phoneTools: false,
        sandboxOnly,
      },
    );
    try {
      await agent.send('test');
      for (const name of ['work_status', 'work_plan', 'workspace_edit', 'verification_run'])
        assert.ok(names.includes(name));
      const verify = schemas.find((t) => t.function.name === 'verification_run').function
        .parameters;
      assert.ok(verify.properties.timeout_seconds && verify.properties.sandbox_id);
      const read = schemas.find((t) => t.function.name === 'workspace_read').function.parameters;
      assert.ok(read.properties.start_line && read.properties.line_count);
      if (sandboxOnly)
        for (const name of ['memory_write', 'plugin_call', 'mcp_call', 'tap'])
          assert.ok(!names.includes(name));
    } finally {
      await agent.dispose();
    }
  }
});

test('a read-only team rejects direct delegation even if a caller bypasses the advertised catalog', async () => {
  let delegate;
  const create = async (_rpc, _notify, config) => {
    delegate = config.delegate;
    return { send: async () => {}, dispose: async () => {}, cancelFromNative() {} };
  };
  const team = await load().createTeam(
    create,
    async () => ({}),
    () => {},
    { workMode: 'plan', effort: 'ultra', access: 'full' },
  );
  try {
    await assert.rejects(
      delegate('spawn_agent', { name: 'child', task: 'edit' }, new AbortController().signal),
    );
  } finally {
    await team.dispose();
  }
});

test('image-only turns retain both images without fabricating user text, including restored history', async () => {
  const harness = load();
  const requests = [];
  const rpc = async (method, args) => {
    if (method === 'image') return 'aW1hZ2U=';
    if (method === 'complete') {
      requests.push(args);
      return { choices: [{ message: { content: '看到了两张图片' }, finish_reason: 'stop' }] };
    }
    return {};
  };
  const config = {
    sessionId: 'image-only',
    model: 'deepseek-flash',
    access: 'none',
    phoneTools: false,
  };
  const agent = await harness.create(rpc, () => {}, config);
  let seed;
  try {
    await agent.send('', [
      { attachmentId: 'first-photo', mediaType: 'image/jpeg' },
      { attachmentId: 'second-photo', mediaType: 'image/png' },
    ]);
    const imageMessage = requests[0].messages.find(
      (message) =>
        message.role === 'user' &&
        Array.isArray(message.content) &&
        message.content.some((part) => part.type === 'image_url'),
    );
    assert.equal(imageMessage.content.length, 2);
    assert.ok(imageMessage.content.every((part) => part.type === 'image_url'));
    assert.ok(
      JSON.stringify(requests[0].messages.filter((message) => message.role === 'system')).includes(
        '默认识图在后台完成',
      ),
    );
    seed = JSON.parse(JSON.stringify(agent.snapshot()));
    assert.ok(!JSON.stringify(seed).includes('请描述这些图片'));
    const userEvents = seed.filter((event) => event.type === 'user/message');
    assert.equal(userEvents.length, 1);
    assert.ok(!JSON.stringify(userEvents).includes('默认识图在后台完成'));
  } finally {
    await agent.dispose();
  }
  const restored = await harness.create(rpc, () => {}, { ...config, seed });
  try {
    await restored.send('比较两张图片');
    const images = requests
      .at(-1)
      .messages.flatMap((message) => (Array.isArray(message.content) ? message.content : []))
      .filter((part) => part.type === 'image_url');
    assert.equal(images.length, 2);
    assert.ok(JSON.stringify(requests.at(-1).messages).includes('比较两张图片'));
  } finally {
    await restored.dispose();
  }
});

test('background image recognition persists separate image text and reuses cached results', async () => {
  const harness = load();
  const stored = new Map();
  const events = [];
  let recognitions = 0;
  const rpc = async (method, args) => {
    if (method === 'image') return 'aW1hZ2U=';
    if (method === 'image_recognition') {
      if (args.action === 'pending')
        return {
          pending: args.ids
            .filter((id) => !stored.has(id))
            .map((id) => ({ id, sha256: 'a'.repeat(64) })),
        };
      for (const entry of args.entries) stored.set(entry.id, entry.text);
      return { saved: args.entries.length };
    }
    if (method === 'complete' && args.response_format?.type === 'json_object') {
      recognitions++;
      return {
        usage: { total_tokens: 40 },
        choices: [
          {
            message: {
              content: JSON.stringify({
                images: [
                  { id: 'photo-a', description: '蓝色公交站牌', text: '人民路 48 路' },
                  { id: 'photo-b', description: '绿色出口指示牌', text: '停车场出口 C7' },
                  { id: 'foreign-photo', description: '不可保存到其他图片', text: 'wrong' },
                ],
              }),
            },
          },
        ],
      };
    }
    if (method === 'complete')
      return { choices: [{ message: { content: '收到图片' }, finish_reason: 'stop' }] };
    return {};
  };
  const agent = await harness.create(rpc, (type, value) => events.push({ type, value }), {
    id: 'image-index-task',
    sessionId: 'indexed-images',
    access: 'none',
    phoneTools: false,
  });
  try {
    const images = ['photo-a', 'photo-b'].map((attachmentId) => ({
      attachmentId,
      mediaType: 'image/jpeg',
    }));
    await agent.send('', images);
    assert.match(stored.get('photo-a'), /人民路 48 路/);
    assert.match(stored.get('photo-b'), /停车场出口 C7/);
    assert.equal(stored.size, 2);
    assert.equal(events.filter((event) => event.type === 'image_index_usage').length, 1);
    assert.ok(!JSON.stringify(agent.snapshot()).includes('停车场出口 C7'));
    await agent.send('继续', images);
    assert.equal(recognitions, 1);
  } finally {
    await agent.dispose();
  }
});

test('stopping cancels background image recognition and never saves a cancelled result', async () => {
  const harness = load();
  let started;
  const ready = new Promise((resolve) => {
    started = resolve;
  });
  let saves = 0;
  let aborted = false;
  const rpc = async (method, args, signal) => {
    if (method === 'image') return 'aW1hZ2U=';
    if (method === 'image_recognition') {
      if (args.action === 'pending') return { pending: [{ id: 'photo', sha256: 'b'.repeat(64) }] };
      saves++;
      return {};
    }
    if (method === 'complete' && args.response_format?.type === 'json_object') {
      started();
      return new Promise((_, reject) =>
        signal.addEventListener(
          'abort',
          () => {
            aborted = true;
            reject(Error('cancelled'));
          },
          { once: true },
        ),
      );
    }
    if (method === 'complete')
      return { choices: [{ message: { content: '收到' }, finish_reason: 'stop' }] };
    return {};
  };
  const agent = await harness.create(rpc, () => {}, {
    id: 'cancel-index',
    sessionId: 'cancel-index',
    access: 'none',
  });
  try {
    const run = agent.send('', [{ attachmentId: 'photo', mediaType: 'image/jpeg' }]);
    await ready;
    agent.cancelFromNative();
    await run;
    assert.equal(aborted, true);
    assert.equal(saves, 0);
  } finally {
    await agent.dispose();
  }
});
