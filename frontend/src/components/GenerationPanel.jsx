import { useState } from 'react'
import { api } from '../api/client'

export default function GenerationPanel({ options, onGenerated }) {
  const [subject, setSubject] = useState('')
  const [level, setLevel] = useState('')
  const [status, setStatus] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  const submit = async (event) => {
    event.preventDefault()
    if (!subject || !level) {
      setStatus({ type: 'error', text: '분야와 난이도를 모두 선택해 주세요.' })
      return
    }
    setSubmitting(true)
    setStatus(null)
    try {
      const result = await api.generate(subject, level)
      setStatus({ type: 'success', text: `${result.message} 현재 대기열 ${result.queueSize}건` })
      onGenerated?.()
    } catch (error) {
      setStatus({ type: 'error', text: error.message })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="generation-panel">
      <div>
        <p className="eyebrow eyebrow--light">ON-DEMAND GENERATION</p>
        <h2>원하는 문제를<br />직접 요청하세요.</h2>
      </div>
      <form className="generation-form" onSubmit={submit}>
        <label>
          분야
          <select value={subject} onChange={(event) => setSubject(event.target.value)}>
            <option value="">분야 선택</option>
            {options.subjects.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
        <label>
          난이도
          <select value={level} onChange={(event) => setLevel(event.target.value)}>
            <option value="">난이도 선택</option>
            {options.levels.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
        <button className="button button--accent" type="submit" disabled={submitting}>
          {submitting ? '요청 중...' : '문제 생성 요청 →'}
        </button>
        {status && <p className={`form-status form-status--${status.type}`} role="status">{status.text}</p>}
      </form>
    </section>
  )
}
