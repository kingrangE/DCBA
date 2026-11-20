import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { useAuth } from '../context/AuthContext'

export default function GenerationPanel({ options, optionsLoading, optionsError, onRetryOptions }) {
  const [subject, setSubject] = useState('')
  const [level, setLevel] = useState('')
  const { user, expireSession } = useAuth()
  const requestKey = `dcba-generation-${user.id}`
  const [learningIntent, setLearningIntent] = useState('')
  const [intentError, setIntentError] = useState(false)
  const [status, setStatus] = useState(null)
  const [requestId, setRequestId] = useState(() => localStorage.getItem(requestKey))
  const [progress, setProgress] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const requestInFlight = useRef(false)

  useEffect(() => {
    if (!requestId) return
    let active = true
    let timer
    const poll = async () => {
      try {
        const result = await api.generationStatus(requestId)
        if (!active) return
        setProgress(result)
        if (result.status === 'completed' || result.status === 'failed') return
      } catch (error) {
        if (!active) return
        if (error.status === 401) { expireSession(); return }
        if (error.status === 404) {
          localStorage.removeItem(requestKey)
          setProgress(null)
          return
        }
        setProgress({ status: 'unavailable' })
      }
      timer = setTimeout(poll, 2500)
    }
    poll()
    return () => { active = false; clearTimeout(timer) }
  }, [expireSession, requestId, requestKey])

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
    const intent = learningIntent.trim()
    if (Array.from(intent).length > 200) {
      setIntentError(true)
      event.currentTarget.elements.namedItem('learningIntent').focus()
      return
    }
    setIntentError(false)

    requestInFlight.current = true
    setSubmitting(true)
    setStatus(null)
    try {
      const result = await api.generate(subject, level, intent)
      if (result.requestId) {
        localStorage.setItem(requestKey, result.requestId)
        setProgress({ status: 'queued' })
        setRequestId(result.requestId)
      } else {
        setStatus({ type: 'success', text: result.message })
      }
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
      <p className="muted">분야와 난이도를 선택하세요. 완성된 문제는 저장한 문제에 추가돼요.</p>
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
          <label>
            학습하고 싶은 내용 (선택)
            <textarea name="learningIntent" rows={3} value={learningIntent} disabled={submitting}
              aria-invalid={intentError || undefined} aria-describedby={intentError ? 'generation-intent-error' : 'generation-intent-help'}
              onChange={(event) => setLearningIntent(event.target.value)} />
            <span id="generation-intent-help" className="muted">공개 가능한 CS 개념이나 학습 목표를 한 문장으로 적어 주세요. 계정이나 이메일 등 개인·조직 정보는 제외해 주세요.</span>
          </label>
          {intentError && <p id="generation-intent-error" role="alert" className="form-status form-status--error">양끝 공백을 제외해 200자 이하로 줄여 주세요.</p>}
          <button className="button button--primary" type="submit" disabled={submitting}>
            {submitting ? '요청 접수 중…' : '생성 요청하기'}
          </button>
          {status && <p className={`form-status form-status--${status.type}`} role={status.type === 'error' ? 'alert' : 'status'}>{status.text}</p>}
        </form>
      )}
      {progress && <div className="generation-progress" role={progress.status === 'failed' ? 'alert' : 'status'}>
        {progress.status === 'queued' && <p>접수됨</p>}
        {progress.status === 'running' && <p>생성 중</p>}
        {progress.status === 'unavailable' && <p>진행 상태를 다시 확인하고 있어요.</p>}
        {progress.status === 'failed' && <p>실패 · {progress.message}</p>}
        {progress.status === 'completed' && <>
          <p>완료 · 요청한 문제가 생성되어 저장한 문제에 추가됐어요.</p>
          <Link to={`/generation-requests/${requestId}`}>문제 풀기</Link>
        </>}
      </div>}
    </section>
  )
}
