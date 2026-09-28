import { createOpencodeServer } from '@opencode-ai/sdk/server';
import { createOpencodeClient } from '@opencode-ai/sdk/v2/client';
import { deferred } from './rpc.mjs';

/** Official SDK over its local server; this preserves OpenCode's own harness. */
export class OpenCodeSession {
  constructor(callback, event) { this.callback = callback; this.event = event; }
  async open(options) {
    this.options = options;
    this.streamFailure = deferred();
    this.controller = new AbortController();
    this.server = await createOpencodeServer({ hostname: '127.0.0.1', port: 0, timeout: 20_000, config: options.native ?? {} });
    this.client = createOpencodeClient({ baseUrl: this.server.url, directory: options.cwd, throwOnError: true });
    const { data: providers } = await this.client.provider.list({ directory: options.cwd });
    const { data: session } = options.sessionId
      ? await this.client.session.get({ sessionID: options.sessionId, directory: options.cwd })
      : await this.client.session.create({ directory: options.cwd, title: 'Koog native fixture' });
    this.sessionId = session.id;
    const events = await this.client.event.subscribe({ directory: options.cwd }, { signal: this.controller.signal });
    this.pump = this.drain(events.stream);
    return { sessionId: this.sessionId, connectedProviders: providers.connected,
      models: providers.all.filter(p => providers.connected.includes(p.id)).flatMap(p => Object.keys(p.models).map(id => `${p.id}/${id}`)) };
  }
  async drain(stream) {
    try {
      for await (const event of stream) {
        if (event.properties?.sessionID && event.properties.sessionID !== this.sessionId) continue;
        this.event(event);
        if (event.type === 'permission.asked') {
          const answer = await this.callback('permission.asked', event.properties);
          await this.client.permission.respond({ sessionID: this.sessionId, permissionID: event.properties.id,
            directory: this.options.cwd, ...answer });
        }
        if (event.type === 'question.asked') {
          const answer = await this.callback('question.asked', event.properties);
          await this.client.question.reply({ requestID: event.properties.id, directory: this.options.cwd, ...answer });
        }
      }
      if (!this.controller.signal.aborted) this.streamFailure.reject(new Error('OpenCode event stream ended'));
    } catch (error) { if (!this.controller.signal.aborted) this.streamFailure.reject(error); }
  }
  async turn(prompt) {
    if (this.busy) throw new Error('Native turn already active');
    this.busy = true;
    try {
      const slash = this.options.model?.indexOf('/') ?? -1;
      const model = slash > 0 ? { providerID: this.options.model.slice(0, slash), modelID: this.options.model.slice(slash + 1) } : undefined;
      const { data } = await Promise.race([this.client.session.prompt({ sessionID: this.sessionId, directory: this.options.cwd,
        ...(model ? { model } : {}), parts: [{ type: 'text', text: prompt }] }), this.streamFailure.promise]);
      if (data.info.error) throw new Error(JSON.stringify(data.info.error));
      const text = data.parts.filter(part => part.type === 'text').map(part => part.text).join('\n');
      return { sessionId: this.sessionId, text, nativeStatus: data.info.finish ?? 'completed' };
    } finally { this.busy = false; }
  }
  async interrupt() { if (this.sessionId) await this.client.session.abort({ sessionID: this.sessionId, directory: this.options.cwd }); }
  async close() { this.controller?.abort(); this.server?.close(); await this.pump; }
}
