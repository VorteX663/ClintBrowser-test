var __qgRanKey = __qgKey + 'r';
var __qgActiveKey = __qgKey + 'a';

function defineHidden(name, value) {
    try {
        Object.defineProperty(window, name, { value: value, configurable: true, enumerable: false, writable: false });
    } catch (e) {}
}

if (window[__qgRanKey] === true) return;
defineHidden(__qgRanKey, true);

var bridge = window.__qgBridge;
try { delete window.__qgBridge; } catch (e) {}
if (!bridge) return;

var topUrl = location.href;
try {
    var ancestors = location.ancestorOrigins;
    if (ancestors && ancestors.length) topUrl = ancestors[ancestors.length - 1];
} catch (e) {}

var raw = '';
try { raw = bridge.cosmetic(location.href, topUrl); } catch (e) {}
if (!raw) return;
defineHidden(__qgActiveKey, true);

var data = null;
try { data = JSON.parse(raw); } catch (e) {}

if (data && data.injectedScript) {
    var scriptletRunner = null;
    try {
        scriptletRunner = new Function(data.injectedScript);
    } catch (e) {
        if (window === window.top) {
            try { bridge.scriptletFallback(location.href); } catch (e2) {}
        }
    }
    if (scriptletRunner) {
        try { scriptletRunner(); } catch (e) {}
    }
}

installWebSocketGuard();

if (!data || data.error) return;

var mutationListeners = [];
var mutationObserver = null;
var sheet = createSheet();

if (data.hideSelectors && data.hideSelectors.length) {
    sheet.add(data.hideSelectors, 'display:none!important');
}

if (data.proceduralActions && data.proceduralActions.length) {
    try { runProceduralActions(data.proceduralActions); } catch (e) {}
}

if (!data.genericHide) {
    try { runGenericHide(JSON.stringify(data.exceptions || [])); } catch (e) {}
}

onMutations(function () { sheet.ensure(); });

function onMutations(listener) {
    mutationListeners.push(listener);
    if (mutationObserver || typeof MutationObserver === 'undefined') return;
    mutationObserver = new MutationObserver(function (records) {
        for (var i = 0; i < mutationListeners.length; i++) {
            try { mutationListeners[i](records); } catch (e) {}
        }
    });
    try {
        mutationObserver.observe(document, {
            childList: true, subtree: true,
            attributes: true, attributeFilter: ['class', 'id']
        });
    } catch (e) {}
}

function createSheet() {
    var constructed = null;
    var styleElement = null;
    var pendingText = '';

    try {
        if ('adoptedStyleSheets' in document && typeof CSSStyleSheet === 'function') {
            constructed = new CSSStyleSheet();
            document.adoptedStyleSheets = Array.prototype.slice.call(document.adoptedStyleSheets).concat([constructed]);
        }
    } catch (e) {
        constructed = null;
    }

    function ensure() {
        if (constructed) {
            try {
                var current = document.adoptedStyleSheets;
                if (Array.prototype.indexOf.call(current, constructed) === -1) {
                    document.adoptedStyleSheets = Array.prototype.slice.call(current).concat([constructed]);
                }
            } catch (e) {}
            return;
        }
        if (!styleElement) {
            if (!pendingText) return;
            styleElement = document.createElement('style');
            styleElement.textContent = pendingText;
        }
        if (!styleElement.isConnected) {
            var parent = document.head || document.documentElement;
            if (parent) parent.appendChild(styleElement);
        }
    }

    function add(selectors, declaration) {
        if (constructed) {
            for (var i = 0; i < selectors.length; i++) {
                try {
                    constructed.insertRule(selectors[i] + '{' + declaration + '}', constructed.cssRules.length);
                } catch (e) {}
            }
            ensure();
            return;
        }
        var text = '';
        for (var j = 0; j < selectors.length; j++) {
            text += selectors[j] + '{' + declaration + '}\n';
        }
        pendingText += text;
        if (styleElement) styleElement.appendChild(document.createTextNode(text));
        ensure();
    }

    return { add: add, ensure: ensure };
}

function installWebSocketGuard() {
    var NativeWebSocket = window.WebSocket;
    if (typeof NativeWebSocket !== 'function' || typeof Proxy !== 'function' || typeof Reflect === 'undefined') return;
    var guarded = new Proxy(NativeWebSocket, {
        construct: function (target, args, newTarget) {
            try {
                if (args.length) {
                    var absolute = new URL(String(args[0]), location.href).href;
                    if (bridge.blockWebSocket(absolute, location.href) === true) {
                        var replaced = Array.prototype.slice.call(args);
                        replaced[0] = 'wss://websocket.blocked.invalid/';
                        return Reflect.construct(target, replaced, newTarget);
                    }
                }
            } catch (e) {}
            return Reflect.construct(target, args, newTarget);
        }
    });
    try { window.WebSocket = guarded; } catch (e) {}
}

