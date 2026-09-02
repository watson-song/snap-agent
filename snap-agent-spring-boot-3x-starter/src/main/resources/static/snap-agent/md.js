// Minimal Markdown renderer — escapes HTML first, then applies MD formatting.
// Supports: code blocks, tables, headers, bold, italic, inline code, lists, hr.

function renderMarkdown(md) {
    if (!md) return '';

    // 1. Extract code blocks first (protect from further processing)
    var codeBlocks = [];
    var text = md.replace(/```(\w*)\n([\s\S]*?)```/g, function(_, lang, code) {
        var idx = codeBlocks.length;
        codeBlocks.push({ lang: lang, code: code.replace(/\n$/, '') });
        return '\u0000CODE' + idx + '\u0000';
    });

    // 2. Escape HTML
    text = text.replace(/&/g, '&amp;')
               .replace(/</g, '&lt;')
               .replace(/>/g, '&gt;')
               .replace(/"/g, '&quot;');

    // 3. Split into lines for block-level processing
    var lines = text.split('\n');
    var html = [];
    var i = 0;

    while (i < lines.length) {
        var line = lines[i];

        // Restore code block placeholder
        var codeMatch = line.match(/\u0000CODE(\d+)\u0000/);
        if (codeMatch) {
            var cb = codeBlocks[parseInt(codeMatch[1])];
            var langClass = cb.lang ? ' class="lang-' + cb.lang + '"' : '';
            html.push('<pre class="code-block"' + langClass + '><code>' + escapeHtml(cb.code) + '</code></pre>');
            i++;
            continue;
        }

        // Headers
        var hMatch = line.match(/^(#{1,6})\s+(.*)/);
        if (hMatch) {
            var level = hMatch[1].length;
            html.push('<h' + level + ' class="md-h' + level + '">' + inlineMd(hMatch[2]) + '</h' + level + '>');
            i++;
            continue;
        }

        // Horizontal rule
        if (/^---+\s*$/.test(line) || /^\*\*\*+\s*$/.test(line)) {
            html.push('<hr class="md-hr">');
            i++;
            continue;
        }

        // Table detection (line starts with | and next line is separator)
        if (line.trim().startsWith('|') && i + 1 < lines.length && /^\|[\s\-:|]+\|/.test(lines[i + 1].trim())) {
            var tableHtml = renderTable(lines, i);
            if (tableHtml) {
                html.push(tableHtml.html);
                i = tableHtml.nextIndex;
                continue;
            }
        }

        // Unordered list
        if (/^[\s]*[-*]\s+/.test(line)) {
            var listItems = [];
            while (i < lines.length && /^[\s]*[-*]\s+/.test(lines[i])) {
                var item = lines[i].replace(/^[\s]*[-*]\s+/, '');
                listItems.push('<li>' + inlineMd(item) + '</li>');
                i++;
            }
            html.push('<ul class="md-ul">' + listItems.join('') + '</ul>');
            continue;
        }

        // Ordered list
        if (/^[\s]*\d+\.\s+/.test(line)) {
            var olItems = [];
            while (i < lines.length && /^[\s]*\d+\.\s+/.test(lines[i])) {
                var oi = lines[i].replace(/^[\s]*\d+\.\s+/, '');
                olItems.push('<li>' + inlineMd(oi) + '</li>');
                i++;
            }
            html.push('<ol class="md-ol">' + olItems.join('') + '</ol>');
            continue;
        }

        // Empty line
        if (line.trim() === '') {
            i++;
            continue;
        }

        // Paragraph (collect consecutive non-empty, non-special lines)
        var para = [];
        while (i < lines.length && lines[i].trim() !== '' &&
               !/^(#{1,6})\s/.test(lines[i]) &&
               !/^[\s]*[-*]\s+/.test(lines[i]) &&
               !/^[\s]*\d+\.\s+/.test(lines[i]) &&
               !lines[i].trim().startsWith('|') &&
               !/^---+\s*$/.test(lines[i]) &&
               !/\u0000CODE\d+\u0000/.test(lines[i])) {
            para.push(lines[i]);
            i++;
        }
        if (para.length > 0) {
            html.push('<p class="md-p">' + inlineMd(para.join('<br>')) + '</p>');
        } else {
            // Fallback: line didn't match any handler above (e.g. incomplete
            // table row starting with |). Render as standalone paragraph to
            // prevent infinite loops on unexpected input.
            html.push('<p class="md-p">' + inlineMd(lines[i]) + '</p>');
            i++;
        }
    }

    return html.join('\n');
}

function renderTable(lines, startIdx) {
    var i = startIdx;
    var headers = splitTableRow(lines[i]);
    if (!headers) return null;
    i++;

    // Skip separator line
    if (i < lines.length && /^\|[\s\-:|]+\|/.test(lines[i].trim())) {
        i++;
    }

    var rows = [];
    while (i < lines.length && lines[i].trim().startsWith('|')) {
        var cells = splitTableRow(lines[i]);
        if (cells) rows.push(cells);
        i++;
    }

    var html = '<table class="md-table"><thead><tr>';
    for (var h = 0; h < headers.length; h++) {
        html += '<th>' + inlineMd(headers[h].trim()) + '</th>';
    }
    html += '</tr></thead><tbody>';
    for (var r = 0; r < rows.length; r++) {
        html += '<tr>';
        for (var c = 0; c < rows[r].length; c++) {
            html += '<td>' + inlineMd(rows[r][c].trim()) + '</td>';
        }
        html += '</tr>';
    }
    html += '</tbody></table>';
    return { html: html, nextIndex: i };
}

function splitTableRow(line) {
    var trimmed = line.trim();
    if (!trimmed.startsWith('|')) return null;
    // Remove leading/trailing pipes
    if (trimmed.endsWith('|')) trimmed = trimmed.slice(0, -1);
    if (trimmed.startsWith('|')) trimmed = trimmed.slice(1);
    return trimmed.split('|');
}

// Inline formatting: bold, italic, inline code, links
function inlineMd(text) {
    if (!text) return '';
    // Inline code (do first to protect content)
    text = text.replace(/`([^`]+)`/g, function(_, code) {
        return '<code class="md-code">' + escapeHtml(code) + '</code>';
    });
    // Bold
    text = text.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    // Italic (avoid matching bold)
    text = text.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    // Links [text](url)
    text = text.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>');
    return text;
}

function escapeHtml(str) {
    return str.replace(/&/g, '&amp;')
              .replace(/</g, '&lt;')
              .replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;');
}
