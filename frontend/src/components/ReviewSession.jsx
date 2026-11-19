import { useEffect, useId, useRef, useState } from 'react'
import { api } from '../api/client'
import { useAuth } from '../context/AuthContext'
import { ExerciseMarkdown, ExerciseQuestion } from './ExerciseCard'

const answerLimit = 2000
const retryTime = new Intl.DateTimeFormat('ko-KR', { hour: 'numeric', minute: '2-digit', second: '2-digit' })

function FeedbackGroup({ title, criteria }) {
  if (!criteria.length) return null
  return (
    <div>
      <h4>{title}</h4>
      <ul>
        {criteria.map(({ id, label, comment }) => (
          <li key={id}><strong>{label}</strong> · {comment}</li>
        ))}
      </ul>
    </div>
  )
}

function ReviewQuestion({ item, onMark, onNext, onLeave, last }) {
  const { expireSession } = useAuth()
  const inputId = useId()
  const answerId = useId()
  const feedbackRequest = useRef(0)
  const [answer, setAnswer] = useState('')
  const [showAnswer, setShowAnswer] = useState(false)
  const [feedback, setFeedback] = useState(null)
  const [waiting, setWaiting] = useState(false)
  const [marking, setMarking] = useState(false)
  const [unavailable, setUnavailable] = useState(false)
  const feedbackAvailable = item.feedbackAvailable
  const [feedbackError, setFeedbackError] = useState('')
  const [markError, setMarkError] = useState('')
  const [markStatus, setMarkStatus] = useState('')
  const [retryAt, setRetryAt] = useState(null)

  useEffect(() => () => { feedbackRequest.current += 1 }, [])
  useEffect(() => {
    if (!retryAt) return
    const timer = setTimeout(() => {
      setRetryAt(null)
      setFeedbackError('')
    }, Math.max(0, retryAt - Date.now()))
    return () => clearTimeout(timer)
  }, [retryAt])

  const requestFeedback = async (event) => {
    event.preventDefault()
    const submitted = answer.trim()
    if (!submitted || waiting || retryAt || unavailable || !feedbackAvailable) return
    const requestId = ++feedbackRequest.current
    setWaiting(true)
    setFeedbackError('')
    try {
      const result = await api.reviewFeedback(item.id, submitted)
      if (feedbackRequest.current === requestId) setFeedback({ result, answer: submitted })
    } catch (error) {
      if (feedbackRequest.current !== requestId) return
      if (error.status === 401) expireSession()
      else if (error.status === 404) setUnavailable(true)
      else {
        const seconds = Number(error.retryAfter)
        if (error.status === 429 && Number.isFinite(seconds) && seconds > 0) {
          setRetryAt(Date.now() + seconds * 1000)
        }
        setFeedbackError(error.status ? error.message : '피드백을 받지 못했어요. 다시 시도해 주세요.')
      }
    } finally {
      if (feedbackRequest.current === requestId) setWaiting(false)
    }
  }

  const toggleMark = async () => {
    if (marking || unavailable) return
    const enabled = !item.reviewAgain
    setMarking(true)
    setMarkError('')
    setMarkStatus('')
    try {
      await api.markReview(item.id, enabled)
      onMark(enabled)
      setMarkStatus(enabled ? '다시 풀기로 표시했어요.' : '다시 풀기 표시를 해제했어요.')
    } catch (error) {
      if (error.status === 401) expireSession()
      else if (error.status === 404) setUnavailable(true)
      else setMarkError('다시 풀기 상태를 바꾸지 못했어요. 다시 시도해 주세요.')
    } finally {
      setMarking(false)
    }
  }

  return (
    <div className="review-content">
      <div className="card-meta"><span>{item.subjectName}</span><span>난이도 {item.levelNumber}</span></div>
      <ExerciseQuestion question={item.question} />
      <form className="stack-form" onSubmit={requestFeedback}>
        <label htmlFor={inputId}>내 답변</label>
        <textarea id={inputId} rows={6} maxLength={answerLimit} value={answer} readOnly={waiting}
          aria-describedby={inputId + '-count'} onChange={(event) => setAnswer(event.target.value)} />
        <p className="field-help" id={inputId + '-count'}>{answer.length.toLocaleString('ko-KR')} / 2,000자</p>
        {unavailable ? (
          <p role="status">이 문제의 저장 또는 차단 상태가 바뀌었어요. 다음 문제로 이동할 수 있습니다.</p>
        ) : feedbackAvailable ? (
          <div className="review-feedback-action">
            <p className="field-help">피드백을 요청하면 답변이 AI 서비스(NVIDIA)에 전달됩니다.</p>
            <button className="button button--primary" type="submit" disabled={!answer.trim() || waiting || Boolean(retryAt)}>
              {waiting ? '피드백 받는 중…' : 'AI 피드백 받기'}
            </button>
          </div>
        ) : (
          <p className="field-help">이 문제에는 AI 피드백을 사용할 수 없어요. 해설에서 답변을 비교해 보세요.</p>
        )}
      </form>
      <span className="review-feedback-status" role="status">
        {waiting ? 'AI 피드백을 기다리는 중이에요.' : feedback && !feedbackError && !unavailable ? 'AI 피드백이 도착했어요.' : ''}
      </span>
      {feedbackError && <p className="notice notice--error" role="alert">
        {retryAt ? '피드백 요청 한도에 도달했어요. ' + retryTime.format(retryAt) + '부터 다시 요청할 수 있어요.' : feedbackError}
      </p>}
      {feedback && (
        <section className="review-feedback answer-panel" aria-label="AI 피드백">
          {answer.trim() !== feedback.answer && <p className="field-help">아래는 수정하기 전 답변에 대한 피드백이에요.</p>}
          <FeedbackGroup title="잘 짚은 점" criteria={feedback.result.criteria.filter((item) => item.status === 'met')} />
          <FeedbackGroup title="보완할 점" criteria={feedback.result.criteria.filter((item) => item.status !== 'met')} />
          <div><h4>답변을 다듬을 힌트</h4><p>{feedback.result.nextStep}</p></div>
        </section>
      )}
      <div className="card-actions">
        <button className="button button--secondary" type="button" aria-expanded={showAnswer} aria-controls={answerId}
          onClick={() => setShowAnswer((previous) => !previous)}>{showAnswer ? '해설 접기' : '해설 보기'}</button>
        <button className="button button--secondary" type="button" disabled={marking || unavailable} aria-pressed={item.reviewAgain}
          onClick={toggleMark}>{marking ? '저장 중…' : item.reviewAgain ? '다시 풀기 해제' : '다시 풀기'}</button>
      </div>
      <div className="answer-panel" id={answerId} hidden={!showAnswer}>
        <h4>해설</h4>
        {showAnswer && <div className="exercise-markdown answer-copy"><ExerciseMarkdown>{item.answer}</ExerciseMarkdown></div>}
      </div>
      {markError && <p className="notice notice--error" role="alert">{markError}</p>}
      {markStatus && <p className="notice notice--success" role="status">{markStatus}</p>}
      <div className="card-actions review-navigation">
        <button className="button button--secondary" type="button" disabled={marking} onClick={onLeave}>연습 나가기</button>
        <button className="button button--primary" type="button" disabled={marking} onClick={onNext}>
          {last ? '연습 끝내기' : '다음 문제'}
        </button>
      </div>
    </div>
  )
}

