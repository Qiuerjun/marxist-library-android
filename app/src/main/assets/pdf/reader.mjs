import {
    AnnotationEditorType, AnnotationMode, GlobalWorkerOptions, PDFDataRangeTransport, PasswordResponses, getDocument
} from "./vendor/build/pdf.min.mjs";
import { showContents } from "./contents.mjs";
import { connectGestures } from "./gestures.mjs";
import { watchPage } from "./render-status.mjs";

// The viewer bundle shares the PDF.js core initialized by the first import.
const { EventBus, FindState, LinkTarget, PDFFindController, PDFLinkService, PDFViewer, RenderingStates, ScrollMode } =
    await import("./vendor/web/pdf_viewer.mjs");
const element = id => document.getElementById(id);
const address = new URLSearchParams(location.search);
const surface = element("viewerContainer");
const pages = element("viewer");
const bus = new EventBus();
const links = new PDFLinkService({ eventBus: bus, externalLinkTarget: LinkTarget.SELF, ignoreDestinationZoom: true });
const search = new PDFFindController({ eventBus: bus, linkService: links });
const viewer = new PDFViewer({
    container: surface,
    viewer: pages,
    eventBus: bus,
    linkService: links,
    findController: search,
    annotationMode: AnnotationMode.ENABLE,
    annotationEditorMode: AnnotationEditorType.DISABLE,
    enablePermissions: true,
    enableAutoLinking: false,
    removePageBorders: true,
    maxCanvasPixels: 6_000_000,
    maxCanvasDim: 4096,
    minDurationToUpdateCanvas: 180,
    imageResourcesPath: "vendor/web/images/"
});
links.setViewer(viewer);
GlobalWorkerOptions.workerSrc = new URL("vendor/build/pdf.worker.min.mjs", location.href).href;

const rendering = watchPage(send, hasPaintedPage);
const options = {
    mode: address.get("mode") === "PAGED" ? "PAGED" : "CONTINUOUS",
    animation: ["SLIDE", "FADE", "NONE"].includes(address.get("animation")) ? address.get("animation") : "SLIDE",
    edges: address.get("edges") === "true"
};
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
const textPages = new Map();
let publication = null;
let task = null;
let ready = false;
let restoring = true;
let restoringPage = 0;
let failed = false;
let fit = "page-width";
let saveTimer = 0;
let turn = null;
let activeAnimation = null;
let outline = null;
let labels = null;
let searched = "";
let passwordReply = null;

function send(type, values = {}) {
    window.archivePdfAndroid?.postMessage(JSON.stringify({ type, ...values }));
}

function hasPaintedPage(number) {
    const page = viewer.getPageView(number - 1);
    if (page?.renderingState !== RenderingStates.FINISHED) return false;
    const frame = page.canvas ?? page.div.querySelector(".xfaLayer");
    if (!frame?.isConnected) return false;
    const bounds = frame.getBoundingClientRect();
    const view = surface.getBoundingClientRect();
    return bounds.width > 0 && bounds.height > 0 &&
        bounds.bottom > view.top && bounds.top < view.bottom &&
        bounds.right > view.left && bounds.left < view.right;
}

