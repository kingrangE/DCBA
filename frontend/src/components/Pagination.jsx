export default function Pagination({ result, onChange }) {
  const { content, hasPrevious, hasNext, previousCursor, nextCursor } = result
  if (!hasPrevious && !hasNext) return null
  return (
    <nav className="pagination" aria-label="문제 페이지">
      <button type="button" onClick={() => onChange({ before: null, after: null })}>처음</button>
      <button type="button" disabled={!hasPrevious || !content.length} onClick={() => onChange({ before: null, after: previousCursor })}>이전</button>
      <button type="button" disabled={!hasNext || !content.length} onClick={() => onChange({ before: nextCursor, after: null })}>다음</button>
    </nav>
  )
}
