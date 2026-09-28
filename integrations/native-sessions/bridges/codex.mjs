import { spawn } from 'node:child_process';
import { Peer, deferred } from './rpc.mjs';

/** Official App Server protocol; no transcript scraping or model API emulation. */
export class CodexSession {
  constructor(callback, event) { this.callback = callback; this.event = event; }
  async open(options) {
    this.options = options;
    this.child = spawn(process.env.KOOG_CODEX_CLI || 'codex', ['app-server', '--stdio'], {
      cwd: options.cwd, stdio: ['pipe', 'pipe', 'pipe'],
    });
    this.child.stderr.pipe(process.stderr);
    this.peer = new Peer(this.child.stdout, this.child.stdin, {
      request: (method, params) => this.callback(method, params),
      notification: (method, params) => this.notification(method, params),
      exit: error => this.active?.reject(error),
    });
    this.child.on('error', error => this.peer.finish(error));
    this.child.on('exit', () => this.peer.finish());
    await this.peer.request('initialize', {
      clientInfo: { name: 'koog_native_prototype', version: '0.1' },
      capabilities: { experimentalApi: true },
    });
    this.peer.notify('initialized', {});
    const account = await this.peer.request('account/read', { refreshToken: false });
    const models = await this.peer.request('model/list', {});
    const params = { ...options.native, cwd: options.cwd, ...(options.model ? { model: options.model } : {}) };
    if (!options.sessionId && options.tools?.length) {
      params.dynamicTools = options.tools.map(tool => ({ type: 'function', ...tool }));
    }
    const response = options.sessionId
      ? await this.peer.request('thread/resume', { ...params, threadId: options.sessionId })
      : await this.peer.request('thread/start', params);
    this.sessionId = response.thread.id;
    return { sessionId: this.sessionId, model: response.model, version: response.thread.cliVersion,
      authType: account.account?.type ?? null, models: models.data.map(model => model.model) };
  }
  notification(method, params) {
    this.event({ method, params });
    const turn = this.active;
    if (!turn || params.threadId !== this.sessionId) return;
    if (method === 'turn/started') turn.id = params.turn.id;
    if (params.turnId && turn.id && params.turnId !== turn.id) return;
    if (method === 'item/completed' && params.item?.type === 'agentMessage') {
      // Final completed items are authoritative; deltas remain progress only.
      turn.text = params.item.text;
    }
    if (method === 'turn/completed') {
      if (turn.id && params.turn.id !== turn.id) return;
      this.active = null;
      if (params.turn.status !== 'completed') turn.reject(new Error(JSON.stringify(params.turn.error ?? params.turn.status)));
      else turn.resolve({ sessionId: this.sessionId, text: turn.text ?? '', nativeStatus: params.turn.status });
    }
  }
  async turn(prompt) {
    if (this.active) throw new Error('Native turn already active');
    const turn = deferred();
    this.active = turn;
    try {
      const started = await this.peer.request('turn/start', {
        threadId: this.sessionId, input: [{ type: 'text', text: prompt }],
        ...(this.options.effort ? { effort: this.options.effort } : {}),
      });
      turn.id ??= started.turn.id;
      return await turn.promise;
    } catch (error) { if (this.active === turn) this.active = null; throw error; }
  }
  async interrupt() {
    if (this.active?.id) await this.peer.request('turn/interrupt', { threadId: this.sessionId, turnId: this.active.id });
  }
  async close() {
    this.peer?.finish();
    if (!this.child || this.child.exitCode !== null) return;
    this.child.stdin.end();
    this.child.kill('SIGTERM');
    await new Promise(resolve => {
      const timer = setTimeout(() => { this.child.kill('SIGKILL'); resolve(); }, 1000);
      this.child.once('exit', () => { clearTimeout(timer); resolve(); });
    });
  }
}
