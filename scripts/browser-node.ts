/** Browser substitutes for the small Node surface used by the embedded kernel. */
export const randomUUID = () => crypto.randomUUID();
export const isAbsolute = (value: string) => value.startsWith('/');
export const isPromise = (value: unknown) => value instanceof Promise;
export const createRequire = () => (id: string) => {
  if (id !== '../package.json') throw new Error(`Unsupported runtime require: ${id}`);
  return { version: '0.2.1-rc.3' };
};
