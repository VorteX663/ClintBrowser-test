(function () {
    'use strict';

    if (window.__clintBackgroundPlayback) return;

    var bridge = window.ClintBackgroundPlaybackBridge;
    var isTop = window === window.top;
    var frameId = Math.random().toString(36).slice(2) + Date.now().toString(36);
    var COMMAND_KEY = '__clintBackgroundPlaybackCommand';
    var MAX_DATA_ARTWORK = 1000000;
    var backgroundPlayback = __CLINT_BACKGROUND_PLAYBACK__;
    var lastMedia = null;
    var reported = null;
    var handlers = {};
    var positionState = null;
    var infoTimer = 0;
    var lastInfo = '';

    function spoof(target, name, value) {
        var descriptor = Object.getOwnPropertyDescriptor(target, name);
        if (!descriptor || !descriptor.get) return;
        try {
            Object.defineProperty(target, name, {
                configurable: true,
                enumerable: descriptor.enumerable,
                get: function () { return backgroundPlayback ? value : descriptor.get.call(this); }
            });
        } catch (e) {}
    }

    spoof(Document.prototype, 'hidden', false);
    spoof(Document.prototype, 'visibilityState', 'visible');
    spoof(Document.prototype, 'webkitHidden', false);
    spoof(Document.prototype, 'webkitVisibilityState', 'visible');

    function swallow(event) {
        if (backgroundPlayback) event.stopImmediatePropagation();
    }

    ['visibilitychange', 'webkitvisibilitychange'].forEach(function (type) {
        window.addEventListener(type, swallow, true);
        document.addEventListener(type, swallow, true);
    });

    function isPlaying(media) {
        return !media.paused && !media.ended;
    }

    function allMedia() {
        try {
            return Array.prototype.slice.call(document.querySelectorAll('video,audio'));
        } catch (e) {
            return [];
        }
    }

    function anyPlaying() {
        if (lastMedia && isPlaying(lastMedia)) return true;
        return allMedia().some(isPlaying);
    }

    function report(state, force) {
        if ((state === reported && !force) || !bridge) return;
        reported = state;
        try { bridge.onMediaState(frameId, state, isTop); } catch (e) {}
        scheduleInfo();
    }

    function refresh() {
        report(anyPlaying() ? 'playing' : 'paused');
    }

    function absoluteUrl(src) {
        if (!src || typeof src !== 'string') return '';
        if (src.indexOf('data:image/') === 0) return src.length <= MAX_DATA_ARTWORK ? src : '';
        try {
            var url = new URL(src, document.baseURI);
            return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : '';
        } catch (e) {
            return '';
        }
    }

    function artworkArea(item) {
        var best = 0;
        String(item.sizes || '').split(/\s+/).forEach(function (size) {
            var parts = size.toLowerCase().split('x');
            var area = (parseInt(parts[0], 10) || 0) * (parseInt(parts[1], 10) || 0);
            if (area > best) best = area;
        });
        return best;
    }

    function pickArtwork(list) {
        if (!list || !list.length) return '';
        var items = Array.prototype.slice.call(list).filter(function (item) { return item && absoluteUrl(item.src); });
        if (!items.length) return '';
        items.sort(function (a, b) { return artworkArea(b) - artworkArea(a); });
        return absoluteUrl(items[0].src);
    }

    function metaContent(selector) {
        try {
            var node = document.querySelector(selector);
            return node ? node.getAttribute('content') || node.getAttribute('href') || '' : '';
        } catch (e) {
            return '';
        }
    }

    function fallbackArtwork() {
        var poster = lastMedia && lastMedia.poster;
        return absoluteUrl(poster) ||
            absoluteUrl(metaContent('meta[property="og:image"]')) ||
            absoluteUrl(metaContent('meta[name="twitter:image"]')) ||
            absoluteUrl(metaContent('link[rel="apple-touch-icon"]'));
    }

    function finiteOr(value, fallback) {
        return typeof value === 'number' && isFinite(value) ? value : fallback;
    }

    function currentPosition() {
        if (positionState && finiteOr(positionState.duration, -1) > 0) {
            var rate = finiteOr(positionState.playbackRate, 1);
            var elapsed = anyPlaying() ? (Date.now() - positionState.at) / 1000 * rate : 0;
            return {
                duration: positionState.duration,
                position: Math.min(positionState.duration, finiteOr(positionState.position, 0) + elapsed),
                rate: rate
            };
        }
        if (lastMedia) {
            return {
                duration: finiteOr(lastMedia.duration, -1),
                position: finiteOr(lastMedia.currentTime, 0),
                rate: finiteOr(lastMedia.playbackRate, 1)
            };
        }
        return { duration: -1, position: 0, rate: 1 };
    }

    function collectInfo() {
        var session = navigator.mediaSession;
        var metadata = session && session.metadata;
        var position = currentPosition();
        return {
            title: metadata && metadata.title ? String(metadata.title) : '',
            artist: metadata && metadata.artist ? String(metadata.artist) : '',
            album: metadata && metadata.album ? String(metadata.album) : '',
            artwork: pickArtwork(metadata && metadata.artwork) || fallbackArtwork(),
            duration: position.duration,
            position: position.position,
            rate: position.rate,
            hasMedia: !!lastMedia,
            canSeek: position.duration > 0 && (!!handlers.seekto || !!lastMedia),
            canNext: !!handlers.nexttrack,
            canPrevious: !!handlers.previoustrack
        };
    }

    function sendInfo() {
        infoTimer = 0;
        if (!bridge || (!lastMedia && !handlers.play)) return;
        var json;
        try { json = JSON.stringify(collectInfo()); } catch (e) { return; }
        if (json === lastInfo) return;
        lastInfo = json;
        try { bridge.onMediaInfo(frameId, json); } catch (e) {}
    }

    function scheduleInfo() {
        if (infoTimer) return;
        infoTimer = setTimeout(sendInfo, 60);
    }

    function onMediaEvent(event) {
        var target = event.target;
        if (!(target instanceof HTMLMediaElement)) return;
        if (event.type === 'playing' || event.type === 'play') {
            lastMedia = target;
            report('playing', event.type === 'playing');
        } else if (event.type === 'pause' || event.type === 'ended' || event.type === 'emptied') {
            setTimeout(refresh, 0);
        }
        if (target === lastMedia) scheduleInfo();
    }

    var MEDIA_EVENTS = ['play', 'playing', 'pause', 'ended', 'emptied', 'seeked', 'ratechange', 'durationchange', 'loadedmetadata'];
    var tracked = new WeakSet();

    MEDIA_EVENTS.forEach(function (type) {
        document.addEventListener(type, onMediaEvent, true);
    });

    function track(media) {
        if (tracked.has(media)) return;
        tracked.add(media);
        MEDIA_EVENTS.forEach(function (type) {
            media.addEventListener(type, onMediaEvent);
        });
    }

    var nativePlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
        try { track(this); } catch (e) {}
        return nativePlay.apply(this, arguments);
    };

    function wrapSetter(proto, name) {
        var descriptor = proto && Object.getOwnPropertyDescriptor(proto, name);
        if (!descriptor || !descriptor.set) return;
        try {
            Object.defineProperty(proto, name, {
                configurable: true,
                enumerable: descriptor.enumerable,
                get: descriptor.get,
                set: function (value) {
                    descriptor.set.call(this, value);
                    scheduleInfo();
                }
            });
        } catch (e) {}
    }

    function hookMediaSession() {
        if (!window.MediaSession || !navigator.mediaSession) return;
        var proto = window.MediaSession.prototype;
        wrapSetter(proto, 'metadata');
        if (window.MediaMetadata) {
            ['title', 'artist', 'album', 'artwork'].forEach(function (name) {
                wrapSetter(window.MediaMetadata.prototype, name);
            });
        }
        var nativeSetActionHandler = proto.setActionHandler;
        if (nativeSetActionHandler) {
            proto.setActionHandler = function (action, handler) {
                handlers[action] = typeof handler === 'function' ? handler : null;
                scheduleInfo();
                return nativeSetActionHandler.apply(this, arguments);
            };
        }
        var nativeSetPositionState = proto.setPositionState;
        if (nativeSetPositionState) {
            proto.setPositionState = function (state) {
                positionState = state && typeof state === 'object' ? {
                    duration: state.duration,
                    position: state.position,
                    playbackRate: state.playbackRate,
                    at: Date.now()
                } : null;
                scheduleInfo();
                return nativeSetPositionState.apply(this, arguments);
            };
        }
    }

    try { hookMediaSession(); } catch (e) {}

    function callHandler(action, details) {
        var handler = handlers[action];
        if (!handler) return false;
        try {
            var payload = { action: action };
            for (var key in details) payload[key] = details[key];
            handler(payload);
            return true;
        } catch (e) {
            return false;
        }
    }

    var pausedByCommand = [];

    function pauseAll() {
        var targets = allMedia();
        if (lastMedia && targets.indexOf(lastMedia) < 0) targets.push(lastMedia);
        pausedByCommand = targets.filter(isPlaying);
        targets.forEach(function (media) {
            try { media.pause(); } catch (e) {}
        });
    }

    function play(media) {
        try {
            var result = media.play();
            if (result && result.catch) result.catch(function () {});
        } catch (e) {}
    }

    function resume(targetFrame) {
        var targets = pausedByCommand;
        pausedByCommand = [];
        if (targets.length) {
            targets.forEach(play);
        } else if (targetFrame === frameId) {
            if (lastMedia) play(lastMedia);
            else callHandler('play', {});
        }
    }

    function seek(seconds) {
        var time = Math.max(0, finiteOr(seconds, 0));
        if (callHandler('seekto', { seekTime: time, fastSeek: false })) return;
        if (lastMedia) {
            try { lastMedia.currentTime = time; } catch (e) {}
        }
    }

    function relay(command) {
        var message = {};
        message[COMMAND_KEY] = command;
        for (var i = 0; i < window.frames.length; i++) {
            try { window.frames[i].postMessage(message, '*'); } catch (e) {}
        }
    }

    function run(command) {
        if (!command || typeof command !== 'object') return;
        var mine = command.frame === frameId;
        switch (command.action) {
            case 'play': resume(command.frame); break;
            case 'pause': pauseAll(); break;
            case 'next': if (mine) callHandler('nexttrack', {}); break;
            case 'previous': if (mine) callHandler('previoustrack', {}); break;
            case 'seek': if (mine) seek(command.time); break;
            case 'config': backgroundPlayback = !!command.backgroundPlayback; break;
        }
        relay({
            action: command.action,
            frame: command.frame,
            time: command.time,
            backgroundPlayback: command.backgroundPlayback
        });
    }

    window.addEventListener('message', function (event) {
        var data = event.data;
        if (!data || typeof data !== 'object' || !data[COMMAND_KEY]) return;
        if (event.source !== window.parent) return;
        run(data[COMMAND_KEY]);
    });

    window.addEventListener('pagehide', function () {
        reported = null;
        lastInfo = '';
        report(isTop ? 'gone' : 'paused');
    });

    window.addEventListener('pageshow', function (event) {
        if (event.persisted) refresh();
    });

    Object.defineProperty(window, '__clintBackgroundPlayback', {
        value: Object.freeze({ run: run }),
        configurable: false,
        enumerable: false,
        writable: false
    });
})();
