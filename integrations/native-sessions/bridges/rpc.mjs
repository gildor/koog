import { createInterface } from 'node:readline';

export function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  // Completion can race the acknowledgement of a native turn/start request.
  promise.catch(() => {});
  return { promise, resolve, reject };
}

/** JSONL peer. Native callback payloads and answers pass through unchanged. */
export class Peer {
  pending = new Map();
  sequence = 0;
  closed = false;
  constructor(input, output, { request, notification = () => {}, exit = () => {} }) {
    this.output = output;
    this.handlers = { request, notification, exit };
    this.lines = createInterface({ input });
    this.lines.on('line', line => {
      try { this.receive(JSON.parse(line)); }
      catch (error) { this.finish(error); }
    });
    this.lines.on('close', () => this.finish(new Error('Native transport ended')));
    input.on('error', error => this.finish(error));
    output.on('error', error => this.finish(error));
  }
  write(message) {
    if (this.closed) throw new Error('Transport closed');
    this.output.write(JSON.stringify(message) + '\n');
  }
  request(method, params = {}) {
    const id = ++this.sequence;
    const call = deferred();
    this.pending.set(id, call);
    try { this.write({ id, method, params }); }
    catch (error) { this.pending.delete(id); call.reject(error); }
    return call.promise;
  }
  notify(method, params) { this.write({ method, params }); }
  receive(message) {
    if (message.method && message.id !== undefined) {
      Promise.resolve().then(() => this.handlers.request(message.method, message.params ?? {}))
        .then(result => this.write({ id: message.id, result }))
        .catch(error => {
          if (!this.closed) this.write({ id: message.id, error: { code: -32000, message: error.message } });
        });
    } else if (message.method) {
      this.handlers.notification(message.method, message.params ?? {});
    } else {
      const call = this.pending.get(message.id);
      if (!call) return;
      this.pending.delete(message.id);
      if (message.error) call.reject(new Error(message.error.message));
      else call.resolve(message.result);
    }
  }
  finish(error = new Error('Transport closed')) {
    if (this.closed) return;
    this.closed = true;
    for (const call of this.pending.values()) call.reject(error);
    this.pending.clear();
    this.handlers.exit(error);
    this.lines.close();
  }
}
