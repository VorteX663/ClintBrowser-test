"use strict";

const MODEL_FILE_ALIGNMENTS = { model: 256, lex: 64, vocab: 64, srcvocab: 64, trgvocab: 64 };
const WHITESPACE_REGEX = /^(\s*)(.*?)(\s*)$/s;
const FULL_WIDTH_PUNCTUATION_REGEX = /([。！？])“/g;
const FULL_WIDTH_LANGUAGES = new Set(["ja", "ko", "zh"]);

let bergamot = null;
let service = null;
let active = null;

function decompress(file) {
  const bytes = new Uint8Array(file.buffer);
  if (!file.compressed) return bytes;
  const out = file.size > 0 ? new Uint8Array(file.size) : undefined;
  return self.fzstd.decompress(bytes, out);
}

function initializeWasm(wasmBinary) {
  return new Promise((resolve, reject) => {
    const instance = loadBergamot({
      INITIAL_MEMORY: 41943040,
      print: () => {},
      printErr: () => {},
      onAbort() {
        reject(new Error("abort"));
      },
      onRuntimeInitialized: async () => {
        await Promise.resolve();
        resolve(instance);
      },
      wasmBinary
    });
  });
}

function textConfig(config) {
  const indent = "            ";
  let result = "\n";
  for (const [key, value] of Object.entries(config)) {
    result += `${indent}${key}: ${value}\n`;
  }
  return result + indent;
}

function constructModel(payload) {
  const aligned = {};
  for (const [fileType, file] of Object.entries(payload.files)) {
    const alignment = MODEL_FILE_ALIGNMENTS[fileType];
    if (!alignment) continue;
    const bytes = decompress(file);
    const memory = new bergamot.AlignedMemory(bytes.byteLength, alignment);
    memory.getByteArrayView().set(bytes);
    aligned[fileType] = memory;
  }
  const vocabList = new bergamot.AlignedMemoryList();
  if (aligned.vocab) {
    vocabList.push_back(aligned.vocab);
  } else if (aligned.srcvocab && aligned.trgvocab) {
    vocabList.push_back(aligned.srcvocab);
    vocabList.push_back(aligned.trgvocab);
  } else {
    throw new Error("vocab");
  }
  const config = textConfig({
    "beam-size": "1",
    normalize: "1.0",
    "word-penalty": "0",
    "max-length-break": "128",
    "mini-batch-words": "1024",
    workspace: "128",
    "max-length-factor": "2.0",
    "skip-cost": "true",
    "cpu-threads": "0",
    quiet: "true",
    "quiet-translation": "true",
    "gemm-precision": payload.modelName.endsWith("intgemm8.bin") || payload.modelName.endsWith("intgemm8.bin.zst") ? "int8shiftAll" : "int8shiftAlphaAll",
    alignment: "soft"
  });
  return new bergamot.TranslationModel(payload.from, payload.to, config, aligned.model, aligned.lex ?? null, vocabList, null);
}

function releaseActive() {
  if (!active) return;
  for (const model of active.models) {
    try {
      model.delete();
    } catch (e) {}
  }
  active = null;
}

function prepareText(text, from) {
  if (FULL_WIDTH_LANGUAGES.has(from)) return text.replace(FULL_WIDTH_PUNCTUATION_REGEX, "$1 “");
  return text;
}

function runBatch(texts, html) {
  const messages = new bergamot.VectorString();
  const options = new bergamot.VectorResponseOptions();
  const mapping = [];
  const results = new Array(texts.length);
  texts.forEach((text, index) => {
    const match = WHITESPACE_REGEX.exec(text);
    const core = match ? match[2] : text;
    if (!core) {
      results[index] = text;
      return;
    }
    mapping.push({ index, before: match ? match[1] : "", after: match ? match[3] : "" });
    messages.push_back(prepareText(core, active.from));
    options.push_back({ qualityScores: false, alignment: true, html });
  });
  let responses = null;
  try {
    if (mapping.length > 0) {
      responses = active.models.length === 1
        ? service.translate(active.models[0], messages, options)
        : service.translateViaPivoting(active.models[0], active.models[1], messages, options);
      mapping.forEach((entry, position) => {
        results[entry.index] = entry.before + responses.get(position).getTranslatedText() + entry.after;
      });
    }
    return results;
  } finally {
    messages.delete();
    options.delete();
    if (responses) responses.delete();
  }
}

function translate(texts, html) {
  try {
    return runBatch(texts, html);
  } catch (error) {
    if (texts.length === 1) return [null];
    return texts.map(text => {
      try {
        return runBatch([text], html)[0];
      } catch (e) {
        return null;
      }
    });
  }
}

self.onmessage = async event => {
  const message = event.data;
  try {
    switch (message.type) {
      case "init": {
        const wasm = decompress(message.wasm);
        bergamot = await initializeWasm(wasm);
        service = new bergamot.BlockingService({ cacheSize: 0 });
        self.postMessage({ type: "init", id: message.id, ok: true });
        break;
      }
      case "load": {
        releaseActive();
        const models = message.payloads.map(constructModel);
        active = { key: message.key, from: message.from, models };
        self.postMessage({ type: "load", id: message.id, ok: true });
        break;
      }
      case "unload": {
        releaseActive();
        self.postMessage({ type: "unload", id: message.id, ok: true });
        break;
      }
      case "translate": {
        if (!active || active.key !== message.key) throw new Error("not_loaded");
        const results = translate(message.texts, message.html);
        self.postMessage({ type: "translate", id: message.id, ok: true, results });
        break;
      }
    }
  } catch (error) {
    self.postMessage({ type: message.type, id: message.id, ok: false, error: String(error && error.message || error) });
  }
};
