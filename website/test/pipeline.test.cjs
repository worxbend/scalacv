const assert = require('node:assert/strict');
const {createHash} = require('node:crypto');
const test = require('node:test');
const {mount} = require('./pipeline-harness.cjs');

// Fixed canvas commands and computed pixel buffers, using a deterministic sensor input.
// These characterize rendering without requiring a browser or platform-specific font rasterizer.
const frames = [
  '252ce3c014560d7af10223058217b60dc45ecf7462271fe848d6d50c92e95e0a',
  'a2a65d80faef615afe33ed2017397cdebf2262a37238214ed702823da175fa99',
  '0d8862b44e54f6a9567e65defd4a1cf3f7cf3cf0415473dffd1c36a7e74dfe6a',
  '83fae25a4d997667242ddab7f75ab66cdb0671617cf0f3a7353c97ee051b96ed',
  'd5f0796b95341f8b6eb0261ac8b6e104c316da304928591a494d473783966d85',
  '444bc8dda104a528e5311bc64e4cd61678477fcdbcb8c0001e0320f10de87456',
];
for (const [stage, hash] of frames.entries()) {
  test(`stage ${stage} retains its rendered frame and caption`, t => {
    const p = mount(); t.after(() => p.close());
    p.tick(100); p.tick(600 + stage * 3000);
    assert.equal(p.states[0], stage);
    assert.equal(createHash('sha256').update(p.drain()).digest('hex'), hash);
  });
}

for (const reason of ['paused', 'hidden', 'offscreen']) {
  test(`${reason} parks the scene clock and resumes without a jump`, t => {
    const p = mount(), reference = mount();
    t.after(() => { p.close(); reference.close(); });
    p.tick(100); p.tick(600); p.drain();
    const park = value => {
      if (reason === 'paused') p.pause(value);
      if (reason === 'hidden') p.document.hidden = value;
      if (reason === 'offscreen') p.observers[0].callback([{isIntersecting: !value}]);
    };
    park(true); p.tick(10000);
    assert.equal(p.drain(), '[]');
    park(false); p.tick(10040);
    reference.tick(100); reference.tick(640);
    assert.equal(p.drain(), reference.drain());
    assert.equal(p.states[0], 0);
  });
}

test('paused resize redraws the frozen composition without advancing time', t => {
  const p = mount(); t.after(() => p.close());
  p.tick(100); p.tick(2600); const frozen = p.drain();
  p.pause(true); p.tick(10000); p.resize();
  assert.equal(p.screen.blank, false);
  assert.equal(p.drain(), frozen);
  assert.equal(p.states[0], 0);
  p.tick(10100); assert.equal(p.drain(), '[]');
});

test('reduced motion redraws the same final composition on resize without a loop', t => {
  const p = mount({reduced: true}); t.after(() => p.close());
  const first = p.drain(); p.resize();
  assert.equal(p.screen.blank, false);
  assert.equal(p.drain(), first);
  assert.equal(p.states[0], 5);
  assert.equal(p.frames.size, 0);
});

test('caption switches at the middle of the morph and frames are throttled', t => {
  const p = mount(); t.after(() => p.close());
  p.tick(100); p.tick(2600);
  assert.equal(p.states[0], 0); p.drain();
  p.tick(2610); assert.equal(p.drain(), '[]');
  p.tick(2700); assert.equal(p.states[0], 1);
});

test('cleanup cancels the scheduled frame and disconnects both observers', () => {
  const p = mount(); p.tick(100); p.close();
  assert.equal(p.frames.size, 0);
  assert(p.observers.every(observer => observer.disconnected));
});
