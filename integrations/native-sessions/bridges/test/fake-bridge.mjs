import { Peer } from '../rpc.mjs';
const peer = new Peer(process.stdin, process.stdout, {
  request: async (method, params) => {
    if (method === 'open') return { sessionId: 'fixture-native' };
    if (method === 'close' || method === 'interrupt') return {};
    if (params.prompt === 'crash') process.exit(23);
    if (params.prompt === 'malformed') { process.stdout.write('malformed\n'); return new Promise(() => {}); }
    if (params.prompt === 'callback') {
      peer.notify('native/event', { provider: 'fixture', event: { type: 'delta', text: 'before' } });
      const answer = await peer.request('native/request', { provider: 'fixture', method: 'native/approval', params: { reason: 'test', arbitrary: [false, 7] } });
      return { sessionId: 'fixture-native', text: JSON.stringify(answer) };
    }
    return { sessionId: 'fixture-native', text: params.prompt };
  },
});
