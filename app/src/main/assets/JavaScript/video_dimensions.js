(function(){
    function collect(root, out) {
        if (!root || !root.querySelectorAll) return out;
        if (root.tagName === 'VIDEO') out.push(root);
        var videos = root.querySelectorAll('video');
        for (var i = 0; i < videos.length; i++) out.push(videos[i]);
        if (root.shadowRoot) collect(root.shadowRoot, out);
        var all = root.querySelectorAll('*');
        for (var j = 0; j < all.length; j++) {
            if (all[j].shadowRoot) collect(all[j].shadowRoot, out);
        }
        return out;
    }
    function fullscreenTarget() {
        var el = document.fullscreenElement || document.webkitFullscreenElement || null;
        while (el && el.shadowRoot && el.shadowRoot.fullscreenElement) el = el.shadowRoot.fullscreenElement;
        return el;
    }
    function visibleArea(v) {
        var r = v.getBoundingClientRect();
        var w = Math.min(r.right, window.innerWidth) - Math.max(r.left, 0);
        var h = Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0);
        return w > 0 && h > 0 ? w * h : 0;
    }
    function pick(list) {
        var best = null, bestScore = -1, bestArea = -1;
        for (var i = 0; i < list.length; i++) {
            var v = list[i], a = visibleArea(v), s = 0;
            if (v.videoWidth > 0 && v.videoHeight > 0) s += 8;
            if (!v.paused && !v.ended) s += 4;
            if (a > 0) s += 2;
            if (s > bestScore || (s === bestScore && a > bestArea)) { best = v; bestScore = s; bestArea = a; }
        }
        return best;
    }
    try {
        var fs = fullscreenTarget();
        var v = fs ? pick(collect(fs, [])) : null;
        if (!v || !(v.videoWidth > 0)) v = pick(collect(document, [])) || v;
        return v && v.videoWidth > 0 && v.videoHeight > 0 ? v.videoWidth + ',' + v.videoHeight : '0,0';
    } catch (e) {
        return '0,0';
    }
})()