function runProceduralActions(rawActions) {
    var rules = [];
    for (var i = 0; i < rawActions.length; i++) {
        try {
            var entry = rawActions[i];
            var rule = typeof entry === 'string' ? JSON.parse(entry) : entry;
            if (!rule || !Array.isArray(rule.selector) || !rule.selector.length) continue;
            var only = rule.selector.length === 1 ? rule.selector[0] : null;
            if (only && only.type === 'css-selector' && !rule.action) {
                sheet.add([only.arg], 'display:none!important');
            } else if (only && only.type === 'css-selector' && rule.action && rule.action.type === 'style') {
                sheet.add([only.arg], rule.action.arg);
            } else {
                rules.push(rule);
            }
        } catch (e) {}
    }
    if (!rules.length) return;

    function parseRegexArg(arg) {
        if (typeof arg !== 'string' || arg.length < 2 || arg.charAt(0) !== '/') return null;
        var lastSlash = arg.lastIndexOf('/');
        if (lastSlash <= 0) return null;
        try {
            return new RegExp(arg.slice(1, lastSlash), arg.slice(lastSlash + 1));
        } catch (e) {
            return null;
        }
    }

    function unquote(value) {
        var trimmed = value.trim();
        if (trimmed.length >= 2) {
            var first = trimmed.charAt(0);
            var last = trimmed.charAt(trimmed.length - 1);
            if ((first === '"' || first === "'") && first === last) return trimmed.slice(1, -1);
        }
        return trimmed;
    }

    function containsTextOrRegex(text, arg) {
        var re = parseRegexArg(arg);
        return re ? re.test(text) : text.indexOf(arg) !== -1;
    }

    function equalsTextOrRegex(text, arg) {
        var re = parseRegexArg(arg);
        return re ? re.test(text) : text === arg;
    }

    function splitAttrArg(arg) {
        var inRegex = false;
        var quote = '';
        for (var i = 0; i < arg.length; i++) {
            var c = arg.charAt(i);
            if (quote || inRegex) {
                if (c === '\\') { i++; continue; }
                if (quote && c === quote) quote = '';
                else if (inRegex && c === '/') inRegex = false;
                continue;
            }
            if (c === '"' || c === "'") { quote = c; continue; }
            if (c === '/' && i === 0) { inRegex = true; continue; }
            if (c === '=') return [arg.slice(0, i), arg.slice(i + 1)];
        }
        return [arg, null];
    }

    function matchesAttr(node, arg) {
        var parts = splitAttrArg(arg);
        var nameArg = unquote(parts[0]);
        var valueArg = parts[1] === null ? null : unquote(parts[1]);
        var nameRe = parseRegexArg(nameArg);
        var attrs = node.attributes;
        if (!attrs) return false;
        for (var i = 0; i < attrs.length; i++) {
            var attr = attrs[i];
            var nameMatches = nameRe ? nameRe.test(attr.name) : attr.name === nameArg;
            if (!nameMatches) continue;
            if (valueArg === null || equalsTextOrRegex(attr.value, valueArg)) return true;
        }
        return false;
    }

    function matchesCss(node, pseudo, arg) {
        var colon = arg.indexOf(':');
        if (colon === -1) return false;
        var prop = arg.slice(0, colon).trim();
        var value = arg.slice(colon + 1).trim();
        try {
            var style = pseudo ? getComputedStyle(node, pseudo) : getComputedStyle(node);
            return equalsTextOrRegex(style.getPropertyValue(prop).trim(), value);
        } catch (e) {
            return false;
        }
    }

    function upward(node, arg) {
        var trimmed = arg.trim();
        var count = parseInt(trimmed, 10);
        if (!isNaN(count) && String(count) === trimmed) {
            var cur = node;
            for (var i = 0; i < count && cur; i++) cur = cur.parentElement;
            return cur;
        }
        try {
            return node.parentElement ? node.parentElement.closest(trimmed) : null;
        } catch (e) {
            return null;
        }
    }

    function toArray(nodeList) {
        return Array.prototype.slice.call(nodeList);
    }

    function queryDocument(arg) {
        var selector = arg.replace(/^\s+/, '');
        if (/^[>+~]/.test(selector)) selector = ':root ' + selector;
        try { return toArray(document.querySelectorAll(selector)); } catch (e) { return []; }
    }

    function queryRelative(nodes, arg) {
        var seen = new Set();
        var out = [];
        nodes.forEach(function (node) {
            var scope;
            var selector;
            var parent = node.parentElement;
            if (parent) {
                var index = 1;
                var sibling = node;
                while ((sibling = sibling.previousElementSibling)) index++;
                scope = parent;
                selector = ':scope > :nth-child(' + index + ')' + arg;
            } else {
                scope = document;
                selector = ':root' + arg;
            }
            try {
                var found = scope.querySelectorAll(selector);
                for (var i = 0; i < found.length; i++) {
                    if (!seen.has(found[i])) {
                        seen.add(found[i]);
                        out.push(found[i]);
                    }
                }
            } catch (e) {}
        });
        return out;
    }

    function applyOperator(op, nodes) {
        var type = op.type, arg = op.arg;

        if (type === 'css-selector') {
            return nodes === null ? queryDocument(arg) : queryRelative(nodes, arg);
        }

        if (type === 'xpath') {
            var out = [];
            var contexts = nodes === null ? [document] : nodes;
            contexts.forEach(function (ctx) {
                try {
                    var result = document.evaluate(arg, ctx, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                    for (var j = 0; j < result.snapshotLength; j++) {
                        var item = result.snapshotItem(j);
                        if (item && item.nodeType === 1 && out.indexOf(item) === -1) out.push(item);
                    }
                } catch (e) {}
            });
            return out;
        }

        if (type === 'matches-path') {
            return containsTextOrRegex(location.pathname + location.search, arg) ? nodes : [];
        }

        if (nodes === null) return [];
        switch (type) {
            case 'has-text':
                return nodes.filter(function (n) { return containsTextOrRegex(n.textContent || '', arg); });
            case 'matches-attr':
                return nodes.filter(function (n) { return matchesAttr(n, arg); });
            case 'matches-css':
                return nodes.filter(function (n) { return matchesCss(n, null, arg); });
            case 'matches-css-before':
                return nodes.filter(function (n) { return matchesCss(n, '::before', arg); });
            case 'matches-css-after':
                return nodes.filter(function (n) { return matchesCss(n, '::after', arg); });
            case 'min-text-length':
                var min = parseInt(arg, 10) || 0;
                return nodes.filter(function (n) { return (n.textContent || '').length >= min; });
            case 'upward':
                var mapped = [];
                nodes.forEach(function (n) {
                    var a = upward(n, arg);
                    if (a && mapped.indexOf(a) === -1) mapped.push(a);
                });
                return mapped;
            default:
                return [];
        }
    }

    function evaluateRule(rule) {
        var nodes = null;
        for (var i = 0; i < rule.selector.length; i++) {
            nodes = applyOperator(rule.selector[i], nodes);
            if (nodes !== null && !nodes.length) return [];
        }
        return nodes || [];
    }

    function applyAction(nodes, action) {
        if (!action) {
            nodes.forEach(function (n) {
                if (n.style) n.style.setProperty('display', 'none', 'important');
            });
            return;
        }
        switch (action.type) {
            case 'remove':
                nodes.forEach(function (n) { n.remove(); });
                break;
            case 'style':
                nodes.forEach(function (n) { if (n.style) n.style.cssText += ';' + action.arg; });
                break;
            case 'remove-attr':
                nodes.forEach(function (n) { n.removeAttribute(action.arg); });
                break;
            case 'remove-class':
                nodes.forEach(function (n) { if (n.classList) n.classList.remove(action.arg); });
                break;
        }
    }

    function runOnce() {
        rules.forEach(function (rule) {
            try {
                var nodes = evaluateRule(rule);
                if (nodes.length) applyAction(nodes, rule.action);
            } catch (e) {}
        });
    }

    runOnce();

    var pending = false;
    onMutations(function () {
        if (pending) return;
        pending = true;
        setTimeout(function () { pending = false; runOnce(); }, 100);
    });
}

function runGenericHide(exceptionsJson) {
    var seenClasses = Object.create(null);
    var seenIds = Object.create(null);
    var pendingClasses = [];
    var pendingIds = [];
    var scheduled = false;

    function collect(el) {
        if (!el || el.nodeType !== 1) return;
        var id = el.id;
        if (id && typeof id === 'string' && !seenIds[id]) {
            seenIds[id] = true;
            pendingIds.push(id);
        }
        var classList = el.classList;
        if (classList) {
            for (var i = 0; i < classList.length; i++) {
                var c = classList[i];
                if (c && !seenClasses[c]) {
                    seenClasses[c] = true;
                    pendingClasses.push(c);
                }
            }
        }
    }

    function collectTree(node) {
        if (!node || node.nodeType !== 1) return;
        collect(node);
        if (!node.querySelectorAll) return;
        var all = node.querySelectorAll('[class],[id]');
        for (var i = 0; i < all.length; i++) collect(all[i]);
    }

    function flush() {
        scheduled = false;
        if (!pendingClasses.length && !pendingIds.length) return;
        var classes = pendingClasses;
        var ids = pendingIds;
        pendingClasses = [];
        pendingIds = [];
        try {
            var selectors = JSON.parse(bridge.hiddenClassIdSelectors(
                JSON.stringify(classes), JSON.stringify(ids), exceptionsJson
            ));
            if (selectors && selectors.length) sheet.add(selectors, 'display:none!important');
        } catch (e) {}
    }

    function schedule() {
        if (scheduled) return;
        scheduled = true;
        setTimeout(flush, 50);
    }

    collectTree(document.documentElement);
    flush();

    onMutations(function (records) {
        for (var i = 0; i < records.length; i++) {
            var record = records[i];
            if (record.type === 'attributes') {
                collect(record.target);
            } else {
                var added = record.addedNodes;
                for (var j = 0; j < added.length; j++) collectTree(added[j]);
            }
        }
        if (pendingClasses.length || pendingIds.length) schedule();
    });
}
