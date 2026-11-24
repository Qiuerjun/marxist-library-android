// Prefer real PDF bookmarks. Printed contents are offered as a page when there are no bookmarks.
export async function showContents(pdf, panel, jump) {
    const waiting = note("正在读取目录…");
    waiting.className = "loading-line";
    panel.replaceChildren(waiting);
    const bookmarks = await pdf.getOutline().catch(() => null) || [];
    const result = document.createDocumentFragment();
    addBookmarks(result, bookmarks, jump, 0);
    if (result.childNodes.length < 14) {
        const parsed = await printedContents(pdf, bookmarks).catch(() => null);
        if (parsed?.entries.length) {
            const label = note("原书篇章目录");
            label.className = "group";
            result.append(label);
            for (const entry of parsed.entries) {
                const button = document.createElement("button");
                button.textContent = entry.title;
                const page = document.createElement("small");
                page.textContent = "书内第 " + entry.printed + " 页";
                button.append(page);
                button.addEventListener("click", async () => {
                    button.disabled = true;
                    try {
                        const physical = await confirmedPage(pdf, entry.printed, entry.page);
                        jump({ page: physical || parsed.page });
                    } finally {
                        button.disabled = false;
                    }
                });
                result.append(button);
            }
        } else if (parsed?.page && !result.childNodes.length) {
            result.append(note("此 PDF 未提供书签目录。"));
            const button = document.createElement("button");
            button.textContent = "查看原书目录 · 第 " + parsed.page + " 页";
            button.addEventListener("click", () => jump({ page: parsed.page }));
            result.append(button);
        }
    }
    panel.replaceChildren(result.childNodes.length ? result : note("此 PDF 未提供可跳转的目录。可使用底部页码跳转。"));
}

async function printedContents(pdf, bookmarks) {
    const heading = bookmarks.find(item => /^(目[录錄]|内容目[录錄]|tableofcontents|contents)$/i.test(compact(item.title)));
    const page = await destinationPage(pdf, heading?.dest) || await printedContentsPage(pdf);
    if (!page) return null;
    const body = bookmarks.find(item => /^(正文(?:部分)?|maintext)$/i.test(compact(item.title)));
    const firstPage = await destinationPage(pdf, body?.dest);
    if (!firstPage || firstPage <= page) return { page, entries: [] };
    const stops = await Promise.all(bookmarks.map(item => destinationPage(pdf, item.dest)));
    const stop = Math.min(page + 14, firstPage, ...stops.filter(number => number > page));
    const entries = [];
    for (let number = page; number < stop; number++) entries.push(...await printedEntries(pdf, number));
    if (entries.length < 3) return { page, entries: [] };
    const first = Math.min(...entries.map(entry => entry.printed));
    const shift = firstPage - first;
    // Three consistent printed page numbers distinguish this numbering from the front matter.
    const alignment = await Promise.all(new Array(Math.min(9, pdf.numPages - firstPage + 1)).fill(0).map(
        (_, index) => hasPrintedNumber(pdf, firstPage + index, first + index)
    ));
    if (alignment.filter(Boolean).length < 3) return { page, entries: [] };
    return { page, entries: entries.map(entry => ({ ...entry, page: entry.printed + shift }))
        .filter(entry => entry.page >= firstPage && entry.page <= pdf.numPages) };
}

async function destinationPage(pdf, dest) {
    const array = typeof dest === "string" ? await pdf.getDestination(dest) : dest;
    if (!Array.isArray(array)) return null;
    const ref = array[0];
    if (Number.isInteger(ref)) return ref + 1;
    return ref ? (await pdf.getPageIndex(ref).catch(() => -1)) + 1 : null;
}

async function printedEntries(pdf, number) {
    const { page, rows } = await textRows(pdf, number);
    const height = page.view[3] - page.view[1];
    const entries = [];
    let carried = [];
    let baseline = 0;
    for (const row of rows) {
        if (row.y < page.view[1] + height * .1 || row.y > page.view[1] + height * .805) continue;
        if (Math.abs(baseline - row.y) > height * .048) carried = [];
        baseline = row.y;
        const text = compact(row.cells.map(cell => cell.str).join(""));
        const end = text.match(/^(.*?)[.·…]{2,}(\d{1,4})(?:[-—–−]\d{1,4})?$/);
        if (end) {
            const title = [...carried, end[1]].join("").slice(0, 240);
            if (title.length > 1 && Number(end[2]) > 0) entries.push({ title, printed: Number(end[2]) });
            carried = [];
        } else if (/^[.·…\d]+$/.test(text) || /^(?:目[录錄]|\d{4}年)$/.test(text) || /[.·…]{2,}/.test(text)) {
            carried = [];
        } else if (text) {
            carried.push(text);
            if (carried.length > 3) carried.shift();
        }
    }
    return entries;
}

