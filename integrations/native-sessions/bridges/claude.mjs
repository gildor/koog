import { query, createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import { z } from 'zod';
import { deferred } from './rpc.mjs';

class InputQueue {
  values = [];
  push(value) { if (this.waiter) { this.waiter({ value, done: false }); this.waiter = null; } else this.values.push(value); }
  [Symbol.asyncIterator]() { return this; }
  next() { return this.values.length ? Promise.resolve({ value: this.values.shift(), done: false }) : new Promise(resolve => { this.waiter = resolve; }); }
  return() { this.waiter?.({ done: true }); this.waiter = null; return Promise.resolve({ done: true }); }
}

/** One persistent official SDK query, with streamed inputs for multiple native turns. */
export class ClaudeSession {
  constructor(callback, event) { this.callback = callback; this.event = event; }
  async open(options) {
    this.sessionId = options.sessionId;
    this.input = new InputQueue();
    const nativeTools = (options.tools ?? []).map(spec => tool(spec.name, spec.description,
      z.fromJSONSchema(spec.inputSchema).shape,
      args => this.callback('tool/call', { name: spec.name, arguments: args }),
      { alwaysLoad: true }));
    const env = { ...process.env };
    delete env.CLAUDECODE; // This is an independent SDK client, not a nested TUI invocation.
    this.q = query({ prompt: this.input, options: {
      ...options.native, cwd: options.cwd, env,
      ...(options.model ? { model: options.model } : {}),
      ...(options.sessionId ? { resume: options.sessionId } : {}),
      ...(process.env.KOOG_CLAUDE_CLI ? { pathToClaudeCodeExecutable: process.env.KOOG_CLAUDE_CLI } : {}),
      includePartialMessages: true,
      mcpServers: { ...options.native?.mcpServers,
        ...(nativeTools.length ? { business: createSdkMcpServer({ name: 'business', version: '0.1', tools: nativeTools }) } : {}),
      },
      canUseTool: (toolName, input, context) => {
        const { signal, ...nativeContext } = context;
        return this.callback('canUseTool', { toolName, input, context: nativeContext });
      },
      onElicitation: request => this.callback('onElicitation', request),
      stderr: data => process.stderr.write(data),
    } });
    this.pump = this.drain();
    const account = await this.q.accountInfo();
    const models = await this.q.supportedModels();
    return { sessionId: this.sessionId ?? null, models: models.map(model => model.value),
      authType: account.tokenSource ?? account.apiKeySource ?? null,
      subscriptionType: account.subscriptionType ?? null, apiProvider: account.apiProvider ?? null };
  }
  async drain() {
    try {
      for await (const message of this.q) {
        if (message.session_id) this.sessionId = message.session_id;
        this.event(message);
        if (message.type === 'result' && this.active) {
          const turn = this.active;
          this.active = null;
          if (message.is_error || message.subtype !== 'success') turn.reject(new Error(JSON.stringify(message.errors ?? message.subtype)));
          else turn.resolve({ sessionId: this.sessionId, text: message.result ?? JSON.stringify(message.structured_output), nativeStatus: message.subtype });
        }
      }
      this.failure = new Error('Claude stream ended before completing the turn');
      this.active?.reject(this.failure);
    } catch (error) { this.active?.reject(error); this.failure = error; }
  }
  async turn(prompt) {
    if (this.failure) throw this.failure;
    if (this.active) throw new Error('Native turn already active');
    const turn = deferred();
    this.active = turn;
    this.input.push({ type: 'user', message: { role: 'user', content: prompt }, parent_tool_use_id: null, session_id: this.sessionId ?? '' });
    return turn.promise;
  }
  async interrupt() { await this.q?.interrupt(); }
  async close() { await this.input?.return(); this.q?.close(); await this.pump; }
}
