export default function Pagination({ result, disabled, isFirstPage, onChange }) {
  const { hasPrevious, hasNext, previousCursor, nextCursor } = result
  if (isFirstPage && !hasNext) return null

  return (
    <nav className="pagination" aria-label="문제 페이지">
      <button type="button" disabled={disabled || isFirstPage} onClick={() => onChange({ before: null, after: null })}>처음</button>
      <button type="button" disabled={disabled || !hasPrevious} onClick={() => onChange({ before: null, after: previousCursor })}>이전</button>
      <button type="button" disabled={disabled || !hasNext} onClick={() => onChange({ before: nextCursor, after: null })}>다음</button>
    </nav>
  )
}
