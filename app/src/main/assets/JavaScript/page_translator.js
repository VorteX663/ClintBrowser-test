(function () {
  if (window.top !== window || window.__clintTranslate) return;

  var SKIP_TAGS = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, CODE: 1, PRE: 1, KBD: 1, SAMP: 1, VAR: 1, TEXTAREA: 1, INPUT: 1, SELECT: 1, OPTION: 1, SVG: 1, MATH: 1, CANVAS: 1, VIDEO: 1, AUDIO: 1, IFRAME: 1, OBJECT: 1, EMBED: 1, HEAD: 1, TITLE: 1 };
  var INLINE_TAGS = { A: 1, ABBR: 1, B: 1, BDI: 1, BDO: 1, BR: 1, CITE: 1, DATA: 1, DEL: 1, DFN: 1, EM: 1, FONT: 1, I: 1, INS: 1, LABEL: 1, MARK: 1, Q: 1, S: 1, SMALL: 1, SPAN: 1, STRONG: 1, SUB: 1, SUP: 1, TIME: 1, U: 1, WBR: 1, BIG: 1, TT: 1, STRIKE: 1, NOBR: 1 };
  var VOID_TAGS = { BR: 1, WBR: 1 };
  var ATTRIBUTES = ["title", "alt", "placeholder", "aria-label"];
  var MAX_BATCH_UNITS = 32;
  var MAX_BATCH_CHARS = 5000;
  var MAX_UNIT_CHARS = 8000;

  var bridge = window.ClintTranslateBridge;
  var session = null;
  var observer = null;
  var visibility = null;
  var visible = new Set();
  var queue = [];
  var queued = new Set();
  var inFlight = null;
  var batchCounter = 0;
  var snapshots = new Map();
  var attributeSnapshots = [];
  var translatedBlocks = new WeakSet();
  var titleSnapshot = null;

  function isSkipped(element) {
    for (var node = element; node && node.nodeType === 1; node = node.parentElement) {
      if (SKIP_TAGS[node.tagName.toUpperCase()]) return true;
      if (node.getAttribute("translate") === "no") return true;
      if (node.classList && node.classList.contains("notranslate")) return true;
      if (node.isContentEditable) return true;
      if (node.getAttribute("translate") === "yes") return false;
    }
    return false;
  }

  function isInline(element) {
    return !!INLINE_TAGS[element.tagName.toUpperCase()];
  }

  function blockOf(node) {
    var element = node.nodeType === 1 ? node : node.parentElement;
    while (element && element !== document.body && isInline(element)) element = element.parentElement;
    return element;
  }

  function hasText(text) {
    return /[^\s\d\p{P}\p{S}]/u.test(text);
  }

  function escapeHtml(text) {
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function serialize(block) {
    var nodes = [];
    var chars = 0;
    var meaningful = false;
    function walk(parent) {
      var out = "";
      for (var child = parent.firstChild; child; child = child.nextSibling) {
        if (child.nodeType === 3) {
          var value = child.nodeValue;
          if (hasText(value)) meaningful = true;
          chars += value.length;
          out += escapeHtml(value);
        } else if (child.nodeType === 1) {
          var tag = child.tagName.toLowerCase();
          var id = nodes.length;
          nodes.push(child);
          if (!/^[a-z][a-z0-9-]*$/.test(tag)) tag = "span";
          if (isInline(child) && !isSkipped(child)) {
            if (VOID_TAGS[child.tagName.toUpperCase()]) {
              out += "<" + tag + " data-ct=\"" + id + "\">";
            } else {
              out += "<" + tag + " data-ct=\"" + id + "\">" + walk(child) + "</" + tag + ">";
            }
          } else {
            var placeholder = isInline(child) ? "span" : "div";
            out += "<" + placeholder + " data-ct=\"" + id + "\" data-cs=\"1\"></" + placeholder + ">";
          }
        }
      }
      return out;
    }
    var html = walk(block);
    if (!meaningful || chars > MAX_UNIT_CHARS) return null;
    return { html: html, nodes: nodes };
  }

  function rebuild(live, translated, nodes, used) {
    var children = [];
    for (var child = translated.firstChild; child; child = child.nextSibling) {
      if (child.nodeType === 3) {
        children.push(document.createTextNode(child.nodeValue));
      } else if (child.nodeType === 1) {
        var id = child.getAttribute("data-ct");
        var original = id !== null ? nodes[parseInt(id, 10)] : null;
        if (!original || used.has(original)) {
          if (child.textContent) children.push(document.createTextNode(child.textContent));
          continue;
        }
        used.add(original);
        if (!child.hasAttribute("data-cs") && !VOID_TAGS[original.tagName.toUpperCase()]) {
          rebuild(original, child, nodes, used);
        }
        children.push(original);
      }
    }
    if (!snapshots.has(live)) snapshots.set(live, Array.prototype.slice.call(live.childNodes));
    if (live.replaceChildren) {
      live.replaceChildren.apply(live, children);
    } else {
      while (live.firstChild) live.removeChild(live.firstChild);
      children.forEach(function (node) { live.appendChild(node); });
    }
  }

  function applyBlock(unit, result) {
    var block = unit.block;
    if (!block.isConnected || unit.version !== versionOf(block)) return;
    var template = document.createElement("template");
    template.innerHTML = result;
    var used = new Set();
    rebuild(block, template.content, unit.nodes, used);
    unit.nodes.forEach(function (node) {
      if (!used.has(node) && node.parentNode === null) block.appendChild(node);
    });
    translatedBlocks.add(block);
  }

  var versions = new WeakMap();
  function versionOf(block) {
    return versions.get(block) || 0;
  }
  function bump(block) {
    versions.set(block, versionOf(block) + 1);
  }

  function enqueueBlock(block) {
    if (!block || queued.has(block) || translatedBlocks.has(block)) return;
    if (block.nodeType !== 1 || isSkipped(block)) return;
    queued.add(block);
    queue.push({ kind: "block", block: block });
    if (visibility) visibility.observe(block);
  }

  function attributesSkipped(element) {
    var tag = element.tagName.toUpperCase();
    if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return element.parentElement ? isSkipped(element.parentElement) : false;
    return isSkipped(element);
  }

  function enqueueAttributes(element) {
    if (element.nodeType !== 1 || attributesSkipped(element)) return;
    ATTRIBUTES.forEach(function (name) {
      var value = element.getAttribute(name);
      if (!value || !hasText(value)) return;
      if (element.__clintAttrs && element.__clintAttrs[name] === value) return;
      queue.push({ kind: "attr", element: element, name: name, value: value });
    });
  }

  function collect(root) {
    if (!root) return;
    if (root.nodeType === 3) {
      if (hasText(root.nodeValue)) {
        var parent = root.parentElement;
        if (parent && !isSkipped(parent)) enqueueBlock(blockOf(root));
      }
      return;
    }
    if (root.nodeType !== 1) return;
    if (isSkipped(root)) {
      enqueueAttributes(root);
      return;
    }
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        if (node.nodeType === 1) {
          enqueueAttributes(node);
          return isSkipped(node) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_SKIP;
        }
        return hasText(node.nodeValue) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
      }
    });
    enqueueAttributes(root);
    var text;
    while ((text = walker.nextNode())) enqueueBlock(blockOf(text));
  }

  function nextBatch() {
    var priority = [];
    var rest = [];
    queue.forEach(function (item) {
      if (item.kind !== "block" || visible.has(item.block)) priority.push(item);
      else rest.push(item);
    });
    queue = priority.concat(rest);
    var batch = [];
    var chars = 0;
    while (queue.length && batch.length < MAX_BATCH_UNITS && chars < MAX_BATCH_CHARS) {
      var item = queue.shift();
      if (item.kind === "block") {
        queued.delete(item.block);
        if (visibility) visibility.unobserve(item.block);
        if (!item.block.isConnected || translatedBlocks.has(item.block)) continue;
        var serialized = serialize(item.block);
        if (!serialized) {
          translatedBlocks.add(item.block);
          continue;
        }
        item.nodes = serialized.nodes;
        item.text = serialized.html;
        item.version = versionOf(item.block);
        item.html = true;
      } else if (item.kind === "attr") {
        if (!item.element.isConnected || item.element.getAttribute(item.name) !== item.value) continue;
        item.text = item.value;
        item.html = false;
      } else {
        if (document.title !== item.text) continue;
        item.html = false;
      }
      chars += item.text.length;
      batch.push(item);
    }
    return batch;
  }

  function pump() {
    if (!session || inFlight) return;
    var batch = nextBatch();
    if (!batch.length) {
      bridge.idle(session);
      return;
    }
    batchCounter += 1;
    inFlight = { id: batchCounter, items: batch };
    bridge.request(session, batchCounter, JSON.stringify(batch.map(function (item) {
      return { t: item.text, h: item.html };
    })));
  }

  function withoutObserver(action) {
    var pending = observer ? observer.takeRecords() : [];
    if (observer) observer.disconnect();
    try {
      action();
    } finally {
      if (observer && session) {
        observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRIBUTES });
      }
    }
    if (pending.length) onMutations(pending);
  }

  function onMutations(records) {
    if (!session) return;
    records.forEach(function (record) {
      if (record.type === "attributes") {
        enqueueAttributes(record.target);
        return;
      }
      var block = blockOf(record.target);
      if (block && translatedBlocks.has(block)) {
        translatedBlocks.delete(block);
        forgetSnapshots(block);
        bump(block);
      }
      if (record.type === "characterData") {
        collect(record.target);
      } else {
        for (var i = 0; i < record.addedNodes.length; i++) collect(record.addedNodes[i]);
        if (block) collect(block);
      }
    });
    pump();
  }

  function forgetSnapshots(block) {
    snapshots.forEach(function (_, element) {
      if (element === block || block.contains(element)) snapshots.delete(element);
    });
  }

  function restoreAll() {
    withoutObserver(function () {
      var entries = Array.from(snapshots.entries()).reverse();
      entries.forEach(function (entry) {
        var element = entry[0];
        var original = entry[1];
        if (element.replaceChildren) {
          element.replaceChildren.apply(element, original);
        } else {
          while (element.firstChild) element.removeChild(element.firstChild);
          original.forEach(function (node) { element.appendChild(node); });
        }
      });
      attributeSnapshots.forEach(function (entry) {
        if (entry.element.getAttribute(entry.name) === entry.translated) entry.element.setAttribute(entry.name, entry.original);
        if (entry.element.__clintAttrs) delete entry.element.__clintAttrs[entry.name];
      });
      if (titleSnapshot !== null) document.title = titleSnapshot;
    });
    snapshots = new Map();
    attributeSnapshots = [];
    translatedBlocks = new WeakSet();
    titleSnapshot = null;
  }

  function stop() {
    session = null;
    inFlight = null;
    queue = [];
    queued = new Set();
    if (observer) observer.disconnect();
    observer = null;
    if (visibility) visibility.disconnect();
    visibility = null;
    visible = new Set();
  }

  function sampleText(limit) {
    var out = "";
    if (!document.body) return out;
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        var parent = node.parentElement;
        if (!parent || isSkipped(parent)) return NodeFilter.FILTER_REJECT;
        return hasText(node.nodeValue) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
      }
    });
    var node;
    while (out.length < limit && (node = walker.nextNode())) out += " " + node.nodeValue.trim();
    return out;
  }

  window.__clintTranslate = {
    detect: function () {
      var html = document.documentElement;
      var lang = html.getAttribute("lang") || html.getAttribute("xml:lang") || "";
      if (!lang) {
        var meta = document.querySelector("meta[http-equiv='content-language' i]");
        if (meta) lang = (meta.getAttribute("content") || "").split(",")[0];
      }
      return JSON.stringify({ lang: lang.trim(), text: sampleText(4000), title: document.title || "" });
    },
    start: function (id) {
      if (session === id) return;
      if (session) stop();
      session = id;
      batchCounter = 0;
      if (titleSnapshot === null && document.title) titleSnapshot = document.title;
      if ("IntersectionObserver" in window) {
        visibility = new IntersectionObserver(function (entries) {
          entries.forEach(function (entry) {
            if (entry.isIntersecting) visible.add(entry.target);
            else visible.delete(entry.target);
          });
        }, { rootMargin: "100% 0px" });
      }
      observer = new MutationObserver(onMutations);
      if (document.title && hasText(document.title)) {
        queue.push({ kind: "title", text: document.title });
      }
      collect(document.body);
      withoutObserver(function () {});
      setTimeout(pump, 0);
    },
    receive: function (id, batchId, results) {
      if (id !== session || !inFlight || inFlight.id !== batchId) return;
      var items = inFlight.items;
      inFlight = null;
      withoutObserver(function () {
        items.forEach(function (item, index) {
          var result = results[index];
          if (typeof result !== "string") return;
          try {
            if (item.kind === "block") {
              applyBlock(item, result);
            } else if (item.kind === "attr") {
              if (item.element.getAttribute(item.name) !== item.value) return;
              attributeSnapshots.push({ element: item.element, name: item.name, original: item.value, translated: result });
              item.element.__clintAttrs = item.element.__clintAttrs || {};
              item.element.__clintAttrs[item.name] = result;
              item.element.setAttribute(item.name, result);
            } else if (item.kind === "title") {
              document.title = result;
            }
          } catch (e) {}
        });
      });
      setTimeout(pump, 0);
    },
    fail: function (id, batchId) {
      if (id !== session || !inFlight || inFlight.id !== batchId) return;
      inFlight = null;
      stop();
    },
    stop: stop,
    restore: function () {
      stop();
      restoreAll();
    },
    active: function () {
      return session;
    }
  };

})();
