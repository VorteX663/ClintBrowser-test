"use strict";

(function () {
  const host = window.ClintEngineHost;
  const SIMD_TEST = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11]);
  let worker = null;
  let workerUrl = null;

  function reply(message) {
    host.onMessage(JSON.stringify(message));
  }

  function supported() {
    try {
      return typeof WebAssembly === "object" && typeof Worker === "function" && WebAssembly.validate(SIMD_TEST);
    } catch (e) {
      return false;
    }
  }

  async function text(path) {
    const response = await fetch(path);
    if (!response.ok) throw new Error("asset " + path);
    return response.text();
  }

  async function file(entry) {
    const response = await fetch(entry.url);
    if (!response.ok) throw new Error("file " + entry.url);
    const buffer = await response.arrayBuffer();
    return { buffer, compressed: entry.compressed, size: entry.size || 0 };
  }

  async function ensureWorker() {
    if (worker) return;
    const sources = await Promise.all([text("fzstd.js"), text("bergamot-translator.js"), text("engine-worker.js")]);
    workerUrl = URL.createObjectURL(new Blob([sources.join("\n;\n")], { type: "text/javascript" }));
    worker = new Worker(workerUrl);
    worker.onmessage = event => reply(event.data);
    worker.onerror = event => {
      event.preventDefault();
      reply({ type: "crash", ok: false, error: String(event.message || "worker") });
      try {
        worker.terminate();
      } catch (e) {}
      worker = null;
    };
  }

  async function handle(message) {
    try {
      switch (message.type) {
        case "init": {
          if (!supported()) {
            reply({ type: "init", id: message.id, ok: false, error: "unsupported" });
            return;
          }
          await ensureWorker();
          const wasm = await file(message.wasm);
          worker.postMessage({ type: "init", id: message.id, wasm }, [wasm.buffer]);
          break;
        }
        case "load": {
          await ensureWorker();
          const transfer = [];
          const payloads = [];
          for (const payload of message.payloads) {
            const files = {};
            for (const [fileType, entry] of Object.entries(payload.files)) {
              const loaded = await file(entry);
              transfer.push(loaded.buffer);
              files[fileType] = loaded;
            }
            payloads.push({ from: payload.from, to: payload.to, modelName: payload.modelName, files });
          }
          worker.postMessage({ type: "load", id: message.id, key: message.key, from: message.from, payloads }, transfer);
          break;
        }
        case "dispose": {
          if (worker) {
            worker.terminate();
            worker = null;
          }
          if (workerUrl) {
            URL.revokeObjectURL(workerUrl);
            workerUrl = null;
          }
          reply({ type: "dispose", id: message.id, ok: true });
          break;
        }
        default: {
          if (!worker) throw new Error("no_worker");
          worker.postMessage(message);
        }
      }
    } catch (error) {
      reply({ type: message.type, id: message.id, ok: false, error: String(error && error.message || error) });
    }
  }

  window.ClintEngine = {
    call(json) {
      handle(JSON.parse(json));
    }
  };

  window.addEventListener("DOMContentLoaded", () => host.onPageReady(supported()));
})();
