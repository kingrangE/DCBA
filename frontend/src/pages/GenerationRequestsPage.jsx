import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import { useAuth } from '../context/AuthContext'
import ExerciseCard from '../components/ExerciseCard'
import Layout from '../components/Layout'

function GeneratedExercise({ requestId }) {
  const [exercise, setExercise] = useState(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [pending, setPending] = useState(null)
  const { expireSession } = useAuth()

  useEffect(() => {
    let active = true
    api.generatedExercise(requestId).then((item) => {
      if (active) setExercise(item)
    }).catch((failure) => {
      if (!active) return
      if (failure.status === 401) expireSession()
      else setError(failure.status === 404 ? '삭제되었거나 볼 수 없는 문제예요.' : '문제를 불러오지 못했어요.')
    })
    return () => { active = false }
  }, [requestId, attempt, expireSession])

  const changePreference = async (item, key) => {
    const action = key === 'saved' ? (item.saved ? api.unsave : api.save) : api.ban
    setPending(key)
    try {
      await action(item.id)
      if (key === 'banned') {
        setExercise(null)
        setError('차단한 문제예요. 문제 목록에서 차단을 해제할 수 있어요.')
      } else {
        setExercise({ ...item, saved: !item.saved })
      }
    } finally {
      setPending(null)
    }
  }

  return <>
    {!exercise && !error && <p role="status">문제를 불러오는 중이에요.</p>}
    {error && <div className="notice notice--error" role="alert">{error}
      {error === '문제를 불러오지 못했어요.' && <button className="button button--secondary" type="button" onClick={() => {
        setError('')
        setAttempt((value) => value + 1)
      }}>다시 불러오기</button>}
    </div>}
    {exercise && <ExerciseCard exercise={exercise} pending={pending} onChangePreference={changePreference} />}
  </>
}

export default function GenerationRequestsPage() {
  const { requestId } = useParams()
  return (
    <Layout>
      <main id="main-content" className="profile-page" tabIndex={-1}>
        <div className="section-heading">
          <h1>요청한 문제</h1>
          <Link className="button button--secondary" to="/dashboard">문제 목록으로</Link>
        </div>
        <GeneratedExercise key={requestId} requestId={requestId} />
      </main>
    </Layout>
  )
}
