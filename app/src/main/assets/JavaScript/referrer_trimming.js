(function () {
    function insert() {
        var head = document.head;
        if (!head) return false;
        var meta = document.createElement('meta');
        meta.name = 'referrer';
        meta.content = 'strict-origin';
        head.insertBefore(meta, head.firstChild);
        return true;
    }
    try {
        if (insert()) return;
        var observer = new MutationObserver(function () {
            if (insert()) observer.disconnect();
        });
        observer.observe(document, { childList: true, subtree: true });
    } catch (e) {}
})();
