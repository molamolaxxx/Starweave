import test from 'node:test';
import assert from 'node:assert/strict';
import { ReliableLink } from '../src/reliable-link.mjs';
const socket = () => ({ readyState: 1, frames: [], send(text) { this.frames.push(JSON.parse(text)); }, close() {} });
test('reconnect replays unacknowledged events and deduplicates tasks', async () => {
  const delivered = [], link = new ReliableLink(f => delivered.push(f), reason => assert.fail(reason));
  const a = socket(); link.attach(a, { type: 'register' }); a.onopen();
  assert.equal(a.frames[0].resumeReceived, 0);
  link.receive({ type: 'resume_ready', received: 0, resumed: false });
  link.receive({ type: 'resume_frame', seq: 1, payload: { method: 'session/prompt' } });
  link.send({ type: 'result', text: 'completed once' });
  link.detach(a, 'test'); const waiting = link.wait();
  const b = socket(); link.attach(b, { type: 'register' }); b.onopen();
  assert.equal(b.frames[0].resumeToken, a.frames[0].resumeToken);
  link.receive({ type: 'resume_ready', received: 0, resumed: true }); await waiting;
  assert.equal(b.frames[1].seq, 1);
  link.receive({ type: 'resume_frame', seq: 1, payload: { method: 'session/prompt' } });
  assert.equal(delivered.length, 1);
  link.receive({ type: 'resume_ack', seq: 1 }); assert.equal(link.pending.size, 0); assert.equal(link.bytes, 0); link.stop();
});
test('invalid acknowledgement and lost center fail instead of silently starting a task', () => {
  const link = new ReliableLink(() => {}, () => {}); link.attach(socket(), {});
  assert.throws(() => link.receive({ type: 'resume_ack', seq: 9 }));
  link.receive({ type: 'resume_ready', received: 0, resumed: false });
  assert.throws(() => link.receive({ type: 'resume_ready', received: 0, resumed: false }), /中心已丢失/); link.stop();
});
test('waiting for reconnect is cancellable and cache is bounded', async () => {
  const link = new ReliableLink(() => {}, () => {}), abort = new AbortController();
  const waiting = link.wait(abort.signal); abort.abort(); await assert.rejects(waiting, /取消/);
  for (let i = 0; i < 8192; i++) link.send({ n: i });
  assert.throws(() => link.send({ n: 8193 }), /上限/); link.stop();
});
test('large offline backlog is drained in acknowledgement windows without overflowing the socket queue', () => {
  const link = new ReliableLink(() => {}, () => {}), a = socket(); link.attach(a, {});
  for (let i = 0; i < 400; i++) link.send({ text: `delta-${i}` });
  link.receive({ type: 'resume_ready', received: 0, resumed: false });
  assert.equal(a.frames.length, 32);
  link.receive({ type: 'resume_ack', seq: 32 }); assert.equal(a.frames.length, 64);
  for (let seq = 64; seq <= 384; seq += 32) link.receive({ type: 'resume_ack', seq });
  assert.equal(a.frames.length, 400);
  link.receive({ type: 'resume_ack', seq: 400 }); assert.equal(link.pending.size, 0); link.stop();
});
