// Run with node --test; jsdom is needed only in the verification environment.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const staticPath = path.join(__dirname, '../../main/resources/static');

function render(content, sanitizer = true, markdown = true) {
    const dom = new JSDOM('<div data-markdown></div>', { runScripts: 'outside-only' });
    const element = dom.window.document.querySelector('div');
    element.setAttribute('data-markdown', content);
    if (markdown) dom.window.eval(fs.readFileSync(path.join(staticPath, 'vendor/marked.umd.js'), 'utf8'));
    if (sanitizer) dom.window.eval(fs.readFileSync(path.join(staticPath, 'vendor/purify.min.js'), 'utf8'));
    dom.window.eval(fs.readFileSync(path.join(staticPath, 'js/render-exercises.js'), 'utf8'));
    dom.window.document.dispatchEvent(new dom.window.Event('DOMContentLoaded'));
    return element;
}

test('keeps Markdown while stripping executable HTML and unsafe links', () => {
    const element = render('**Answer** <img src="x" onerror="alert(1)"><script>alert(1)</script><iframe src="https://example.invalid"></iframe> [link](javascript:alert(1))');
    assert.equal(element.querySelector('strong').textContent, 'Answer');
    assert.equal(element.querySelector('[onerror], script, iframe, [href^="javascript:"]'), null);
});

test('shows literal text if either rendering dependency is missing', () => {
    const content = '<img src="x" onerror="alert(1)"> **Answer**';
    for (const [sanitizer, markdown] of [[false, true], [true, false]]) {
        const element = render(content, sanitizer, markdown);
        assert.equal(element.textContent, content);
        assert.equal(element.children.length, 0);
    }
});
