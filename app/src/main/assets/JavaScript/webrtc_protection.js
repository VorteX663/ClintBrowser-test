(function () {
    try {
        var Native = window.RTCPeerConnection || window.webkitRTCPeerConnection;
        if (!Native) return;
        var Wrapped = function (config, constraints) {
            var safe = Object.assign({}, config || {});
            safe.iceTransportPolicy = 'relay';
            return new Native(safe, constraints);
        };
        Wrapped.prototype = Native.prototype;
        Wrapped.generateCertificate = Native.generateCertificate;
        window.RTCPeerConnection = Wrapped;
        if (window.webkitRTCPeerConnection) window.webkitRTCPeerConnection = Wrapped;
    } catch (e) {}
})();