function appearance(colors) {
    const root = document.documentElement;
    for (const key of ["paper", "ink", "accent", "rule"]) {
        if (/^#[0-9a-f]{6}$/i.test(colors[key])) root.style.setProperty("--" + key, colors[key]);
    }
    root.dataset.theme = colors.dark ? "dark" : "light";
}

function readingLocation() {
    if (!ready || restoring || failed) return null;
    const page = readingPage();
    const sheet = viewer.getPageView(page - 1)?.div;
    const bounds = sheet?.getBoundingClientRect();
    const fraction = bounds?.height ? (surface.getBoundingClientRect().top - bounds.top) / bounds.height : 0;
    return { page, offset: Math.round(Math.max(0, Math.min(1, fraction)) * 10_000) };
}

function readingPage() {
    let page = viewer.currentPageNumber;
    if (viewer.scrollMode !== ScrollMode.VERTICAL) return page;
    const top = surface.getBoundingClientRect().top;
    // The viewer reports the most visible page. Save the line at the top of the viewport instead.
    while (page > 1 && viewer.getPageView(page - 2)?.div.getBoundingClientRect().bottom > top + 2) page--;
    return page;
}

function reportPosition() {
    clearTimeout(saveTimer);
    if (restoring || !ready) return;
    saveTimer = setTimeout(() => {
        const found = readingLocation();
        if (found) send("position", found);
    }, 370);
}

function dismiss() {
    const open = document.querySelector("dialog[open]");
    if (open?.id === "password-dialog") cancelPassword();
    else if (open) open.close();
    else if (!element("searchbar").hidden) toggleSearch(false);
    else return false;
    return true;
}

function configure(value) {
    const modeChanged = options.mode !== value.mode;
    Object.assign(options, value);
    if (modeChanged && ready && !restoring && !failed) setMode(options.mode, true);
}

window.archivePdf = { appearance, settings: configure, location: readingLocation, dismiss };
appearance({ ...Object.fromEntries(address), dark: address.get("dark") === "true" });
send("engine");

class LocalDocument extends PDFDataRangeTransport {
    constructor(length) {
        super(length, null, true);
        this.requests = new AbortController();
    }

    async requestDataRange(begin, end) {
        try {
            // Read only the requested part of the file already saved by the Android app.
            const response = await fetch("/pdf/file?start=" + begin + "&end=" + end, { signal: this.requests.signal });
            if (!response.ok) throw new Error("missing PDF slice");
            const bytes = new Uint8Array(await response.arrayBuffer());
            if (bytes.length !== end - begin) throw new Error("truncated PDF slice");
            this.onDataRange(begin, bytes);
        } catch (error) {
            if (error.name !== "AbortError") {
                console.warn("Local PDF range could not be read", error);
                showProblem("本地 PDF 读取不完整。可重试或重新下载。");
            }
        }
    }

    abort() {
        this.requests.abort();
    }
}

function showProblem(message) {
    if (failed) return;
    failed = true;
    rendering.stop();
    send("problem", { message });
    task?.destroy().catch(() => {});
}

function cancelPassword() {
    element("password-dialog").close();
    showProblem("打开此 PDF 需要文档密码");
}

function showPassword(reply, reason) {
    passwordReply = reply;
    const input = element("password");
    input.value = "";
    element("password-label").textContent = reason === PasswordResponses.INCORRECT_PASSWORD ? "密码不正确，请重试" : "请输入文档密码";
    const dialog = element("password-dialog");
    if (!dialog.open) dialog.showModal();
    send("password");
    input.focus();
}

function refresh() {
    if (!ready || failed) return;
    rendering.follow(restoringPage || viewer.currentPageNumber);
    const page = readingPage();
    const label = labels?.[page - 1];
    element("previous").disabled = restoring || page <= 1;
    element("next").disabled = restoring || page >= publication.numPages;
    const pageControl = element("page");
    pageControl.textContent = page + " / " + publication.numPages;
    pageControl.setAttribute("aria-label", "第 " + page + " 页，共 " + publication.numPages + " 页，点按跳页");
    element("fit").textContent = viewer.currentScaleValue === "page-width" ? "适宽" :
        viewer.currentScaleValue === "page-fit" ? "整页" : Math.round(viewer.currentScale * 100) + "%";
    const status = element("status");
    const notes = [];
    if (label && label !== String(page)) notes.push("书内页码 " + label);
    if (textPages.get(page) === false) notes.push("本页无可选文字");
    const note = notes.join(" · ");
    status.hidden = !note;
    if (status.textContent !== note) status.textContent = note;
    surface.dataset.swipe = String(viewer.scrollMode === ScrollMode.PAGE && surface.scrollWidth <= surface.clientWidth + 3);
}

function restoreOffset(page, offset) {
    const sheet = viewer.getPageView(page - 1)?.div;
    if (!sheet || !offset) return;
    const top = sheet.getBoundingClientRect().top - surface.getBoundingClientRect().top;
    surface.scrollTop += top + sheet.offsetHeight * offset / 10_000;
}

function fitViewport() {
    if (fit && surface.clientWidth > 0 && surface.clientHeight > 0) viewer.currentScaleValue = fit;
}

// A short final sheet must be able to reach the top of the viewport when restoring its page.
function alignLastPage() {
    if (!ready || failed) return;
    const last = viewer.getPageView(publication.numPages - 1).div;
    const gap = viewer.scrollMode === ScrollMode.VERTICAL ? Math.max(0, surface.clientHeight - last.offsetHeight) : 0;
    pages.style.paddingBottom = gap ? gap + "px" : "";
}

async function prepareStart(page, offset) {
    const view = viewer.getPageView(page - 1);
    if (!view.pdfPage) view.setPdfPage(await publication.getPage(page));
    go(page);
    fitViewport();
    if (offset && view.renderingState !== RenderingStates.FINISHED) {
        await new Promise(resolve => {
            const listener = ({ pageNumber, cssTransform, isDetailView }) => {
                if (pageNumber === page && !cssTransform && !isDetailView) finish();
            };
            const timeout = setTimeout(finish, 2_500);
            function finish() {
                clearTimeout(timeout);
                bus.off("pagerendered", listener);
                resolve();
            }
            bus.on("pagerendered", listener);
        });
    }
    // Page dimensions are known before seeking to the saved fraction.
    await new Promise(requestAnimationFrame);
    alignLastPage();
    viewer.scrollPageIntoView({ pageNumber: page });
    restoreOffset(page, offset);
}

function motion() {
    return reducedMotion.matches ? "NONE" : options.animation;
}

function animatePage(page) {
    if (!turn || turn.page !== page) return;
    const sheet = viewer.getPageView(page - 1)?.div;
    const style = motion();
    if (!sheet || style === "NONE") {
        turn = null;
        return;
    }
    const first = style === "SLIDE" ? { transform: "translateX(" + turn.direction * 54 + "px)", opacity: .65 } :
        { opacity: .15 };
    activeAnimation?.cancel();
    activeAnimation = sheet.animate([first, { transform: "translateX(0)", opacity: 1 }], {
        duration: style === "SLIDE" ? 230 : 185, easing: "cubic-bezier(.21,.66,.38,1)"
    });
    turn = null;
}

function go(page, animated = false) {
    if (!ready || !Number.isInteger(page) || page < 1 || page > publication.numPages) return;
    const before = readingPage();
    activeAnimation?.cancel();
    turn = null;
    if (animated && page !== before && motion() !== "NONE") {
        turn = { page, direction: page > before ? 1 : -1 };
    }
    if (page === publication.numPages) alignLastPage();
    viewer.scrollPageIntoView({ pageNumber: page });
    const view = viewer.getPageView(page - 1);
    if (view?.renderingState === RenderingStates.FINISHED) animatePage(page);
    refresh();
    reportPosition();
}

function setMode(mode, keepPlace) {
    const found = keepPlace ? readingLocation() : null;
    turn = null;
    activeAnimation?.cancel();
    viewer.scrollMode = mode === "PAGED" ? ScrollMode.PAGE : ScrollMode.VERTICAL;
    fit = mode === "PAGED" ? "page-fit" : "page-width";
    fitViewport();
    alignLastPage();
    if (found) {
        go(found.page);
        restoreOffset(found.page, found.offset);
    }
    refresh();
}

function zoom(scale, origin = undefined) {
    if (!ready || !Number.isFinite(scale) || viewer.currentScale <= 0) return;
    const target = Math.max(.12, Math.min(6, scale));
    if (Math.abs(target - viewer.currentScale) < .012) return;
    fit = null;
    viewer.updateScale({ scaleFactor: target / viewer.currentScale, origin, drawingDelay: 155 });
    refresh();
}

function toggleSearch(open) {
    const form = element("searchbar");
    form.hidden = !open;
    element("search").setAttribute("aria-expanded", String(open));
    if (open) element("query").focus();
    else {
        element("query").blur();
        bus.dispatch("findbarclose", { source: form });
    }
}

function find(previous = false) {
    const query = element("query").value.trim();
    if (!query) {
        element("matches").textContent = "";
        bus.dispatch("findbarclose", { source: element("searchbar") });
        searched = "";
        return;
    }
    element("matches").textContent = "查找中…";
    bus.dispatch("find", {
        source: element("searchbar"),
        type: query === searched ? "again" : "",
        query, findPrevious: previous, highlightAll: true, caseSensitive: false,
        entireWord: false, matchDiacritics: false
    });
    searched = query;
}

function findCount({ current, total }) {
    if (total > 0) element("matches").textContent = current + " / " + total;
}

const outlineDialog = element("outline-dialog");
const jumpDialog = element("jump-dialog");
document.querySelectorAll("[data-close]").forEach(button => button.addEventListener("click", () => button.closest("dialog").close()));
element("contents").addEventListener("click", async () => {
    outlineDialog.showModal();
    outline ||= showContents(publication, element("outline"), async ({ page, dest }) => {
        outlineDialog.close();
        if (page) go(page);
        else await links.goToDestination(dest);
        refresh();
        reportPosition();
    }).catch(() => { element("outline").textContent = "暂时无法读取目录。可使用底部页码跳转。"; outline = null; });
    const locate = await outline;
    if (outlineDialog.open) locate?.(readingPage());
});
element("page").addEventListener("click", () => {
    const field = element("page-number");
    element("page-range").textContent = "页码（1–" + publication.numPages + "）";
    field.max = publication.numPages;
    field.value = readingPage();
    jumpDialog.showModal();
    field.select();
});
element("jump-form").addEventListener("submit", event => {
    event.preventDefault();
    go(Number(element("page-number").value));
    jumpDialog.close();
});
element("previous").addEventListener("click", () => go(readingPage() - 1, true));
element("next").addEventListener("click", () => go(readingPage() + 1, true));
element("zoom-out").addEventListener("click", () => zoom(viewer.currentScale / 1.24));
element("zoom-in").addEventListener("click", () => zoom(viewer.currentScale * 1.24));
element("fit").addEventListener("click", () => {
    fit = viewer.currentScaleValue === "page-width" ? "page-fit" : "page-width";
    fitViewport();
    refresh();
});
element("search").addEventListener("click", () => toggleSearch(element("searchbar").hidden));
element("close-search").addEventListener("click", () => toggleSearch(false));
element("searchbar").addEventListener("submit", event => { event.preventDefault(); find(); });
element("previous-result").addEventListener("click", () => find(true));
element("query").addEventListener("input", () => { if (!element("query").value) find(); });
element("password-form").addEventListener("submit", event => {
    event.preventDefault();
    const input = element("password");
    const password = input.value;
    input.value = "";
    element("password-dialog").close();
    send("opening");
    passwordReply?.(password);
});
element("password-dialog").addEventListener("cancel", event => { event.preventDefault(); cancelPassword(); });
element("cancel-password").addEventListener("click", cancelPassword);

connectGestures(surface, {
    step: count => go(viewer.currentPageNumber + count, true),
    canTurn: () => ready && surface.dataset.swipe === "true",
    edgeTap: () => options.edges,
    zoom,
    scale: () => viewer.currentScale
});
document.addEventListener("keydown", event => {
    if (!ready || document.querySelector("dialog[open]")) return;
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f") {
        event.preventDefault();
        toggleSearch(true);
        return;
    }
    if (event.target.closest("input, textarea, button") || !window.getSelection()?.isCollapsed) return;
    const step = { PageDown: 1, PageUp: -1, ArrowRight: 1, ArrowLeft: -1 }[event.key];
    if (step) {
        event.preventDefault();
        go(readingPage() + step, true);
    }
});

