import { describe, it, expect, beforeEach } from 'vitest';
import { readFileSync } from 'fs';
import { resolve } from 'path';

// Load md.js source into global scope
const STATIC_DIR = resolve(__dirname, '../../../main/resources/static/snap-agent');
const mdSource = readFileSync(resolve(STATIC_DIR, 'md.js'), 'utf-8');
// eslint-disable-next-line no-eval
eval(mdSource);

describe('md.js — renderMarkdown', () => {
  it('returns empty string for null/undefined input', () => {
    expect(renderMarkdown(null)).toBe('');
    expect(renderMarkdown(undefined)).toBe('');
    expect(renderMarkdown('')).toBe('');
  });

  it('renders a simple paragraph', () => {
    const html = renderMarkdown('Hello world');
    expect(html).toContain('<p class="md-p">Hello world</p>');
  });

  it('renders multiple paragraphs separated by blank lines', () => {
    const html = renderMarkdown('First paragraph\n\nSecond paragraph');
    expect(html).toContain('<p class="md-p">First paragraph</p>');
    expect(html).toContain('<p class="md-p">Second paragraph</p>');
  });

  it('renders headers h1 through h6', () => {
    expect(renderMarkdown('# Title')).toContain('<h1 class="md-h1">Title</h1>');
    expect(renderMarkdown('## Section')).toContain('<h2 class="md-h2">Section</h2>');
    expect(renderMarkdown('### Sub')).toContain('<h3 class="md-h3">Sub</h3>');
    expect(renderMarkdown('#### Deep')).toContain('<h4 class="md-h4">Deep</h4>');
    expect(renderMarkdown('##### Deeper')).toContain('<h5 class="md-h5">Deeper</h5>');
    expect(renderMarkdown('###### Deepest')).toContain('<h6 class="md-h6">Deepest</h6>');
  });

  it('renders horizontal rules', () => {
    expect(renderMarkdown('---')).toContain('<hr class="md-hr">');
    expect(renderMarkdown('***')).toContain('<hr class="md-hr">');
  });

  it('renders unordered lists', () => {
    const html = renderMarkdown('- item1\n- item2\n- item3');
    expect(html).toContain('<ul class="md-ul">');
    expect(html).toContain('<li>item1</li>');
    expect(html).toContain('<li>item2</li>');
    expect(html).toContain('<li>item3</li>');
  });

  it('renders ordered lists', () => {
    const html = renderMarkdown('1. first\n2. second\n3. third');
    expect(html).toContain('<ol class="md-ol">');
    expect(html).toContain('<li>first</li>');
    expect(html).toContain('<li>second</li>');
    expect(html).toContain('<li>third</li>');
  });

  it('renders code blocks with language class', () => {
    const html = renderMarkdown('```javascript\nconst x = 1;\n```');
    expect(html).toContain('<pre class="code-block" class="lang-javascript">');
    expect(html).toContain('<code>const x = 1;</code>');
  });

  it('renders code blocks without language', () => {
    const html = renderMarkdown('```\nplain code\n```');
    expect(html).toContain('<pre class="code-block">');
    expect(html).toContain('<code>plain code</code>');
  });

  it('escapes HTML in code blocks', () => {
    const html = renderMarkdown('```\n<div>html</div>\n```');
    expect(html).toContain('&lt;div&gt;html&lt;/div&gt;');
  });

  it('escapes HTML in regular text to prevent XSS', () => {
    const html = renderMarkdown('<script>alert("xss")</script>');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>alert');
  });

  it('renders inline code', () => {
    const html = renderMarkdown('Use `npm test` to run');
    expect(html).toContain('<code class="md-code">npm test</code>');
  });

  it('renders bold text', () => {
    const html = renderMarkdown('This is **bold** text');
    expect(html).toContain('<strong>bold</strong>');
  });

  it('renders italic text', () => {
    const html = renderMarkdown('This is *italic* text');
    expect(html).toContain('<em>italic</em>');
  });

  it('renders links', () => {
    const html = renderMarkdown('[Click here](https://example.com)');
    expect(html).toContain('<a href="https://example.com" target="_blank">Click here</a>');
  });
});

describe('md.js — renderTable', () => {
  it('renders a basic table with headers and rows', () => {
    const md = '| Name | Age |\n| --- | --- |\n| Alice | 30 |\n| Bob | 25 |';
    const html = renderMarkdown(md);
    expect(html).toContain('<table class="md-table">');
    expect(html).toContain('<thead>');
    expect(html).toContain('<th>Name</th>');
    expect(html).toContain('<th>Age</th>');
    expect(html).toContain('<tbody>');
    expect(html).toContain('<td>Alice</td>');
    expect(html).toContain('<td>30</td>');
    expect(html).toContain('<td>Bob</td>');
    expect(html).toContain('<td>25</td>');
  });

  it('handles tables with alignment separators', () => {
    const md = '| Left | Center | Right |\n| :--- | :---: | ---: |\n| a | b | c |';
    const html = renderMarkdown(md);
    expect(html).toContain('<table class="md-table">');
    expect(html).toContain('<th>Left</th>');
    expect(html).toContain('<td>a</td>');
  });

  it('handles tables without trailing pipe', () => {
    const md = '| Name | Age\n| --- | ---\n| Alice | 30';
    const html = renderMarkdown(md);
    expect(html).toContain('<table class="md-table">');
    expect(html).toContain('<td>Alice</td>');
  });
});

describe('md.js — splitTableRow', () => {
  it('splits a row with leading and trailing pipes', () => {
    expect(splitTableRow('| a | b |')).toEqual(['a', 'b']);
  });

  it('splits a row without trailing pipe', () => {
    expect(splitTableRow('| a | b')).toEqual(['a', 'b']);
  });

  it('returns null for non-table line', () => {
    expect(splitTableRow('regular text')).toBeNull();
  });

  it('returns empty array for empty cells', () => {
    expect(splitTableRow('| | |')).toEqual(['', '']);
  });
});

describe('md.js — inlineMd', () => {
  it('returns empty string for falsy input', () => {
    expect(inlineMd(null)).toBe('');
    expect(inlineMd(undefined)).toBe('');
    expect(inlineMd('')).toBe('');
  });

  it('renders bold and italic together', () => {
    const html = inlineMd('**bold** and *italic*');
    expect(html).toContain('<strong>bold</strong>');
    expect(html).toContain('<em>italic</em>');
  });

  it('renders inline code protecting content from further processing', () => {
    const html = inlineMd('`**not bold**`');
    expect(html).toContain('<code class="md-code">**not bold**</code>');
    expect(html).not.toContain('<strong>');
  });

  it('renders links with target _blank', () => {
    const html = inlineMd('[text](http://url.com)');
    expect(html).toContain('<a href="http://url.com" target="_blank">text</a>');
  });
});

describe('md.js — escapeHtml', () => {
  it('escapes ampersand', () => {
    expect(escapeHtml('a & b')).toBe('a &amp; b');
  });

  it('escapes angle brackets', () => {
    expect(escapeHtml('<div>')).toBe('&lt;div&gt;');
  });

  it('escapes double quotes', () => {
    expect(escapeHtml('"hello"')).toBe('&quot;hello&quot;');
  });

  it('escapes all special characters together', () => {
    expect(escapeHtml('<a href="x">A & B</a>')).toBe(
      '&lt;a href=&quot;x&quot;&gt;A &amp; B&lt;/a&gt;'
    );
  });

  it('returns empty string for falsy input', () => {
    expect(escapeHtml(null)).toBe('');
    expect(escapeHtml('')).toBe('');
  });
});
