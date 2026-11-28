import assert from 'node:assert/strict';
import {
  extractProbedPeer,
  isValidPeerHostInput,
  mergePeers,
  normalizePeerHost,
  peerAdminUrl,
  removeSavedPeer,
  upsertSavedPeer,
} from './peers.ts';

assert.equal(normalizePeerHost('  http://192.168.1.20:8099/admin  '), '192.168.1.20:8099');
assert.equal(normalizePeerHost('https://192.168.1.21:8099/'), 'https://192.168.1.21:8099');
assert.equal(normalizePeerHost('https://tv.myhome.example/admin/'), 'https://tv.myhome.example');
assert.equal(normalizePeerHost('192.168.1.22'), '192.168.1.22:8099');
assert.equal(normalizePeerHost('100.64.12.34'), '100.64.12.34:8099');
assert.equal(normalizePeerHost('tv.myhome.example'), 'tv.myhome.example:8099');
assert.equal(normalizePeerHost('[fd7a::1]'), '[fd7a::1]:8099');
assert.equal(normalizePeerHost(''), '');

assert.equal(peerAdminUrl('192.168.1.20:8099'), 'http://192.168.1.20:8099/admin/');
assert.equal(peerAdminUrl('https://tv.myhome.example'), 'https://tv.myhome.example/admin/');

assert.equal(isValidPeerHostInput('192.168.1.20:8099'), true);
assert.equal(isValidPeerHostInput('192.168.1.20'), true);
assert.equal(isValidPeerHostInput('100.64.12.34'), true);
assert.equal(isValidPeerHostInput('47.92.1.2:8099'), true);
assert.equal(isValidPeerHostInput('tv.myhome.example'), true);
assert.equal(isValidPeerHostInput('https://tv.myhome.example'), true);
assert.equal(isValidPeerHostInput('[fd7a::1]:8099'), true);
assert.equal(isValidPeerHostInput('127.0.0.1:8099'), false);
assert.equal(isValidPeerHostInput('169.254.169.254:8099'), false);
assert.equal(isValidPeerHostInput('localhost:8099'), false);
assert.equal(isValidPeerHostInput(''), false);
assert.equal(isValidPeerHostInput('not a host!'), false);

let saved = upsertSavedPeer([], { host: '192.168.1.20:8099', name: '卧室电视', isTv: true, questions: 42 });
assert.equal(saved.length, 1);
saved = upsertSavedPeer(saved, { host: 'http://192.168.1.20:8099/', name: '卧室大电视', version: '0.5.7' });
assert.equal(saved.length, 1);
assert.equal(saved[0].name, '卧室大电视');
assert.equal(saved[0].isTv, true);
assert.equal(saved[0].version, '0.5.7');

const merged = mergePeers(
  [
    { id: 'self-id', host: '192.168.1.10:8099', name: '客厅电视', isTv: true, online: true },
    { id: 'bed-id', host: '192.168.1.20:8099', name: '卧室电视(实时)', version: '0.5.8', isTv: true, questions: 60, online: true },
  ],
  [
    { host: '192.168.1.20:8099', name: '卧室旧名', addedAt: 1000 },
    { host: '192.168.1.30:8099', name: '书房平板', isTv: false, questions: 25, addedAt: 2000 },
  ],
  ['192.168.1.10:8099'],
);

assert.equal(merged.length, 2);
assert.equal(merged[0].host, '192.168.1.20:8099');
assert.equal(merged[0].name, '卧室电视(实时)');
assert.equal(merged[0].discovered, true);
assert.equal(merged[0].saved, true);
assert.equal(merged[0].online, true);
assert.equal(merged[1].host, '192.168.1.30:8099');
assert.equal(merged[1].discovered, false);
assert.equal(merged[1].saved, true);
assert.equal(merged[1].online, false);

const probed = extractProbedPeer(
  { ok: true, peer: { host: '192.168.1.30:8099', name: '书房 MatePad', version: '0.5.8', isTv: false, questions: 88 } },
  '192.168.1.30:8099',
);
assert.equal(probed.name, '书房 MatePad');
assert.equal(probed.isTv, false);
assert.equal(probed.questions, 88);

const flatProbed = extractProbedPeer(
  { ok: true, name: '儿童房电视', flavor: 'tv', version: '0.5.7', questions: 12 },
  '192.168.1.31:8099',
);
assert.equal(flatProbed.host, '192.168.1.31:8099');
assert.equal(flatProbed.isTv, true);

saved = removeSavedPeer(saved, '192.168.1.20:8099');
assert.equal(saved.length, 0);

console.log('peers.test.ts passed');
