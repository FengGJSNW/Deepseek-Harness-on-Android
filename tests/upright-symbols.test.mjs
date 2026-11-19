import test from 'node:test';
import assert from 'node:assert/strict';
import { uprightSegments } from '../web/upright-symbols.js';

test('emoji sequences stay whole and upright without changing original text', () => {
  const emoji = ['😀', '👩🏽‍💻', '👨‍👩‍👧‍👦', '🇨🇳', '1️⃣', '❤️', '©️', '→'];
  for (const value of emoji) {
    const parts = uprightSegments(`Text ${value} 123`);
    assert.equal(parts.map((p) => p.text).join(''), `Text ${value} 123`);
    assert.ok(
      parts.some((p) => p.text === value && p.upright),
      value,
    );
  }
  assert.ok(uprightSegments('Italic 中文 123 # * + =').every((p) => !p.upright));
});

test('Markdown preserves whole emoji when Intl.Segmenter is absent', async (t) => {
  t.mock.property(Intl, 'Segmenter', undefined);
  const legacy = await import('../web/upright-symbols.js?without-native-segmentation');
  const text = 'é 你好 👩🏽‍💻 → 🇨🇳 1️⃣ 👨‍👩‍👧‍👦';
  assert.deepEqual(legacy.uprightSegments(text), uprightSegments(text));
});
