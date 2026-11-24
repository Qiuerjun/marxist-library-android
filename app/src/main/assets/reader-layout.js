/* Legacy editions use loose text and line breaks where newer editions have paragraphs. */
(function () {
    'use strict';

    var prose = /^[\u3400-\u9fff\uf900-\ufaff“‘《〈（【［\[].{19}/;
    var originalIndent = /^[\t\r\n ]*(?:\u3000{2,}|\u00a0{4,})/;
    var inline = /^(A|ABBR|B|CITE|CODE|DEL|EM|I|SMALL|SPAN|STRONG|SUB|SUP|U)$/;
    var block = /^(ARTICLE|BLOCKQUOTE|DIV|FIGURE|H[1-6]|HR|OL|P|SECTION|TABLE|UL)$/;
    var skip = 'blockquote, li, dd, aside, .source-credit, [role="doc-footnote"], [role="doc-endnote"], [align="center"], [align="right"]';
    var noteTargets = 'a[id^="_ftn"]:not([id^="_ftnref"]), [role="doc-footnote"], [role="doc-endnote"]';

    document.querySelectorAll('table').forEach(function (table) {
        var row = table.rows.length === 1 ? table.rows[0] : null;
        var cell = row && row.cells.length === 1 ? row.cells[0] : null;
        if (cell && cell.tagName === 'TD' && cell.textContent.length > 500 &&
            cell.querySelector('h1,h2,h3,h4,h5,h6')) table.setAttribute('data-reader-layout-table', '');
    });

    function withinReadingFlow(node) {
        var table = node.closest('table');
        return !node.closest(skip) && (!table || table.hasAttribute('data-reader-layout-table'));
    }

    // Some editions wrap whole sections in nested formatting spans. Keep their formatting on both sides of each break.
    Array.from(document.querySelectorAll('span')).reverse().forEach(function (span) {
        var flow = span.parentElement;
        while (flow && flow.tagName === 'SPAN') flow = flow.parentElement;
        if (!flow || !flow.matches('body, article, section, div, td') || !withinReadingFlow(span) ||
            span.id || span.hasAttribute('role') || span.querySelector(noteTargets) ||
            span.querySelectorAll(':scope > br').length < 2 || span.textContent.length < 200) return;
        var line = span.cloneNode(false);
        while (span.firstChild) {
            var child = span.firstChild;
            if (child.nodeName === 'BR') {
                if (line.hasChildNodes()) span.before(line);
                span.before(child);
                line = span.cloneNode(false);
            } else line.appendChild(child);
        }
        span.replaceWith(line);
    });

    function paragraphId(text) {
        var hash = 2166136261;
        for (var i = 0; i < text.length; i++) hash = Math.imul(hash ^ text.charCodeAt(i), 16777619);
        var prefix = 'reader-paragraph-' + (hash >>> 0).toString(16);
        var id = prefix;
        for (var count = 2; document.getElementById(id); count++) id = prefix + '-' + count;
        return id;
    }

    function wrap(nodes) {
        var fragments = nodes.filter(function (node) { return node.nodeName !== 'A'; });
        var text = nodes.map(function (node) { return node.textContent; }).join('').replace(/\s+/g, ' ').trim();
        var unlinked = fragments.map(function (node) { return node.textContent; }).join('').replace(/\s+/g, ' ').trim();
        var linkGroup = nodes.length - fragments.length >= 2 && unlinked.length < 18 &&
            !nodes.some(function (node) { return node.nodeName === 'A' && node.hasAttribute('data-reader-footnote'); });
        if ((!linkGroup && (!prose.test(text) || unlinked.length < 18)) || nodes.some(function (node) {
            return node.nodeType === Node.ELEMENT_NODE && (node.matches(noteTargets) || node.querySelector(noteTargets + ', img'));
        })) return false;
        var paragraph = document.createElement(linkGroup ? 'div' : 'p');
        if (linkGroup) {
            paragraph.className = 'reader-source-links';
            fragments.forEach(function (node) {
                if (node.nodeType === Node.TEXT_NODE) node.nodeValue = node.nodeValue.replace(/[\u3000\u00a0]{2,}/g, ' ');
            });
        } else {
            paragraph.id = paragraphId(text.slice(0, 250));
            paragraph.setAttribute('data-reading-anchor', paragraph.id);
        }
        nodes[0].parentNode.insertBefore(paragraph, nodes[0]);
        nodes.forEach(function (node) { paragraph.appendChild(node); });
        return true;
    }

    function bordersBlock(line, direction) {
        for (var node = line[direction]; node; node = node[direction]) {
            if (block.test(node.nodeName)) return true;
            if (node.nodeName === 'BR' || node.nodeType === Node.COMMENT_NODE ||
                (node.nodeType === Node.TEXT_NODE && !node.textContent.trim()) ||
                (node.nodeName === 'A' && !node.textContent.trim() && !node.firstElementChild)) continue;
            return false;
        }
        return true;
    }

    document.querySelectorAll('body, article, section, div, td').forEach(function (container) {
        if (!withinReadingFlow(container) || !container.querySelector(':scope > br')) return;
        var group = [];
        var wrapped = false;
        Array.from(container.childNodes).forEach(function (node) {
            if (node.nodeName === 'BR') {
                wrapped = wrap(group) || wrapped;
                if (wrapped) node.remove();
                group = [];
            } else if (node.nodeType === Node.ELEMENT_NODE && !inline.test(node.nodeName)) {
                wrap(group);
                group = [];
                wrapped = false;
            } else if (node.textContent.trim() || !wrapped) {
                group.push(node);
                wrapped = false;
            }
        });
        wrap(group);
        container.querySelectorAll(':scope > br').forEach(function (line) {
            if (bordersBlock(line, 'previousSibling') || bordersBlock(line, 'nextSibling')) line.remove();
        });
    });

    document.querySelectorAll('p').forEach(function (paragraph) {
        if (!withinReadingFlow(paragraph) || paragraph.querySelector('br, img')) return;
        var walker = document.createTreeWalker(paragraph, NodeFilter.SHOW_TEXT);
        var first = walker.nextNode();
        while (first && !first.nodeValue.trim()) first = walker.nextNode();
        var indent = first && originalIndent.exec(first.nodeValue);
        if (!indent && !prose.test(paragraph.textContent.replace(/\s+/g, ' ').trim())) return;
        if (indent) {
            first.splitText(indent[0].length);
            var hidden = document.createElement('span');
            hidden.className = 'reader-source-indent';
            first.parentNode.insertBefore(hidden, first);
            hidden.appendChild(first);
        }
        paragraph.setAttribute('data-reader-prose', '');
    });
}());
