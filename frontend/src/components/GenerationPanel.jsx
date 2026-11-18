import { useRef, useState } from 'react'
import { api } from '../api/client'

export default function GenerationPanel({ options, optionsLoading, optionsError, onRetryOptions }) {
  const [subject, setSubject] = useState('')
  const [level, setLevel] = useState('')
  const [status, setStatus] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const requestInFlight = useRef(false)

  if (options.generationEnabled === false) {
    return (
      <section className="generation-panel" id="generate" aria-labelledby="generation-heading">
        <h2 id="generation-heading">문제 생성</h2>
        <p className="muted">공개 웹에서는 새 문제 생성을 제한하고 있어요. 문제 목록에서 기존 문제를 풀고 저장할 수 있어요.</p>
      </section>
    )
  }

  const submit = async (event) => {
    event.preventDefault()
    if (requestInFlight.current) return

    requestInFlight.current = true
    setSubmitting(true)
    setStatus(null)
    try {
      const result = await api.generate(subject, level)
      setStatus({ type: 'success', text: result.message })
    } catch (error) {
      setStatus({
        type: 'error',
        text: error.status ? error.message : '요청이 접수됐는지 확인할 수 없어요. 다시 요청하기 전에 문제 목록을 확인해 주세요.',
      })
    } finally {
      requestInFlight.current = false
      setSubmitting(false)
    }
  }

  return (
    <section className="generation-panel" id="generate" tabIndex={-1} aria-labelledby="generation-heading">
      <h2 id="generation-heading">문제 생성 요청</h2>
      <p className="muted">분야와 난이도를 선택하세요. 생성이 끝나면 전체 문제의 목록 새로고침으로 확인할 수 있어요.</p>
      {optionsError ? (
        <div className="generation-unavailable">
          <p role="status">선택 항목을 불러오지 못했어요.</p>
          <button className="button button--secondary" type="button" onClick={onRetryOptions}>다시 불러오기</button>
        </div>
      ) : optionsLoading ? (
        <p role="status">선택 항목을 불러오는 중이에요.</p>
      ) : (
        <form className="generation-form" onSubmit={submit}>
          <label>
            분야
            <select value={subject} disabled={submitting} required onChange={(event) => setSubject(event.target.value)}>
              <option value="">분야 선택</option>
              {options.subjects.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </label>
          <label>
            난이도
            <select value={level} disabled={submitting} required onChange={(event) => setLevel(event.target.value)}>
              <option value="">난이도 선택</option>
              {options.levels.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </label>
          <button className="button button--primary" type="submit" disabled={submitting}>
            {submitting ? '요청 접수 중…' : '생성 요청하기'}
          </button>
          {status && <p className={`form-status form-status--${status.type}`} role={status.type === 'error' ? 'alert' : 'status'}>{status.text}</p>}
        </form>
      )}
    </section>
  )
}
