import { graphemeSegments } from 'unicode-segmenter/grapheme';

const segmenter =
  typeof Intl.Segmenter === 'function'
    ? new Intl.Segmenter(undefined, { granularity: 'grapheme' })
    : null;
const nonAscii = /[^\u0000-\u007f]/u;
const symbol = /\p{Extended_Pictographic}|\p{Emoji_Presentation}|\p{Symbol}|\u20e3/u;
export function uprightSegments(text) {
  if (!symbol.test(text) || !nonAscii.test(text)) return [{ text, upright: false }];
  return Array.from(
    segmenter ? segmenter.segment(text) : graphemeSegments(text),
    ({ segment }) => ({
      text: segment,
      upright: nonAscii.test(segment) && symbol.test(segment),
    }),
  );
}