export default function ReviewSession({ initialItems, onReturn }) {
  const heading = useRef(null)
  const [items, setItems] = useState(initialItems)
  const [position, setPosition] = useState(0)
  const complete = position === items.length

  useEffect(() => { heading.current?.focus() }, [position])

  return (
    <main className="profile-page" id="main-content" tabIndex={-1}>
      <section className="profile-card" aria-labelledby="review-heading">
        <header className="section-heading">
          <h1 id="review-heading" ref={heading} tabIndex={-1}>{complete ? '연습 완료' : '저장한 문제 연습'}</h1>
          {!complete && <span className="muted" aria-label={`총 ${items.length}문제 중 ${position + 1}번째`}>{position + 1} / {items.length}</span>}
        </header>
        {!complete && <progress className="review-progress" value={position + 1} max={items.length} aria-hidden="true" />}
        {complete ? (
          <div className="review-content">
            <p>진행한 문제 <strong>{position}개</strong></p>
            <p>다시 풀기로 표시된 문제 <strong>{items.filter((item) => item.reviewAgain).length}개</strong></p>
            <div><button className="button button--primary" onClick={onReturn} type="button">목록으로 돌아가기</button></div>
          </div>
        ) : (
          <ReviewQuestion key={position} item={items[position]} last={position === items.length - 1}
            onNext={() => setPosition(position + 1)}
            onLeave={() => { if (window.confirm('연습을 나가면 이번 회차에서 작성한 답변이 사라져요. 나갈까요?')) onReturn() }}
            onMark={(reviewAgain) => setItems((current) => current.map((item, index) =>
              index === position ? { ...item, reviewAgain } : item))} />
        )}
      </section>
    </main>
  )
}
