(function () {
    try {
        Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', {
            get: function () { return true; },
            configurable: true,
            enumerable: true
        });
    } catch (e) {}
})();
