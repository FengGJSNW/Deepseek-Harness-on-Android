import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
const terminal = new Terminal({
  fontFamily: 'DSHAMono, DSHACjk, monospace',
  fontSize: 13,
  cursorBlink: true,
  cursorStyle: 'bar',
  scrollback: 3000,
  allowProposedApi: false,
  theme: {
    background: '#FAFAF9',
    foreground: '#2B3037',
    cursor: '#426EFE',
    selectionBackground: '#DDE6FF',
    black: '#343B44',
    red: '#BB5954',
    green: '#3C8669',
    yellow: '#A57B2D',
    blue: '#4E75BF',
    magenta: '#9065AE',
    cyan: '#378D9D',
    white: '#DADCE1',
    brightBlack: '#868A92',
    brightRed: '#D77069',
    brightGreen: '#57A58A',
    brightYellow: '#C9A04E',
    brightBlue: '#668EE1',
    brightMagenta: '#B385CE',
    brightCyan: '#58ABBE',
    brightWhite: '#FFFFFF',
  },
});
const fit = new FitAddon();
terminal.loadAddon(fit);
terminal.open(document.getElementById('terminal'));
terminal.onData((text) => ConsoleNative.input(text));
terminal.onResize(({ rows, cols }) => ConsoleNative.resize(rows, cols));
window.consoleOutput = (encoded) =>
  terminal.write(Uint8Array.from(atob(encoded), (c) => c.charCodeAt(0)));
window.consoleConfigure = ({ size, system }) => {
  const family = system ? 'monospace' : 'DSHAMono, DSHACjk, monospace';
  if (terminal.options.fontSize !== size || terminal.options.fontFamily !== family) {
    terminal.options.fontSize = size;
    terminal.options.fontFamily = family;
    fit.fit();
  }
};
window.consoleKey = (key) => {
  terminal.focus();
  ConsoleNative.input(key);
};
new ResizeObserver(() => fit.fit()).observe(document.body);
document.fonts.ready.then(() => {
  fit.fit();
  ConsoleNative.ready();
});

window.consoleText = () =>
  Array.from(
    { length: terminal.buffer.active.length },
    (_, i) => terminal.buffer.active.getLine(i)?.translateToString(true) || '',
  ).join('\n');
