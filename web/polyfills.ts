/** Shared resource-management symbols for WebViews predating ES2025. */
for (const key of ['dispose', 'asyncDispose']) {
  if (!(Symbol as any)[key])
    Object.defineProperty(Symbol, key, { value: Symbol.for(`Symbol.${key}`) });
}
