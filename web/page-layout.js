// These packaged pages live for the lifetime of their WebView.
export function observeLayout(element, update) {
  if (typeof ResizeObserver === 'function') {
    new ResizeObserver(update).observe(element);
  } else {
    window.addEventListener('resize', update);
    element.addEventListener('load', update, true);
  }
}

export function afterFonts(update) {
  Promise.resolve(document.fonts?.ready).then(update, update);
}
