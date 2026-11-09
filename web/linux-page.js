/* Only this packaged page can reach the small, per-runtime native bridge. */
let worker;
const bytes = async (name) => {
  const response = await fetch('/runtime/' + name);
  if (!response.ok) throw new Error('Runtime asset unavailable: ' + name);
  return response.arrayBuffer();
};
window.vmSend = async (m) => {
  try {
    if (m.type === 'boot') {
      const [source, wasm, bios, vgabios, kernel, helper, alpine] = await Promise.all([
        fetch('/assets/linux-worker.js').then((r) => r.text()),
        bytes('v86.wasm'),
        bytes('seabios.bin'),
        bytes('vgabios.bin'),
        bytes('kernel.bin'),
        bytes('dsha-guest'),
        bytes('alpine.tar.gz'),
      ]);
      worker = new Worker(URL.createObjectURL(new Blob([source], { type: 'text/javascript' })));
      worker.onmessage = (event) => AndroidVM.event(JSON.stringify(event.data));
      worker.onerror = (event) =>
        AndroidVM.event(JSON.stringify({ type: 'fault', error: event.message }));
      const state = m.restore ? await bytes('state.bin') : undefined;
      worker.postMessage({ ...m, wasm, bios, vgabios, kernel, helper, alpine, state }, [
        wasm,
        bios,
        vgabios,
        kernel,
        helper,
        alpine,
        ...(state ? [state] : []),
      ]);
    } else worker.postMessage(m);
  } catch (error) {
    AndroidVM.event(JSON.stringify({ type: 'fault', id: m.id, error: String(error) }));
  }
};
AndroidVM.event(JSON.stringify({ type: 'pageReady' }));