bus.on("pagechanging", () => { refresh(); reportPosition(); });
bus.on("updateviewarea", () => { refresh(); reportPosition(); });
bus.on("scalechanging", () => { alignLastPage(); refresh(); });
bus.on("pagerendered", ({ pageNumber, cssTransform, isDetailView, error }) => {
    if (!cssTransform && !isDetailView) {
        if (error && pageNumber === rendering.page) {
            showProblem("暂时无法渲染第 " + pageNumber + " 页");
            return;
        }
        if (ready && pageNumber === publication.numPages) alignLastPage();
        if (!error) animatePage(pageNumber);
    }
    refresh();
});
bus.on("textlayerrendered", ({ pageNumber, source }) => {
    textPages.set(pageNumber, !!source.textLayer?.div.querySelector("span"));
    refresh();
});
bus.on("updatefindmatchescount", ({ matchesCount }) => findCount(matchesCount));
bus.on("updatefindcontrolstate", ({ state, matchesCount }) => {
    if (state === FindState.NOT_FOUND) element("matches").textContent = "未找到";
    else if (state === FindState.PENDING) element("matches").textContent = "查找中…";
    else findCount(matchesCount);
});

// PDF.js can calculate a zero fit scale if the WebView is still being laid out.
// Ignore empty sizes and reflow when rotation, split screen or the keyboard changes the viewport.
let surfaceMeasured;
const measured = new Promise(resolve => { surfaceMeasured = resolve; });
const resize = new ResizeObserver(() => {
    if (!surface.clientWidth || !surface.clientHeight) return;
    surfaceMeasured();
    if (!ready || restoring || failed) return;
    fitViewport();
    alignLastPage();
    viewer.update();
    refresh();
});
resize.observe(surface);

