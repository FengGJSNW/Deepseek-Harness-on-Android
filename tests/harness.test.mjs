import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import { webcrypto } from 'node:crypto';

function load() {
  const sandbox = {
    console,
    crypto: webcrypto,
    setTimeout,
    clearTimeout,
    queueMicrotask,
    AbortController,
    AbortSignal,
    TextEncoder,
    TextDecoder,
    structuredClone,
    performance,
    URL,
  };
  vm.createContext(sandbox);
  // WebView 124 has Promise.withResolvers, but lacks resource-management symbols.
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
  return sandbox.MobileHarness;
}

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
  const harness = load();
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
  const h = load();
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
