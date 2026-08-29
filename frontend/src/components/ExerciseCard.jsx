import { useState } from 'react'

export default function ExerciseCard({ exercise, onSave, onBan, busy }) {
  const [showAnswer, setShowAnswer] = useState(false)

  return (
    <article className={`exercise-card ${showAnswer ? 'exercise-card--answer' : ''}`}>
      <div className="card-meta">
        <span>{exercise.subjectName}</span>
        <span>LEVEL {exercise.levelNumber}</span>
      </div>
      <span className="card-number">Q{String(exercise.id).padStart(3, '0')}</span>
      <h3>{showAnswer ? '해설' : exercise.question}</h3>
      {showAnswer && <p className="answer-copy">{exercise.answer}</p>}
      <div className="card-actions">
        <button className="button button--dark" type="button" onClick={() => setShowAnswer((value) => !value)}>
          {showAnswer ? '문제로 돌아가기' : '정답 확인'}
        </button>
        <button
          className={`icon-button ${exercise.saved ? 'is-active' : ''}`}
          type="button"
          disabled={busy}
          onClick={() => onSave(exercise)}
          aria-label={exercise.saved ? '저장 취소' : '문제 저장'}
          title={exercise.saved ? '저장 취소' : '문제 저장'}
        >
          {exercise.saved ? '★' : '☆'}
        </button>
        <button
          className={`icon-button icon-button--ban ${exercise.banned ? 'is-active' : ''}`}
          type="button"
          disabled={busy}
          onClick={() => onBan(exercise)}
          aria-label={exercise.banned ? '차단 취소' : '문제 차단'}
          title={exercise.banned ? '차단 취소' : '문제 차단'}
        >
          {exercise.banned ? '↺' : '×'}
        </button>
      </div>
    </article>
  )
}
