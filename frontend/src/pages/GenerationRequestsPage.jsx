import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import { useAuth } from '../context/AuthContext'
import ExerciseCard from '../components/ExerciseCard'
import Layout from '../components/Layout'
import { completionMessage } from '../components/generationMessages'

function RecentRequests({ completedIds }) {
  const [requests, setRequests] = useState(null)
  const [error, setError] = useState(false)
  const { expireSession } = useAuth()

  useEffect(() => {
    let active = true
    let timer
    const read = async () => {
      try {
        const data = await api.recentGenerationRequests()
        if (!active) return
        setRequests(data)
        setError(false)
      } catch (failure) {
        if (!active) return
        if (failure.status === 401) { expireSession(); return }
        setError(true)
      }
      timer = setTimeout(read, 5000)
    }
    read()
    return () => { active = false; clearTimeout(timer) }
  }, [expireSession])
  const shown = requests?.filter((request) => !completedIds.has(request.requestId))

  return (
    <section className="profile-card generation-notifications" aria-labelledby="recent-generation">
      <h2 id="recent-generation">진행·실패 요청</h2>
      {error && <p className="notice notice--error" role="alert">진행 상태를 불러오지 못했어요. 잠시 후 다시 확인해요.</p>}
      {!requests && !error && <p role="status">진행 상태를 불러오는 중이에요.</p>}
      {shown?.length === 0 && <p>진행하거나 실패한 최근 요청이 없어요.</p>}
      {shown?.length > 0 && <ul>
        {shown.map(({ requestId, subject, level, requestedAt, progress }) => <li key={requestId}>
          <p>{subject} · {level}</p>
          <p>{({ queued: '접수됨', running: '생성 중', failed: '실패' })[progress.status]} · <time dateTime={requestedAt}>{new Date(requestedAt).toLocaleString('ko-KR')}</time></p>
          {progress.status === 'failed' && <>
            <p>{progress.message}</p>
            <Link to="/dashboard#generate">생성 화면에서 다시 요청하기</Link>
          </>}
        </li>)}
      </ul>}
    </section>
  )
}

function CompletionList({ setCompletedIds }) {
  const [page, setPage] = useState(0)
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [pending, setPending] = useState(null)
  const { expireSession } = useAuth()

  useEffect(() => {
    let active = true
    let timer
    const read = async () => {
      try {
        const data = await api.generationNotifications(page)
        if (!active) return
        setResult(data)
        setCompletedIds((ids) => new Set([...ids, ...data.items.map((item) => item.requestId)]))
        setError('')
      } catch (failure) {
        if (!active) return
        if (failure.status === 401) { expireSession(); return }
        setError('완료 알림을 불러오지 못했어요.')
      }
      if (page === 0) timer = setTimeout(read, 5000)
    }
    read()
    return () => { active = false; clearTimeout(timer) }
  }, [page, attempt, expireSession, setCompletedIds])

  const markRead = async (id) => {
    setPending(id)
    setError('')
    try {
      await api.markGenerationRead(id)
      setResult((data) => ({
        ...data, items: data.items.map((item) => item.requestId === id ? { ...item, readAt: new Date().toISOString() } : item),
      }))
    } catch (failure) {
      if (failure.status === 401) expireSession()
      else setError('읽음 상태를 저장하지 못했어요. 다시 시도해 주세요.')
    } finally {
      setPending(null)
    }
  }

  const move = (nextPage) => {
    setResult(null)
    setError('')
    setPage(nextPage)
  }

  return (
    <section className="profile-card generation-notifications" aria-labelledby="completed-generation">
      <h2 id="completed-generation">완료 알림</h2>
      {error && <p className="notice notice--error" role="alert">{error}</p>}
      {!result && !error && <p role="status">완료 알림을 불러오는 중이에요.</p>}
      {!result && error && <button className="button button--secondary" type="button" onClick={() => {
        setError('')
        setAttempt((value) => value + 1)
      }}>다시 불러오기</button>}
      {result?.items.length === 0 && <p>완료된 생성 요청이 없어요.</p>}
      {result?.items.length > 0 && <ul>
        {result.items.map((item) => <li key={item.requestId}>
          <p>{item.readAt ? '읽음' : '새 알림'} · <time dateTime={item.completedAt}>{new Date(item.completedAt).toLocaleString('ko-KR')}</time></p>
          <p>{completionMessage(item.completionType)}</p>
          <div className="card-actions">
            <Link className="button button--primary" to={`/generation-requests/${item.requestId}`}>문제 풀기</Link>
            {!item.readAt && <button className="button button--secondary" type="button" disabled={pending !== null}
              onClick={() => markRead(item.requestId)}>읽음으로 표시</button>}
          </div>
        </li>)}
      </ul>}
      {result && (page > 0 || result.hasNext) && <div className="card-actions">
        <button className="button button--secondary" type="button" disabled={pending !== null || page === 0} onClick={() => move(page - 1)}>이전</button>
        <button className="button button--secondary" type="button" disabled={pending !== null || !result.hasNext} onClick={() => move(page + 1)}>다음</button>
      </div>}
    </section>
  )
}

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
  const [completedIds, setCompletedIds] = useState(() => new Set())
  return (
    <Layout>
      <main id="main-content" className="profile-page" tabIndex={-1}>
        <div className="profile-intro">
          <h1>{requestId ? '요청한 문제' : '내 생성 요청'}</h1>
          {requestId && <Link className="button button--secondary" to="/generation-requests">완료 알림으로 돌아가기</Link>}
        </div>
        {requestId ? <GeneratedExercise key={requestId} requestId={requestId} /> : <>
          <RecentRequests completedIds={completedIds} />
          <CompletionList setCompletedIds={setCompletedIds} />
        </>}
      </main>
    </Layout>
  )
}