async function openDocument() {
    const length = Number(address.get("length"));
    if (!Number.isSafeInteger(length) || length < 1) {
        showProblem("PDF 文件为空");
        return;
    }
    task = getDocument({
        range: new LocalDocument(length),
        rangeChunkSize: 192 * 1024,
        disableStream: true,
        disableAutoFetch: true,
        cMapUrl: "vendor/cmaps/",
        cMapPacked: true,
        standardFontDataUrl: "vendor/standard_fonts/",
        wasmUrl: "vendor/wasm/",
        iccUrl: "vendor/iccs/",
        useWorkerFetch: false,
        canvasMaxAreaInBytes: 40_000_000,
        isEvalSupported: false
    });
    task.onPassword = showPassword;
    try {
        publication = await task.promise;
        const page = Math.max(1, Math.min(publication.numPages, Number(address.get("page")) || 1));
        restoringPage = page;
        rendering.follow(page);
        await measured;
        links.setDocument(publication);
        const initialized = new Promise(resolve => bus.on("pagesinit", resolve, { once: true }));
        viewer.setDocument(publication);
        await Promise.race([initialized, viewer.pagesPromise]);
        ready = true;
        const mode = options.mode;
        setMode(mode, false);
        const offset = Math.max(0, Math.min(10_000, Number(address.get("offset")) || 0));
        await prepareStart(page, offset);
        restoring = false;
        restoringPage = 0;
        if (options.mode !== mode) setMode(options.mode, true);
        document.querySelectorAll("[data-ready]").forEach(button => button.disabled = false);
        refresh();
        reportPosition();
        publication.getPageLabels().then(value => { labels = value; refresh(); }).catch(() => {});
    } catch (error) {
        console.warn("PDF could not be opened", error);
        showProblem("无法打开这个 PDF。文件可能损坏或需要密码。");
    }
}

window.addEventListener("pagehide", () => {
    ready = false;
    clearTimeout(saveTimer);
    resize.disconnect();
    rendering.stop();
    viewer.setDocument(null);
    task?.destroy().catch(() => {});
}, { once: true });
openDocument();
