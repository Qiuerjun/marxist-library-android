/* Reader controls for sanitized local documents. */
(function () {
    'use strict';
    var blocks = Array.from(document.querySelectorAll('[data-reading-anchor]'));
    var notes = window.readerNotes;
    var noteReturn = null;
    var illustrationsReady = false;
    var sections = [];
    var sectionJump = null;
    var readingEdge = 24;

    function normalized(text) {
        return text.replace(/\s+/g, ' ').trim();
    }

    function currentBlock() {
        var edge = Math.min(readingEdge, window.innerHeight / 5);
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
        var y = Math.max(1, Math.min(readingEdge, window.innerHeight - 1, bounds.bottom - 1));
        var probe = bounds.top < 0 && document.caretRangeFromPoint
            ? document.caretRangeFromPoint(bounds.left + 2, y) : null;
        var offset = probe ? offsetInside(block, probe.startContainer, probe.startOffset) : 0;
        return {anchor: block.id, quote: normalized(block.textContent).slice(0, 90), offset: offset};
    }

    function fragment(value) {
        try { return decodeURIComponent(value); } catch (_) { return value; }
    }

    function anchorNode(id) {
        return document.getElementById(id) || document.getElementById(fragment(id)) ||
            Array.from(document.getElementsByName(fragment(id))).find(function (node) { return node.tagName === 'A'; });
    }

    function target(location) {
        var identified = location.anchor ? anchorNode(location.anchor) : null;
        var quote = normalized(location.quote || '');
        if (identified && (!quote || normalized(identified.textContent).indexOf(quote) !== -1)) return identified;
        if (!quote) return null;
        var matches = blocks.filter(function (node) { return normalized(node.textContent).indexOf(quote) !== -1; });
        return matches.length === 1 ? matches[0] : null;
    }

    function landing(node) {
        // Named anchors often sit on a blank line separated from their heading by a horizontal rule.
        if (!node.matches('a, span') || node.childElementCount || normalized(node.textContent)) return node;
        var parent = node.parentElement;
        var marker = parent && parent.matches('p, div') && !normalized(parent.textContent) ? parent : node;
        for (var next = marker.nextSibling; next; next = next.nextSibling) {
            if (next.nodeType === Node.TEXT_NODE && normalized(next.nodeValue)) break;
            if (next.nodeType !== Node.ELEMENT_NODE || next.matches('br, hr') ||
                (!normalized(next.textContent) && !next.matches('img, table'))) continue;
            return next;
        }
        return node;
    }

    function setContents(ids) {
        sections = ids.map(function (id) {
            var node = anchorNode(id);
            return node && {id: id, node: landing(node)};
        }).filter(Boolean).sort(function (left, right) {
            if (left.node === right.node) return 0;
            return left.node.compareDocumentPosition(right.node) & Node.DOCUMENT_POSITION_PRECEDING ? 1 : -1;
        });
    }

    function scrollToAnchor(location) {
        sectionJump = null;
        if (!location.anchor && !location.quote) {
            window.scrollTo(0, 0);
            return true;
        }
        var block = target(location);
        if (!block) return false;
        var destination = landing(block);
        destination.scrollIntoView({block: 'start', inline: 'nearest', behavior: 'auto'});
        var left = location.offset || 0;
        if (!left) {
            var section = sections.find(function (item) { return item.id === location.anchor || item.node === destination; });
            if (section && !location.quote) sectionJump = {section: section, y: window.scrollY};
            return true;
        }
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
        var y = window.scrollY;
        if (sectionJump && Math.abs(y - sectionJump.y) > 1) sectionJump = null;
        var section = sectionJump && sectionJump.section;
        var anchor = section ? {anchor: section.id, quote: '', offset: 0} : locate();
        if (!section) {
            for (var index = sections.length - 1; index >= 0; index--) {
                if (sections[index].node.getBoundingClientRect().top > readingEdge) continue;
                section = sections[index];
                break;
            }
        }
        return {anchor: anchor, section: section ? section.id : '', y: y, width: window.innerWidth,
            height: document.documentElement.scrollHeight, viewport: window.innerHeight};
    }

    function readPosition() {
        var place = noteReturn || snapshot();
        var max = Math.max(0, place.height - place.viewport);
        var progress = max === 0 || place.y >= max - 1 ? 100 : Math.floor(100 * place.y / max);
        return Object.assign({}, place.anchor, {section: place.section, progress: progress, atFootnote: noteReturn !== null});
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
        image.addEventListener('load', function () {
            if (!sectionJump || noteReturn || Math.abs(window.scrollY - sectionJump.y) > 1) return;
            sectionJump.section.node.scrollIntoView({block: 'start', inline: 'nearest'});
            sectionJump.y = window.scrollY;
        });
    });
    document.addEventListener('click', function (event) {
        var link = event.target.closest('a[href^="#"]');
        if (!link || event.defaultPrevented || event.ctrlKey || event.metaKey) return;
        if (noteReturn) {
            if (notes.returnsToText(noteReturn.index, link)) {
                event.preventDefault();
                returnFromFootnote();
                return;
            }
            noteReturn = null;
        }
        var id = fragment(link.getAttribute('href').slice(1));
        if (id && scrollToAnchor({anchor: id})) event.preventDefault();
    });

    window.archiveReader = {
        position: readPosition,
        contents: setContents,
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
            var place = keepPosition && reflow && !noteReturn ? snapshot().anchor : null;
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
