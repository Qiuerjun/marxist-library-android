// Leave native long-press selection and zoomed-page panning on the document surface.
export function connectGestures(surface, { step, canTurn, edgeTap, zoom, scale }) {
    let start = null;
    let pinch = null;
    let frame = 0;
    let moved = null;

    surface.addEventListener("touchstart", event => {
        if (event.touches.length === 2) {
            event.preventDefault();
            start = null;
            pinch = { distance: distance(event.touches), scale: scale() };
            return;
        }
        if (event.touches.length !== 1 || event.target.closest("a, button, input")) {
            start = null;
            return;
        }
        const touch = event.touches[0];
        start = { x: touch.clientX, y: touch.clientY, at: performance.now() };
    }, { passive: false });

    surface.addEventListener("touchmove", event => {
        if (!pinch || event.touches.length !== 2) return;
        event.preventDefault();
        const [a, b] = event.touches;
        moved = {
            scale: pinch.scale * distance(event.touches) / Math.max(1, pinch.distance),
            origin: [(a.clientX + b.clientX) / 2, (a.clientY + b.clientY) / 2]
        };
        if (!frame) frame = requestAnimationFrame(() => {
            frame = 0;
            if (moved) zoom(moved.scale, moved.origin);
        });
    }, { passive: false });

    surface.addEventListener("touchend", event => {
        if (pinch) {
            finishPinch();
            return;
        }
        const point = start;
        start = null;
        if (!point || !canTurn() || event.touches.length || !window.getSelection()?.isCollapsed) return;
        const touch = event.changedTouches[0];
        const x = touch.clientX - point.x;
        const y = touch.clientY - point.y;
        const duration = performance.now() - point.at;
        if (Math.abs(x) > 52 && Math.abs(x) > Math.abs(y) * 1.5) {
            step(x < 0 ? 1 : -1);
        } else if (edgeTap() && Math.abs(x) < 9 && Math.abs(y) < 9 && duration < 260) {
            const bounds = surface.getBoundingClientRect();
            const edge = Math.min(50, bounds.width * .14);
            if (point.x - bounds.left < edge) step(-1);
            else if (bounds.right - point.x < edge) step(1);
        }
    });

    surface.addEventListener("touchcancel", () => {
        start = null;
        finishPinch();
    });

    function finishPinch() {
        if (frame) cancelAnimationFrame(frame);
        if (moved) zoom(moved.scale, moved.origin);
        frame = 0;
        pinch = null;
        moved = null;
    }
}

function distance(touches) {
    const [a, b] = touches;
    return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
}
