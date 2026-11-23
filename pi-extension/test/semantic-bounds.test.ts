import { it } from 'node:test';
import assert from 'node:assert/strict';
import { MessageSchema } from '../src/protocol/generated/message.js';
import { WorkerRequestSchema } from '../src/protocol/generated/worker-request.js';

it('network and worker responses enforce UTF-8 bytes and response alternatives', () => {
  const identity = { conversationId: '12345678-1234-4123-8123-123456789abc', requestId: 'example' };
  const boundary = 'é'.repeat(8192);
  for (const schema of [MessageSchema, WorkerRequestSchema]) {
    const parse = (fields: object) => schema.safeParse({type: 'android-tool-response', payload: {...identity, ...fields}}).success;
    assert.ok(parse({ success: true, resultJson: boundary }));
    assert.ok(!parse({ success: true, resultJson: boundary + 'a' }));
    assert.ok(!parse({ success: true }));
    assert.ok(parse({ success: false, errorCode: 'denied', errorMessage: '', resultJson: 'details' }));
    assert.ok(!parse({ success: false, errorCode: '', errorMessage: '' }));
  }
});

it('blank prompts require an attachment after Unicode whitespace normalization', () => {
  const id = '12345678-1234-4123-8123-123456789abc';
  const common = { text: '\u0085\u00a0\t', delivery: 'followUp', configuration: { provider: 'test', modelId: 'example', effort: 'low' } };
  for (const type of ['prompt', 'timed-prompt']) {
    const payload = { ...common, ...(type === 'timed-prompt' ? { attemptId: id } : {}) };
    assert.ok(!MessageSchema.safeParse({type, payload}).success);
    assert.ok(MessageSchema.safeParse({type, payload: {...payload, attachments: [id]}}).success);
  }
});

it('sync cursor strings stop at the SQLite integer boundary', () => {
  const parse = (after: string) => MessageSchema.safeParse({type: 'sync', payload: {after}}).success;
  for (const value of ['0', '9223372036854775806', '9223372036854775807']) assert.ok(parse(value));
  for (const value of ['-1', '01', '9223372036854775808']) assert.ok(!parse(value));
});
