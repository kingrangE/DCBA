import { useEffect, useState } from 'react'
import { api } from '../api/client'
import ExercisePool from '../components/ExercisePool'
import Layout from '../components/Layout'
import { useAuth } from '../context/AuthContext'

export default function AdminPage() {
  const { expireSession } = useAuth()
  const [paused, setPaused] = useState(null)
  const [loadAttempt, setLoadAttempt] = useState(0)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    api.automaticGenerationPause()
      .then((status) => { if (active) setPaused(status.paused) })
      .catch((failure) => {
        if (!active) return
        if (failure.status === 401) expireSession()
        else setError(failure.status === 403 ? failure.message : '중단 상태를 불러오지 못했어요.')
      })
    return () => { active = false }
  }, [expireSession, loadAttempt])

  const pause = async () => {
    setSubmitting(true)
    setError('')
    try {
      const status = await api.pauseAutomaticGeneration()
      setPaused(status.paused)
    } catch (failure) {
      if (failure.status === 401) expireSession()
      else setError(failure.status === 403 ? failure.message : '중단 여부를 확인하지 못했어요. 다시 시도해 주세요.')
    } finally {
      setSubmitting(false)
    }
  }

  const reload = () => {
    setError('')
    setLoadAttempt((attempt) => attempt + 1)
  }

  return (
    <Layout>
      <main className="profile-page" id="main-content" tabIndex={-1}>
        <h1>관리자</h1>
        <section className="profile-card" aria-labelledby="generation-admin-title">
          <h2 id="generation-admin-title">자동 문제 생성</h2>
          <div className="stack-form">
            <p className="muted">정기 생성의 새 작업 등록을 중단합니다. 수동 요청과 이미 접수된 작업은 계속 처리됩니다.</p>
            {paused === true && <p className="notice notice--success" role="status">자동 생성을 중단했어요.</p>}
            {paused === null && !error && <p role="status">중단 상태를 확인하는 중…</p>}
            {error && <p className="form-status form-status--error" role="alert">{error}</p>}
            {paused === null && error && <button className="button button--secondary" type="button" onClick={reload}>상태 다시 확인</button>}
            {paused === false && (
              <button className="button button--primary" type="button" disabled={submitting} onClick={pause}>
                {submitting ? '중단하는 중…' : '자동 생성 중단'}
              </button>
            )}
          </div>
        </section>
        <ExercisePool />
      </main>
    </Layout>
  )
}
