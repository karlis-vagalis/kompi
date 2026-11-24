import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';

const workflow = readFileSync(new URL('../.github/workflows/release.yml', import.meta.url), 'utf8');

// Read the actual sign/publish job headers, not copies of their expressions.
function job(name) {
  const block = workflow.split(`\n  ${name}:\n`)[1]?.split(/\n  [\w-]+:\n/)[0];
  assert.ok(block, `missing release job: ${name}`);
  const deps = block.match(/^    needs: (.*)$/m)?.[1].replace(/[\[\]\s]/g, '').split(',');
  const condition = block.match(/^    if: (.*)((?:\n      [^\n]*)*)/m);
  const expression = condition?.[1] === '|' ? condition[2].trim() : condition?.[1];
  return { deps, expression: expression?.replace(/^\$\{\{\s*|\s*\}\}$/g, '') ?? 'true' };
}

function runs({ expression }, needs, cancelled, upstreamSucceeded) {
  // Actions adds an implicit success() when there is no explicit status check.
  // The two workflow expressions use only boolean logic and needs lookups,
  // which can also be evaluated directly by JavaScript.
  const hasStatusCheck = /\b(?:always|cancelled|failure|success)\s*\(/.test(expression);
  return !!((hasStatusCheck || upstreamSucceeded) &&
    runInNewContext(expression, { needs, cancelled: () => cancelled }, { timeout: 1000 }));
}

const sign = job('sign');
const publish = job('publish');
assert.deepEqual(sign.deps, ['promote', 'build']);
assert.deepEqual(publish.deps, ['sign']);

for (const [route, promoted, build] of [
  ['promoted artifacts with skipped fallback', true, 'skipped'],
  ['fresh fallback build', false, 'success'],
]) {
  test(`publish runs after successful signing: ${route}`, () => {
    const upstreamSucceeded = build === 'success';
    const needs = { promote: { result: 'success', outputs: { promoted: String(promoted) } }, build: { result: build } };
    assert.equal(runs(sign, needs, false, upstreamSucceeded), true);
    assert.equal(runs(publish, { sign: { result: 'success' } }, false, upstreamSucceeded), true);
    assert.equal(runs(publish, { sign: { result: 'success' } }, true, upstreamSucceeded), false);
    for (const result of ['failure', 'skipped', 'cancelled']) {
      assert.equal(runs(publish, { sign: { result } }, false, false), false);
    }
    assert.equal(runs(sign, needs, true, false), false);
  });
}

test('signing does not run when fallback was needed but did not succeed', () => {
  for (const result of ['failure', 'skipped', 'cancelled']) {
    assert.equal(runs(sign, { promote: { result: 'success', outputs: { promoted: 'false' } }, build: { result } }, false, false), false);
  }
});
