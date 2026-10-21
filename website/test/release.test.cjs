const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const release = require('../src/release.json');
const root = path.resolve(__dirname, '../..');

test('website installation version agrees with the README recommendation', () => {
  const readme = fs.readFileSync(path.join(root, 'README.md'), 'utf8');
  assert.equal(release.scalacv, readme.match(/com\.worxbend::scalacv:([^"\s]+)/)[1]);
});

test('website OpenCV version agrees with the native build', () => {
  const build = fs.readFileSync(path.join(root, 'build.mill'), 'utf8');
  assert.equal(release.opencv, build.match(/val opencv\s*=\s*"([\d.]+)-/)[1]);
});
