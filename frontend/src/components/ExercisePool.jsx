import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { useAuth } from '../context/AuthContext'
import ExerciseCard from './ExerciseCard'
import Pagination from './Pagination'

const firstPage = { before: null, after: null }

export default function ExercisePool() {
  const { expireSession } = useAuth()
  const heading = useRef(null)
  const [cursor, setCursor] = useState(firstPage)
  const [result, setResult] = useState(null)
  const [loadAttempt, setLoadAttempt] = useState(0)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const isFirstPage = !cursor.before && !cursor.after

  useEffect(() => {
    let active = true
    api.exercisePool(cursor)
      .then((page) => {
        if (!active) return
        if (!page.content.length && (cursor.before || cursor.after)) setCursor(firstPage)
        else setResult(page)
      })
      .catch((failure) => {
        if (!active) return
        if (failure.status === 401) expireSession()
        else setError('문제 풀을 불러오지 못했어요.')
      })
    return () => { active = false }
  }, [cursor, expireSession, loadAttempt])

  const deleteExercise = async (exercise) => {
    const preview = exercise.question.split('\n')[0]
    if (!window.confirm(`문제 #${exercise.id}을 삭제할까요?\n${preview}\n\n모든 사용자의 저장·차단 목록에서도 삭제되며 복구할 수 없습니다.`)) return

    setPending(true)
    setError('')
    setNotice('')
    try {
      await api.deleteExercise(exercise.id)
      setNotice(`문제 #${exercise.id}을 삭제했어요.`)
      setResult(null)
      setLoadAttempt((attempt) => attempt + 1)
      heading.current?.focus()
    } catch (failure) {
      if (failure.status === 401) expireSession()
      else setError(failure.status === 403 ? failure.message : '삭제 여부를 확인하지 못했어요. 다시 시도해 주세요.')
    } finally {
      setPending(false)
    }
  }

  const changePage = (nextCursor) => {
    setError('')
    setNotice('')
    setResult(null)
    setCursor(nextCursor)
    heading.current?.focus()
  }

  return (
    <section className="profile-card" aria-labelledby="exercise-pool-title">
      <h2 id="exercise-pool-title" ref={heading} tabIndex={-1}>문제 풀</h2>
      <div className="stack-form">
        {notice && <p className="notice notice--success" role="status">{notice}</p>}
        {error && <p className="form-status form-status--error" role="alert">{error}</p>}
        {!result && !error && <p role="status">문제를 불러오는 중…</p>}
        {!result && error && <button className="button button--secondary" type="button" onClick={() => {
          setError('')
          setLoadAttempt((attempt) => attempt + 1)
        }}>다시 불러오기</button>}
        {result && (
          <>
            {result.content.length ? (
              <div className="exercise-grid">
                {result.content.map((exercise) => (
                  <ExerciseCard key={exercise.id} exercise={exercise} pending={pending} onDelete={deleteExercise} />
                ))}
              </div>
            ) : <p className="empty-state">문제 풀에 문제가 없어요.</p>}
            <Pagination result={result} disabled={pending} isFirstPage={isFirstPage} onChange={changePage} />
          </>
        )}
      </div>
    </section>
  )
}
