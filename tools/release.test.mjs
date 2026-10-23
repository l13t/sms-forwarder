import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { analyzeCommits } from '@semantic-release/commit-analyzer';

const config = JSON.parse(await readFile(new URL('../.releaserc.json', import.meta.url)));
const options = config.plugins.find(([name]) => name === '@semantic-release/commit-analyzer')[1];
const analyze = (messages) => analyzeCommits(options, {
  cwd: process.cwd(),
  commits: messages.map((message, index) => ({ message, hash: String(index + 1).padStart(40, '0') })),
  logger: { log() {} },
});

for (const [message, expected] of [
  ['fix: retry failed requests', 'patch'],
  ['fix(webhook): preserve event IDs', 'patch'],
  ['perf: reduce database reads', 'patch'],
  ['feat: add notification filters', 'minor'],
  ['feat(ui)!: replace navigation', 'major'],
  ['fix!: change webhook contract', 'major'],
  ['refactor: replace payload\n\nBREAKING CHANGE: fields were renamed', 'major'],
  ['chore: replace settings\n\nBREAKING-CHANGE: old settings are incompatible', 'major'],
  ['docs: explain setup', null],
  ['chore: tidy scripts', null],
  ['ci: update runner', null],
  ['test: add fixtures', null],
]) {
  test(`${message.split('\n')[0]} → ${expected ?? 'no release'}`, async () => {
    assert.equal(await analyze([message]), expected);
  });
}
test('highest impact wins across commits since the last release', async () => {
  assert.equal(await analyze(['fix: reconnect', 'feat: add rules', 'docs: explain rules']), 'minor');
  assert.equal(await analyze(['feat: add rules', 'refactor!: change configuration']), 'major');
});
test('documentation-only changes do not release', async () => {
  assert.equal(await analyze(['docs: explain setup', 'ci: update cache', 'test: add fixture']), null);
});
test('release configuration publishes only Android assets on master', () => {
  assert.deepEqual(config.branches, ['master']);
  assert.equal(config.tagFormat, 'v${version}');
  assert.equal(config.plugins.some(([name]) => name === '@semantic-release/npm'), false);
  const exec = config.plugins.find(([name]) => name === '@semantic-release/exec')[1];
  assert.equal(exec.prepareCmd, 'bash tools/build_release.sh ${nextRelease.version}');
  const github = config.plugins.find(([name]) => name === '@semantic-release/github')[1];
  assert.deepEqual(github.assets.map(({ path }) => path), ['dist/*.apk', 'dist/SHA256SUMS']);
  assert.equal(github.successComment, false);
  assert.equal(github.failComment, false);
  assert.equal(github.releasedLabels, false);
});
