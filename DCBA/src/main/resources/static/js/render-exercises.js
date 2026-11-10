document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('[data-markdown]').forEach(element => {
        const content = element.getAttribute('data-markdown');
        if (window.marked && window.DOMPurify) {
            element.innerHTML = DOMPurify.sanitize(marked.parse(content), { USE_PROFILES: { html: true } });
        } else {
            element.textContent = content;
        }
    });
});
