import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PassThrough } from 'node:stream';
import { Peer, deferred } from '../rpc.mjs';
import { CodexSession } from '../codex.mjs';
import { ClaudeSession } from '../claude.mjs';
import { OpenCodeSession } from '../opencode.mjs';

test('bidirectional native requests preserve permission payloads and answers', async t => {
  const upstream = new PassThrough(), downstream = new PassThrough();
  const payload = { permissions: { file_system: { write: ['/fixture'] } }, reason: 'native reason' };
  const answer = { permissions: {}, scope: 'turn' };
  let caller;
  const host = new Peer(upstream, downstream, { request: (method, params) => {
    assert.equal(method, 'native/request'); assert.deepEqual(params, payload); return answer;
  } });
  const native = new Peer(downstream, upstream, { request: async () => {
    caller = await native.request('native/request', payload); return caller;
  } });
  t.after(() => { host.finish(); native.finish(); upstream.destroy(); downstream.destroy(); });
  assert.deepEqual(await host.request('turn'), answer);
  assert.deepEqual(caller, answer);
});

test('RPC responses correlate by ID even when requests finish in reverse order', async t => {
  const a = new PassThrough(), b = new PassThrough();
  const delayed = deferred();
  const host = new Peer(a, b, { request: () => {} });
  const remote = new Peer(b, a, { request: method => method === 'slow' ? delayed.promise : 'fast' });
  t.after(() => { host.finish(); remote.finish(); a.destroy(); b.destroy(); });
  const slow = host.request('slow');
  assert.equal(await host.request('fast'), 'fast');
  delayed.resolve('slow'); assert.equal(await slow, 'slow');
});

test('EOF rejects all pending requests and future sends', async () => {
  const input = new PassThrough(), output = new PassThrough();
  const peer = new Peer(input, output, { request: () => {} });
  const first = peer.request('one'), second = peer.request('two');
  input.end();
  await assert.rejects(first, /transport ended/); await assert.rejects(second, /transport ended/);
  await assert.rejects(peer.request('three'), /closed/);
  assert.equal(peer.pending.size, 0);
  output.destroy();
});

test('malformed JSON fails the transport instead of being treated as model output', async () => {
  const input = new PassThrough(), output = new PassThrough();
  const peer = new Peer(input, output, { request: () => {} });
  const result = peer.request('one'); input.write('invalid\n');
  await assert.rejects(result, SyntaxError); input.destroy(); output.destroy();
});

test('Codex handles completion before turn/start acknowledgement, filters unrelated events', async () => {
  const session = new CodexSession(() => {}, () => {});
  session.sessionId = 'thread'; session.options = {};
  const ack = deferred(); session.peer = { request: () => ack.promise };
  const result = session.turn('task');
  session.notification('turn/started', { threadId: 'thread', turn: { id: 'turn' } });
  session.notification('item/completed', { threadId: 'other', item: { type: 'agentMessage', text: 'wrong' } });
  session.notification('item/completed', { threadId: 'thread', turnId: 'wrong', item: { type: 'agentMessage', text: 'wrong' } });
  session.notification('item/completed', { threadId: 'thread', turnId: 'turn', item: { type: 'agentMessage', text: '42' } });
  session.notification('turn/completed', { threadId: 'thread', turn: { id: 'turn', status: 'completed' } });
  ack.resolve({ turn: { id: 'turn' } });
  assert.equal((await result).text, '42'); assert.equal(session.active, null);
});

test('Codex failed turn rejects instead of returning partial content', async () => {
  const session = new CodexSession(() => {}, () => {});
  session.sessionId = 'thread'; session.options = {};
  session.peer = { request: async () => ({ turn: { id: 'turn' } }) };
  const result = session.turn('task');
  session.notification('turn/completed', { threadId: 'thread', turn: { id: 'turn', status: 'failed', error: 'quota' } });
  await assert.rejects(result, /quota/);
});

test('Claude drains native events and returns only a successful result', async () => {
  const events = [], session = new ClaudeSession(() => {}, message => events.push(message));
  const turn = deferred(); session.active = turn;
  session.q = (async function* () {
    yield { type: 'stream_event', session_id: 'claude-session', event: { delta: 'partial' } };
    yield { type: 'result', subtype: 'success', session_id: 'claude-session', result: '42' };
  })();
  await session.drain();
  assert.deepEqual(await turn.promise, { sessionId: 'claude-session', text: '42', nativeStatus: 'success' });
  assert.equal(events.length, 2);
  await assert.rejects(session.turn('after EOF'), /stream ended/);
});

test('Claude error result and premature EOF both reject the active turn', async () => {
  for (const message of [null, { type: 'result', subtype: 'error_max_turns', is_error: true }]) {
    const session = new ClaudeSession(() => {}, () => {}), turn = deferred(); session.active = turn;
    session.q = (async function* () { if (message) yield message; })();
    await session.drain(); await assert.rejects(turn.promise);
  }
});

test('OpenCode keeps explicit provider/model choice and rejects native error responses', async () => {
  const session = new OpenCodeSession(() => {}, () => {});
  session.options = { model: 'provider/model/submodel', cwd: '/fixture' }; session.sessionId = 'session'; session.streamFailure = deferred();
  session.client = { session: { prompt: async input => {
    assert.deepEqual(input.model, { providerID: 'provider', modelID: 'model/submodel' });
    return { data: { info: {}, parts: [{ type: 'reasoning', text: 'hidden' }, { type: 'text', text: '42' }] } };
  } } };
  assert.equal((await session.turn('task')).text, '42');
  session.client.session.prompt = async () => ({ data: { info: { error: { message: 'quota' } } } });
  await assert.rejects(session.turn('task'), /quota/);
});

test('OpenCode forwards a native permission answer without making its own decision', async () => {
  const properties = { id: 'approval', sessionID: 'session', permission: 'edit', patterns: ['file'] };
  const session = new OpenCodeSession(async (method, input) => {
    assert.equal(method, 'permission.asked'); assert.deepEqual(input, properties); return { response: 'reject' };
  }, () => {});
  session.options = { cwd: '/fixture' }; session.sessionId = 'session'; session.controller = new AbortController(); session.streamFailure = deferred();
  let sent;
  session.client = { permission: { respond: async input => { sent = input; } } };
  await session.drain((async function* () { yield { type: 'permission.asked', properties }; })());
  assert.deepEqual(sent, { sessionID: 'session', permissionID: 'approval', directory: '/fixture', response: 'reject' });
  await assert.rejects(session.streamFailure.promise, /stream ended/);
});