async function confirmedPage(pdf, printed, predicted) {
    // An inserted plate can shift the estimate. Prefer a page with the number printed on it.
    for (const offset of [0, 1, -1, 2, -2, 3, -3]) {
        const candidate = predicted + offset;
        if (candidate > 0 && candidate <= pdf.numPages && await hasPrintedNumber(pdf, candidate, printed)) return candidate;
    }
    if (predicted > 1 && predicted < pdf.numPages &&
        await hasPrintedNumber(pdf, predicted - 1, printed - 1) &&
        await hasPrintedNumber(pdf, predicted + 1, printed + 1)) return predicted;
    return null;
}

async function hasPrintedNumber(pdf, number, wanted) {
    const { page, rows } = await textRows(pdf, number);
    const [left, bottom, right, top] = page.view;
    for (const row of rows) {
        const height = (row.y - bottom) / (top - bottom);
        if (height > .16 && height < .795) continue;
        let digits = "";
        let edge = null;
        let last = 0;
        for (const cell of row.cells) {
            const x = cell.transform[4];
            const at = (x - left) / (right - left);
            const side = at <= .265 ? "left" : at >= .735 ? "right" : null;
            const number = compact(cell.str);
            if (side && /^\d{1,4}$/.test(number)) {
                if (edge !== side || x - last > 4) {
                    if (digits && Number(digits) === wanted) return true;
                    digits = "";
                }
                digits += number;
                edge = side;
                last = x + cell.width;
            } else if (digits) {
                if (Number(digits) === wanted) return true;
                digits = "";
                edge = null;
            }
        }
        if (digits && Number(digits) === wanted) return true;
    }
    return false;
}

async function textRows(pdf, number) {
    const page = await pdf.getPage(number);
    const { items } = await page.getTextContent();
    const sorted = items.filter(item => item.str?.trim()).sort((a, b) => b.transform[5] - a.transform[5]);
    const rows = [];
    for (const item of sorted) {
        const y = item.transform[5];
        const last = rows.at(-1);
        if (last && Math.abs(last.y - y) <= 2.2) last.cells.push(item);
        else rows.push({ y, cells: [item] });
    }
    for (const row of rows) row.cells.sort((a, b) => a.transform[4] - b.transform[4]);
    return { page, rows };
}

function compact(text) {
    return text.normalize("NFKC").replace(/\s+/g, "");
}

function addBookmarks(container, bookmarks, jump, depth) {
    if (depth > 12) return;
    for (const entry of bookmarks) {
        const title = entry.title.trim().slice(0, 320);
        if (title && entry.dest != null) {
            const button = document.createElement("button");
            button.textContent = title;
            button.style.setProperty("--level", Math.min(depth, 6));
            button.addEventListener("click", () => jump({ dest: entry.dest }));
            container.append(button);
        } else if (title && entry.items.length) {
            const heading = note(title);
            heading.className = "group";
            heading.style.paddingInlineStart = Math.min(depth, 6) * 12 + "px";
            container.append(heading);
        }
        addBookmarks(container, entry.items, jump, depth + 1);
    }
}

async function printedContentsPage(pdf) {
    for (let number = 1; number <= Math.min(pdf.numPages, 20); number++) {
        const { page, rows } = await textRows(pdf, number);
        const top = page.view[1] + (page.view[3] - page.view[1]) * .69;
        if (rows.filter(row => row.y > top).slice(0, 5).some(row =>
            /^(目[录錄]|tableofcontents|contents)$/i.test(compact(row.cells.map(cell => cell.str).join(""))))) return number;
    }
    return null;
}

function note(text) {
    const element = document.createElement("p");
    element.textContent = text;
    return element;
}
