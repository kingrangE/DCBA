import { useId, useState } from 'react'
import Markdown from 'react-markdown'

export function ExerciseMarkdown({ children }) {
  return (
    <Markdown
      skipHtml
      unwrapDisallowed
      allowedElements={['h3', 'h5', 'p', 'ul', 'ol', 'li', 'strong', 'em', 'code']}
    >
      {children}
    </Markdown>
  )
}

export function ExerciseQuestion({ question }) {
  return (
    <div className="exercise-markdown question-copy">
      {question.includes('\n') ? <ExerciseMarkdown>{question}</ExerciseMarkdown> : <h3>{question}</h3>}
    </div>
  )
}

export default function ExerciseCard({ exercise, pending, onChangePreference, onDelete }) {
  const answerId = useId()
  const [showAnswer, setShowAnswer] = useState(false)
  const [actionError, setActionError] = useState('')

  const changePreference = async (key, event) => {
    if (pending) return
    setActionError('')
    try {
      await onChangePreference(exercise, key, event.currentTarget)
    } catch {
      setActionError(key === 'saved' ? '저장 상태를 바꾸지 못했어요. 다시 시도해 주세요.' : '차단 상태를 바꾸지 못했어요. 다시 시도해 주세요.')
    }
  }

  return (
    <article className={`exercise-card ${showAnswer ? 'exercise-card--answer' : ''}`}>
      <div className="card-meta">
        <span>{exercise.subjectName}</span>
        <span>난이도 {exercise.levelNumber}</span>
        {onDelete && <span>#{exercise.id}</span>}
      </div>
      <ExerciseQuestion question={exercise.question} />
      <div className="card-actions">
        <button className="button button--primary" type="button" aria-expanded={showAnswer} aria-controls={answerId} onClick={() => setShowAnswer((value) => !value)}>
          {showAnswer ? '해설 접기' : '해설 보기'}
        </button>
        {onDelete ? (
          <button className="button button--danger" type="button" disabled={Boolean(pending)} onClick={() => onDelete(exercise)}>
            문제 삭제
          </button>
        ) : (
          <>
            <button
              className={`button button--secondary ${exercise.saved ? 'is-active' : ''}`}
              type="button"
              aria-disabled={Boolean(pending)}
              onClick={(event) => changePreference('saved', event)}
            >
              {pending === 'saved' ? '처리 중…' : exercise.saved ? '저장 취소' : '저장'}
            </button>
            <button
              className={`button button--secondary ${exercise.banned ? 'is-active' : ''}`}
              type="button"
              aria-disabled={Boolean(pending)}
              onClick={(event) => changePreference('banned', event)}
            >
              {pending === 'banned' ? '처리 중…' : exercise.banned ? '차단 해제' : '차단'}
            </button>
          </>
        )}
      </div>
      <div className="answer-panel" id={answerId} hidden={!showAnswer}>
        <h4>해설</h4>
        {showAnswer && (
          <div className="exercise-markdown answer-copy">
            <ExerciseMarkdown>{exercise.answer}</ExerciseMarkdown>
          </div>
        )}
      </div>
      {actionError && <p className="form-status form-status--error" role="alert">{actionError}</p>}
    </article>
  )
}
