import { Peer } from './rpc.mjs';

let session;
const provider = process.argv[2];
const peer = new Peer(process.stdin, process.stdout, {
  request: async (method, params) => {
    if (method === 'open') {
      if (session) throw new Error('Session already opened');
      const Class = provider === 'codex' ? (await import('./codex.mjs')).CodexSession
        : provider === 'claude' ? (await import('./claude.mjs')).ClaudeSession
        : provider === 'opencode' ? (await import('./opencode.mjs')).OpenCodeSession
        : null;
      if (!Class) throw new Error(`Unknown provider: ${provider}`);
      session = new Class((method, params) => peer.request('native/request', { provider, method, params }),
        event => peer.notify('native/event', { provider, event }));
      return session.open(params);
    }
    if (!session) throw new Error('Open a native session first');
    if (method === 'turn') return session.turn(params.prompt);
    if (method === 'interrupt') { await session.interrupt(); return {}; }
    if (method === 'close') { await session.close(); return {}; }
    throw new Error(`Unknown method: ${method}`);
  },
  exit: () => { void session?.close().finally(() => process.exit(0)); },
});
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => peer.finish());
