/* Identify and read footnotes in the sanitized document, including legacy named anchors. */
(function () {
    'use strict';

    var notes = [];
    var targets = [];
    var boundaries = [];
    var noteContainer = 'aside, li, dd, [role="doc-footnote"], [role="doc-endnote"]';
    var noteLabel = /^(?:[\[［〔【(（]\s*(?:注\s*)?[0-9０-９]+\s*[\]］〕】)）]|[①-⑳*†‡]+)$/;
    var superscriptLabel = /^(?:注\s*)?[0-9０-９]+$/;
    var returnLabel = /^(?:返回(?:正文|原文|注号)?|回到(?:正文|原文|注号)|back|[↑↩]+)$/i;
    var textLimit = 7500;

    function comesBefore(first, second) {
        return Boolean(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING);
    }

    function anchorFor(link) {
        var href = link.getAttribute('href') || '';
        if (href.charAt(0) !== '#' || href.length < 2) return null;
        var raw = href.slice(1);
        var decoded;
        try {
            decoded = decodeURIComponent(raw);
        } catch (_) {
            decoded = raw;
        }
        return document.getElementById(decoded) || document.getElementById(raw) ||
            Array.from(document.getElementsByName(decoded)).find(function (node) {
                return node.tagName === 'A';
            }) || null;
    }

    function cleanLabel(link) {
        return link.textContent.replace(/\s+/g, ' ').trim().slice(0, 60);
    }

    function explicitReference(link) {
        return link.getAttribute('role') === 'doc-noteref' ||
            (link.getAttribute('epub:type') || '').split(/\s+/).indexOf('noteref') !== -1 ||
            (link.getAttribute('rel') || '').split(/\s+/).indexOf('footnote') !== -1;
    }

    function isReturnLink(link, note) {
        var to = anchorFor(link);
        if (!to || !comesBefore(to, note.target)) return false;
        var label = cleanLabel(link);
        return link.getAttribute('role') === 'doc-backlink' || link.getAttribute('rel') === 'backlink' ||
            returnLabel.test(label) || label === note.label;
    }

    function firstBoundary(target) {
        var nearest = null;
        boundaries.forEach(function (node) {
            if (node !== target && !target.contains(node) && comesBefore(target, node) &&
                (!nearest || comesBefore(node, nearest))) {
                nearest = node;
            }
        });
        return nearest;
    }

    function excerpt(note) {
        var target = note.target;
        var semantic = target.closest(noteContainer);
        var block = semantic || target.closest('p, blockquote, td, div, section, article') || target.parentElement;
        var stop = semantic ? null : firstBoundary(target);
        var end = block;
        // Legacy editions may continue a single note over several ordinary paragraphs.
        if (!semantic && block.tagName === 'P') {
            for (var index = 0; index < 12; index++) {
                var sibling = end.nextElementSibling;
                if (!sibling || sibling.tagName !== 'P' ||
                    (stop && (sibling === stop || comesBefore(stop, sibling)))) break;
                end = sibling;
                if (stop && end.contains(stop)) break;
            }
        }

        var range = document.createRange();
        range.setStartBefore(target);
        if (stop && (end.contains(stop) || comesBefore(stop, end))) {
            range.setEndBefore(stop);
        } else if (end === document.body) {
            range.setEnd(end, end.childNodes.length);
        } else {
            range.setEndAfter(end);
        }
        var fragment = range.cloneContents();
        fragment.querySelectorAll('a[href^="#"]').forEach(function (link) {
            if (isReturnLink(link, note)) link.remove();
        });
        fragment.querySelectorAll('.reader-source-indent').forEach(function (indent) { indent.remove(); });
        var walker = document.createTreeWalker(fragment, NodeFilter.SHOW_TEXT);
        var node;
        while ((node = walker.nextNode())) node.nodeValue = node.nodeValue.replace(/[\t\r\n ]+/g, ' ');
        fragment.querySelectorAll('br').forEach(function (line) { line.replaceWith('\n'); });
        fragment.querySelectorAll('p, div, li, dd, blockquote, figcaption').forEach(function (paragraph) {
            paragraph.before('\n');
            paragraph.after('\n');
        });
        var text = fragment.textContent.split('\n').map(function (line) {
            return line.replace(/[\t \u3000]+/g, ' ').trim();
        }).filter(Boolean).join('\n\n');
        if (note.label && text.indexOf(note.label) === 0) {
            text = text.slice(note.label.length).replace(/^\s+/, '');
        }
        return {
            label: note.label,
            text: text.slice(0, textLimit),
            shortened: text.length > textLimit
        };
    }

    document.querySelectorAll('a[href^="#"]').forEach(function (link) {
        if (notes.length >= 3000) return;
        var target = anchorFor(link);
        if (!target || target === link || link.contains(target)) return;
        var label = cleanLabel(link);
        var marker = noteLabel.test(label) || (link.closest('sup') && superscriptLabel.test(label));
        if (!explicitReference(link) && (!marker || !comesBefore(link, target) || target.closest('h1,h2,h3,h4,h5,h6'))) return;
        link.setAttribute('href', '/reader/note/' + notes.length);
        link.setAttribute('data-reader-footnote', '');
        link.setAttribute('aria-haspopup', 'dialog');
        link.setAttribute('aria-label', '查看原文注释 ' + label);
        notes.push({target: target, label: label});
        targets.push(target);
    });
    boundaries = targets.concat(Array.from(document.querySelectorAll('hr, h1, h2, h3, h4, h5, h6, .source-credit')));

    window.readerNotes = {
        preview: function (index) { return notes[index] ? excerpt(notes[index]) : null; },
        target: function (index) { return notes[index] ? notes[index].target : null; },
        returnsToText: function (index, link) { return Boolean(notes[index] && isReturnLink(link, notes[index])); }
    };
}());
