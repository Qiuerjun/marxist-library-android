/* Reader controls for sanitized local documents. */
(function () {
    'use strict';
    var blocks = Array.from(document.querySelectorAll('[data-reading-anchor]'));
    var notes = window.readerNotes;
    var noteReturn = null;
    var illustrationsReady = false;

    function normalized(text) {
        return text.replace(/\s+/g, ' ').trim();
    }

    function currentBlock() {
        var edge = Math.min(8, window.innerHeight / 5);
        var hit = document.elementFromPoint(window.innerWidth / 2, edge);
        var block = hit && hit.closest('[data-reading-anchor]');
        if (block) return block;
        return blocks.find(function (item) {
            return item.getBoundingClientRect().bottom > edge;
        }) || blocks[blocks.length - 1];
    }

    function offsetInside(block, node, offset) {
        if (!node || !block.contains(node)) return 0;
        var prefix = document.createRange();
        prefix.selectNodeContents(block);
        prefix.setEnd(node, offset);
        return Array.from(prefix.toString()).length;
    }

    function locate() {
        var block = currentBlock();
        if (!block) return {anchor: '', quote: '', offset: 0};
        var bounds = block.getBoundingClientRect();
        var y = Math.max(1, Math.min(8, window.innerHeight - 1, bounds.bottom - 1));
        var probe = bounds.top < -4 && document.caretRangeFromPoint
            ? document.caretRangeFromPoint(bounds.left + 2, y) : null;
        var offset = probe ? offsetInside(block, probe.startContainer, probe.startOffset) : 0;
        return {anchor: block.id, quote: normalized(block.textContent).slice(0, 90), offset: offset};
    }

    function target(location) {
        var identified = location.anchor ? document.getElementById(location.anchor) : null;
        var quote = normalized(location.quote || '');
        if (identified && (!quote || normalized(identified.textContent).indexOf(quote) !== -1)) return identified;
        if (!quote) return null;
        var matches = blocks.filter(function (node) { return normalized(node.textContent).indexOf(quote) !== -1; });
        return matches.length === 1 ? matches[0] : null;
    }

    function scrollToAnchor(location) {
        if (!location.anchor && !location.quote) {
            window.scrollTo(0, 0);
            return true;
        }
        var block = target(location);
        if (!block) return false;
        block.scrollIntoView({block: 'start', behavior: 'auto'});
        var left = location.offset || 0;
        if (!left) return true;
        var walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
        var node;
        while ((node = walker.nextNode())) {
            var characters = Array.from(node.nodeValue);
            if (left < characters.length) {
                var start = characters.slice(0, left).join('').length;
                var end = start + characters[left].length;
                var range = document.createRange();
                range.setStart(node, start);
                range.setEnd(node, end);
                var character = range.getBoundingClientRect();
                if (character.height) window.scrollBy(0, character.top - 5);
                break;
            }
            left -= characters.length;
        }
        return true;
    }

    function snapshot() {
        return {anchor: locate(), y: window.scrollY, width: window.innerWidth,
            height: document.documentElement.scrollHeight, viewport: window.innerHeight};
    }

    function readPosition() {
        var place = noteReturn || snapshot();
        var max = Math.max(0, place.height - place.viewport);
        var progress = max === 0 || place.y >= max - 1 ? 100 : Math.floor(100 * place.y / max);
        return Object.assign({}, place.anchor, {progress: progress, atFootnote: noteReturn !== null});
    }

    function jumpToFootnote(index) {
        var target = notes && notes.target(index);
        if (!target) return false;
        if (!noteReturn) noteReturn = snapshot();
        noteReturn.index = index;
        target.scrollIntoView({block: 'start', behavior: 'auto'});
        return true;
    }

    function returnFromFootnote() {
        if (!noteReturn) return false;
        var place = noteReturn;
        noteReturn = null;
        if (place.width === window.innerWidth && place.height === document.documentElement.scrollHeight) {
            window.scrollTo(0, place.y);
            return true;
        }
        return scrollToAnchor(place.anchor);
    }

    function selection() {
        var picked = window.getSelection();
        if (!picked || picked.rangeCount === 0 || picked.isCollapsed) return null;
        var range = picked.getRangeAt(0);
        var start = range.startContainer;
        var element = start.nodeType === Node.ELEMENT_NODE ? start : start.parentElement;
        var block = element && element.closest('[data-reading-anchor]');
        var location = block ? {
            anchor: block.id,
            quote: normalized(block.textContent).slice(0, 90),
            offset: offsetInside(block, start, range.startOffset)
        } : locate();
        location.selection = picked.toString().trim().slice(0, 10000);
        return location;
    }

    function restoreMarks(marks) {
        Array.from(document.querySelectorAll('mark[data-library-mark]')).forEach(function (mark) {
            var parent = mark.parentNode;
            while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
            parent.removeChild(mark);
            parent.normalize();
        });
        marks.forEach(function (item) {
            var block = target(item);
            if (!block || !item.selection) return;
            var text = block.textContent;
            var stored = Array.from(text).slice(0, item.offset || 0).join('').length;
            var start = text.substr(stored, item.selection.length) === item.selection
                ? stored : text.indexOf(item.selection);
            if (start < 0) return;
            var end = start + item.selection.length;
            var walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
            var spans = [];
            var index = 0;
            var node;
            while ((node = walker.nextNode()) && index < end) {
                var length = node.nodeValue.length;
                var from = Math.max(0, start - index);
                var to = Math.min(length, end - index);
                if (from < to) spans.push([node, from, to]);
                index += length;
            }
            spans.forEach(function (span) {
                var chosen = span[0].splitText(span[1]);
                chosen.splitText(span[2] - span[1]);
                var mark = document.createElement('mark');
                mark.setAttribute('data-library-mark', '');
                chosen.parentNode.insertBefore(mark, chosen);
                mark.appendChild(chosen);
            });
        });
    }

    function spokenParagraphs() {
        var deduped = [];
        var start = Math.max(0, blocks.indexOf(currentBlock()));
        blocks.slice(start, start + 300).forEach(function (block) {
            if (block.querySelector('[data-reading-anchor]')) return;
            var text = normalized(block.innerText || block.textContent);
            if (text.length < 2) return;
            while (text.length > 1400) {
                var cut = Math.max(text.lastIndexOf('。', 1400), text.lastIndexOf('；', 1400), text.lastIndexOf('，', 1400));
                cut = cut < 500 ? 1400 : cut + 1;
                deduped.push({text: text.slice(0, cut), anchor: block.id});
                text = text.slice(cut);
            }
            if (text) deduped.push({text: text, anchor: block.id});
        });
        return deduped;
    }

    function retryImage(image) {
        if (image.hasAttribute('data-image-retried')) return;
        image.setAttribute('data-image-retried', '');
        var source = image.getAttribute('src').split('?')[0];
        image.src = source + '?retry=' + Date.now();
    }

    document.querySelectorAll('img[src]').forEach(function (image) {
        image.addEventListener('error', function () {
            if (illustrationsReady) retryImage(image);
        });
    });
    document.addEventListener('click', function (event) {
        var link = event.target.closest('a[href^="#"]');
        if (link && noteReturn) {
            if (notes.returnsToText(noteReturn.index, link)) {
                event.preventDefault();
                returnFromFootnote();
            } else noteReturn = null;
        }
    });

    window.archiveReader = {
        position: readPosition,
        go: function (location) {
            noteReturn = null;
            return scrollToAnchor(location);
        },
        selection: selection,
        marks: restoreMarks,
        speech: spokenParagraphs,
        reloadImages: function () {
            // The first WebView request can finish before the script observes its failure.
            illustrationsReady = true;
            document.querySelectorAll('img[src]').forEach(function (image) {
                if (image.complete && !image.naturalWidth) retryImage(image);
            });
        },
        typography: function (values, keepPosition) {
            var root = document.documentElement.style;
            var changes = Object.keys(values).filter(function (name) {
                return root.getPropertyValue('--' + name) !== String(values[name]);
            });
            var reflow = changes.some(function (name) {
                return ['size', 'spacing', 'margin', 'face', 'paragraph-gap', 'first-indent', 'measure'].indexOf(name) !== -1;
            });
            var place = keepPosition && reflow && !noteReturn ? locate() : null;
            changes.forEach(function (name) { root.setProperty('--' + name, values[name]); });
            if (noteReturn && reflow) {
                noteReturn.width = 0;
                jumpToFootnote(noteReturn.index);
            } else if (place) scrollToAnchor(place);
        },
        previewFootnote: function (index) { return notes && notes.preview(index); },
        jumpToFootnote: jumpToFootnote,
        returnFromFootnote: returnFromFootnote
    };
}());
