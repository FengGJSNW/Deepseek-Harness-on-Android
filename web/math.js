import renderMathInElement from 'katex/contrib/auto-render';
import { continuousCardPath } from './shape.js';
import { uprightSegments } from './upright-symbols.js';
const article = document.getElementById('document');
let frame;
const measure = () => {
  cancelAnimationFrame(frame);
  frame = requestAnimationFrame(() => {
    for (const card of article.querySelectorAll('[data-card-radius]')) {
      const svg = card.querySelector(':scope > .card-backdrop');
      const box = card.getBoundingClientRect();
      const path = continuousCardPath(box.width, box.height, Number(card.dataset.cardRadius));
      if (svg) {
        svg.setAttribute('viewBox', `0 0 ${box.width} ${box.height}`);
        svg.firstChild.setAttribute('d', path);
      }
      const clip = card.querySelector(':scope > .table-scroll');
      if (clip) clip.style.clipPath = `path("${path}")`;
    }
    MathNative.height(Math.ceil(article.getBoundingClientRect().height));
  });
};
window.renderDocument = ({ html, size, line, family, weight, italic, ligatures }) => {
  // HTML comes from CommonMark's escaping renderer. No user HTML or script is accepted.
  article.innerHTML = html;
  for (const pre of article.querySelectorAll('pre')) {
    const code = pre.querySelector('code');
    if (!code) continue;
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.classList.add('card-backdrop');
    pre.dataset.cardRadius = '8';
    svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS(svg.namespaceURI, 'path');
    path.setAttribute('fill', '#f5f4f1');
    svg.append(path);
    pre.prepend(svg);
    const bar = document.createElement('div');
    bar.className = 'code-header';
    const language = document.createElement('span');
    language.className = 'code-language';
    language.textContent = code.className.replace('language-', '') || '纯文本';
    const copy = document.createElement('button');
    copy.setAttribute('aria-label', '复制代码');
    copy.innerHTML =
      '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5H4V20H17V17M8 3H20V16H8Z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg>';
    const label = document.createElement('span');
    label.textContent = '';
    label.setAttribute('role', 'status');
    copy.append(label);
    copy.onclick = () => {
      MathNative.copy(code.textContent);
      label.textContent = '已复制';
      setTimeout(() => (label.textContent = ''), 1500);
    };
    bar.append(language, copy);
    pre.insertBefore(bar, code);
  }
  for (const table of article.querySelectorAll('table')) {
    const card = document.createElement('div');
    card.className = 'table-card';
    const clip = document.createElement('div');
    clip.className = 'table-scroll';
    table.before(card);
    clip.append(table);
    card.append(clip);
  }
  for (const area of article.querySelectorAll('.table-scroll, pre > code')) {
    let scrollbarTimer;
    area.addEventListener(
      'scroll',
      () => {
        area.classList.add('scrollbars-active');
        clearTimeout(scrollbarTimer);
        scrollbarTimer = setTimeout(() => area.classList.remove('scrollbars-active'), 750);
      },
      { passive: true },
    );
  }
  document.body.style.fontSize = `${size}px`;
  document.body.style.lineHeight = String(line);
  document.body.style.fontFamily = family;
  document.body.style.fontWeight = String(weight);
  document.body.style.fontStyle = italic ? 'italic' : 'normal';
  document.body.style.fontVariantLigatures = ligatures ? 'normal' : 'none';
  renderMathInElement(article, {
    delimiters: [
      { left: '$$', right: '$$', display: true },
      { left: '\\[', right: '\\]', display: true },
      { left: '\\(', right: '\\)', display: false },
      { left: '$', right: '$', display: false },
    ],
    ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code', 'option'],
    trust: false,
    throwOnError: false,
    maxSize: 10,
    maxExpand: 1000,
    strict: 'warn',
  });
  const walker = document.createTreeWalker(article, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (!node.parentElement.closest('.katex, .upright-symbol, script, style')) nodes.push(node);
  }
  for (const node of nodes) {
    const segments = uprightSegments(node.textContent);
    if (!segments.some((part) => part.upright)) continue;
    const fragment = document.createDocumentFragment();
    for (const part of segments) {
      if (part.upright) {
        const span = document.createElement('span');
        span.className = 'upright-symbol';
        span.textContent = part.text;
        fragment.append(span);
      } else fragment.append(document.createTextNode(part.text));
    }
    node.replaceWith(fragment);
  }
  measure();
  document.fonts.ready.then(measure);
};
document.addEventListener('click', (event) => {
  const link = event.target.closest('a');
  if (link) {
    event.preventDefault();
    MathNative.open(link.getAttribute('href') || '');
  }
});
new ResizeObserver(measure).observe(article);
MathNative.ready();
