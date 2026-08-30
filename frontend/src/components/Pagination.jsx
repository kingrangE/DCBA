export default function Pagination({ page, totalPages, onChange }) {
  if (totalPages <= 1) return null
  const start = Math.max(0, Math.min(page - 2, totalPages - 5))
  const pages = Array.from({ length: Math.min(5, totalPages) }, (_, index) => start + index)

  return (
    <nav className="pagination" aria-label="문제 페이지">
      <button type="button" disabled={page === 0} onClick={() => onChange(page - 1)}>←</button>
      {pages.map((item) => (
        <button
          type="button"
          className={item === page ? 'is-current' : ''}
          aria-current={item === page ? 'page' : undefined}
          onClick={() => onChange(item)}
          key={item}
        >
          {item + 1}
        </button>
      ))}
      <button type="button" disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)}>→</button>
    </nav>
  )
}
