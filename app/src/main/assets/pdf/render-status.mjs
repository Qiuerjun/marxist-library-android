// Report the active page to Android. A resize can finish rendering without a matching event.
// Usage: watchPage(send, hasFrame).follow(pageNumber).
export function watchPage(send, hasFrame) {
    let page = 0;
    let reported = false;
    let timer = 0;

    function check() {
        clearTimeout(timer);
        if (!page || reported) return;
        if (hasFrame(page)) {
            reported = true;
            send("painted", { page });
            return;
        }
        timer = setTimeout(check, 220);
    }

    return {
        get page() { return page; },
        follow(number) {
            if (page !== number) {
                page = number;
                reported = false;
                send("rendering", { page });
            }
            check();
        },
        stop() {
            page = 0;
            clearTimeout(timer);
        }
    };
}
