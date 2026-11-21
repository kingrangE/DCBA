import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { useAuth } from '../context/AuthContext'
import { completionMessage } from './generationMessages'

const notificationTime = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })

export default function GenerationNotifications() {
  const id = useId()
  const panel = useRef(null)
  const heading = useRef(null)
  const { expireSession } = useAuth()
  const [page, setPage] = useState(0)
  const [result, setResult] = useState(null)
  const [unread, setUnread] = useState(false)
  const [loadError, setLoadError] = useState(false)
  const [readError, setReadError] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [pending, setPending] = useState(null)

  useEffect(() => {
    let active = true
    let timer
    const load = async () => {
      try {
        const data = await api.generationNotifications(page)
        if (!active) return
        setResult(data)
        // The API exposes read state for the latest page, with no total unread count.
        if (page === 0) setUnread(data.items.some((item) => !item.readAt))
        setLoadError(false)
      } catch (failure) {
        if (!active) return
        if (failure.status === 401) { expireSession(); return }
        setLoadError(true)
      }
      if (page === 0) timer = setTimeout(load, 15000)
    }
    load()
    return () => { active = false; clearTimeout(timer) }
  }, [attempt, expireSession, page])

  const move = (nextPage, focusHeading = false) => {
    if (nextPage === page) return
    if (focusHeading) heading.current?.focus()
    setResult(null)
    setLoadError(false)
    setReadError(false)
    setPage(nextPage)
  }

  const markRead = async (requestId, button) => {
    if (pending !== null) return
    setPending(requestId)
    setReadError(false)
    try {
      await api.markGenerationRead(requestId)
      if (document.activeElement === button) button.previousElementSibling?.focus()
      setAttempt((value) => value + 1)
    } catch (failure) {
      if (failure.status === 401) expireSession()
      else setReadError(true)
    } finally {
      setPending(null)
    }
  }

  return <>
    <button className="notification-trigger" type="button" popoverTarget={id}
      aria-label={unread ? '생성 알림, 새 알림 있음' : '생성 알림'}>
      <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" />
      </svg>
      {unread && <span className="notification-dot" aria-hidden="true" />}
    </button>
    <section id={id} popover="auto" className="notification-popover" ref={panel}
      aria-labelledby={id + '-title'} onToggle={(event) => {
        if (event.newState === 'closed') move(0)
      }}>
      <header className="section-heading">
        <h2 id={id + '-title'} ref={heading} tabIndex={-1}>생성 알림</h2>
        <button className="text-button" type="button" popoverTarget={id} popoverTargetAction="hide">닫기</button>
      </header>
      {loadError && <div className="notice notice--error">
        <p role="alert">알림을 불러오지 못했어요.</p>
        <button className="button button--secondary" type="button" onClick={() => {
          heading.current?.focus()
          setAttempt((value) => value + 1)
        }}>다시 불러오기</button>
      </div>}
      {readError && <p className="notice notice--error" role="alert">읽음 상태를 저장하지 못했어요. 다시 시도해 주세요.</p>}
      {!result && !loadError && <p className="muted" role="status">알림을 불러오는 중이에요.</p>}
      {result?.items.length === 0 && <p className="muted">완료된 생성 요청이 없어요.</p>}
      {result?.items.length > 0 && <ul>
        {result.items.map((item) => <li key={item.requestId}>
          <p className="notification-meta">
            {!item.readAt && <strong>새 알림</strong>}
            <time dateTime={item.completedAt}>{notificationTime.format(new Date(item.completedAt))}</time>
          </p>
          <p>{completionMessage(item.completionType)}</p>
          <div className="card-actions">
            <Link className="button button--secondary" to={`/generation-requests/${item.requestId}`}
              onClick={() => panel.current?.hidePopover()}>문제 풀기</Link>
            {!item.readAt && <button className="text-button" type="button" aria-disabled={pending !== null}
              onClick={(event) => markRead(item.requestId, event.currentTarget)}>읽음으로 표시</button>}
          </div>
        </li>)}
      </ul>}
      {result && (page > 0 || result.hasNext) && <div className="card-actions">
        <button className="button button--secondary" type="button" disabled={pending !== null || page === 0} onClick={() => move(page - 1, true)}>이전</button>
        <button className="button button--secondary" type="button" disabled={pending !== null || !result.hasNext} onClick={() => move(page + 1, true)}>다음</button>
      </div>}
    </section>
  </>
}
