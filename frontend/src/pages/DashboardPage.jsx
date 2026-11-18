import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import ExerciseCard from '../components/ExerciseCard'
import GenerationPanel from '../components/GenerationPanel'
import Layout from '../components/Layout'
import Pagination from '../components/Pagination'
import { useAuth } from '../context/AuthContext'

const emptyOptions = { subjects: [], levels: [] }
const firstPage = { before: null, after: null }
const views = [
  { value: 'all', label: '전체 문제' },
  { value: 'selected', label: '저장한 문제' },
  { value: 'banned', label: '차단한 문제' },
]
const preferences = {
  saved: {
    add: api.save,
    remove: api.unsave,
    added: '문제를 저장했어요.',
    removed: '문제 저장을 취소했어요.',
    hides: (view, enabled) => view === 'selected' && !enabled,
  },
  banned: {
    add: api.ban,
    remove: api.unban,
    added: '문제를 차단했어요.',
    removed: '문제 차단을 해제했어요.',
    hides: (view, enabled) => (view === 'all' && enabled) || (view === 'banned' && !enabled),
  },
}

export default function DashboardPage() {
  const { expireSession } = useAuth()
  const listHeading = useRef(null)
  const requestId = useRef(0)
  const [options, setOptions] = useState(emptyOptions)
  const [optionsError, setOptionsError] = useState('')
  const [optionsLoading, setOptionsLoading] = useState(true)
  const [optionsAttempt, setOptionsAttempt] = useState(0)
  const [filters, setFilters] = useState({ view: 'all', subject: '', level: '', ...firstPage })
  const [list, setList] = useState(null)
  const [listAttempt, setListAttempt] = useState(0)
  const [actionStatus, setActionStatus] = useState('')
  const [pendingPreferences, setPendingPreferences] = useState({})
  const current = list?.filters === filters ? list : null
  const result = current?.result
  const loading = !current || current.attempt !== listAttempt
  const listError = loading ? '' : current.error
  const isFiltered = Boolean(filters.subject || filters.level)
  const isFirstPage = !filters.before && !filters.after
  const showGenerationLink = !optionsLoading && !optionsError && options.generationEnabled !== false

  useEffect(() => {
    let active = true
    api.options()
      .then((data) => { if (active) setOptions(data) })
      .catch((error) => {
        if (!active) return
        if (error.status === 401) expireSession()
        else setOptionsError('분야와 난이도 목록을 불러오지 못했어요.')
      })
      .finally(() => { if (active) setOptionsLoading(false) })
    return () => { active = false }
  }, [expireSession, optionsAttempt])

  useEffect(() => {
    const id = ++requestId.current
    api.exercises(filters)
      .then((data) => {
        if (id === requestId.current) setList({ filters, attempt: listAttempt, result: data })
      })
      .catch((error) => {
        if (id !== requestId.current) return
        if (error.status === 401) expireSession()
        else {
          setList((previous) => ({
            filters,
            attempt: listAttempt,
            result: previous?.filters === filters ? previous.result : null,
            error: '문제를 불러오지 못했어요.',
          }))
        }
      })
    return () => { requestId.current += 1 }
  }, [filters, listAttempt, expireSession])

  const retryOptions = () => {
    setOptionsError('')
    setOptionsLoading(true)
    setOptionsAttempt((attempt) => attempt + 1)
  }

  const refreshList = () => {
    requestId.current += 1
    setListAttempt((attempt) => attempt + 1)
  }

  const changeFilters = (values) => {
    setActionStatus('')
    setFilters((previous) => ({ ...previous, ...firstPage, ...values }))
  }

  const movePage = (cursor) => {
    listHeading.current?.focus()
    changeFilters(cursor)
  }

  const changePreference = async (exercise, key, trigger) => {
    const preference = preferences[key]
    const enabled = !exercise[key]
    const id = exercise.id
    setPendingPreferences((previous) => ({ ...previous, [id]: key }))
    try {
      await (enabled ? preference.add(id) : preference.remove(id))
    } catch (error) {
      if (error.status === 401) expireSession()
      throw error
    } finally {
      setPendingPreferences(({ [id]: completed, ...previous }) => previous)
    }

    if (preference.hides(filters.view, enabled) && document.activeElement === trigger) {
      listHeading.current?.focus({ preventScroll: true })
    }
    setList((previous) => {
      if (!previous?.result) return previous

      const content = preference.hides(previous.filters.view, enabled)
        ? previous.result.content.filter((item) => item.id !== id)
        : previous.result.content.map((item) => item.id === id ? { ...item, [key]: enabled } : item)
      return { ...previous, result: { ...previous.result, content } }
    })
    setActionStatus(enabled ? preference.added : preference.removed)
    refreshList()
  }

  return (
    <Layout>
      <main className="dashboard-page" id="main-content" tabIndex={-1}>
        <header className="hero">
          <div>
            <h1>CS 문제 풀기</h1>
            <p className="hero-copy">분야와 난이도를 골라 학습하고, 다시 볼 문제를 저장하세요.</p>
          </div>
          {showGenerationLink && <a className="button button--secondary" href="#generate">문제 생성 요청</a>}
        </header>

        <section className="exercise-section" aria-labelledby="exercise-heading">
          <div className="section-heading">
            <h2 id="exercise-heading" ref={listHeading} tabIndex={-1}>문제 목록</h2>
            <button
              className="text-button"
              type="button"
              aria-disabled={loading}
              onClick={() => { if (!loading) refreshList() }}
            >
              목록 새로고침
            </button>
          </div>
          <div className="tabs" role="group" aria-label="문제 보기">
            {views.map((view) => (
              <button
                type="button"
                key={view.value}
                aria-pressed={filters.view === view.value}
                className={filters.view === view.value ? 'is-active' : ''}
                onClick={() => { if (filters.view !== view.value) changeFilters({ view: view.value }) }}
              >
                {view.label}
              </button>
            ))}
          </div>
          <div className="filters">
            <label>
              분야
              <select value={filters.subject} disabled={optionsLoading || Boolean(optionsError)} onChange={(event) => changeFilters({ subject: event.target.value })}>
                <option value="">모든 분야</option>
                {options.subjects.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select>
            </label>
            <label>
              난이도
              <select value={filters.level} disabled={optionsLoading || Boolean(optionsError)} onChange={(event) => changeFilters({ level: event.target.value })}>
                <option value="">모든 난이도</option>
                {options.levels.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select>
            </label>
            {isFiltered && <button className="button button--secondary" type="button" onClick={() => {
              listHeading.current?.focus({ preventScroll: true })
              changeFilters({ subject: '', level: '' })
            }}>필터 초기화</button>}
          </div>
          {optionsError && (
            <div className="notice notice--error">
              <p role="alert">{optionsError}</p>
              <button className="button button--secondary" type="button" onClick={() => {
                listHeading.current?.focus({ preventScroll: true })
                retryOptions()
              }}>필터 옵션 다시 불러오기</button>
            </div>
          )}
          {actionStatus && <p className="notice notice--success" role="status">{actionStatus}</p>}
          <div className="exercise-results" aria-busy={loading}>
            {listError && (
              <div className="notice notice--error">
                <p role="alert">{listError}</p>
                <button className="button button--secondary" type="button" onClick={() => {
                  listHeading.current?.focus({ preventScroll: true })
                  refreshList()
                }}>문제 다시 불러오기</button>
              </div>
            )}
            {result?.content.length ? (
              <div className="exercise-grid">
                {result.content.map((exercise) => (
                  <ExerciseCard key={exercise.id} exercise={exercise} pending={pendingPreferences[exercise.id]} onChangePreference={changePreference} />
                ))}
              </div>
            ) : loading ? (
              <div className="empty-state">문제를 불러오는 중이에요.</div>
            ) : !listError && (
              <div className="empty-state">
                {!isFirstPage ? (
                  <>
                    <strong>이 페이지에 표시할 문제가 없어요.</strong>
                    <button className="button button--secondary" type="button" onClick={() => movePage(firstPage)}>첫 페이지로</button>
                  </>
                ) : isFiltered ? (
                  <>
                    <strong>조건에 맞는 문제가 없어요.</strong>
                    <span>분야나 난이도를 바꾸거나 필터를 초기화해 보세요.</span>
                  </>
                ) : filters.view === 'all' ? (
                  <>
                    <strong>풀 수 있는 문제가 없어요.</strong>
                    {showGenerationLink && <a className="button button--secondary" href="#generate">문제 생성 요청</a>}
                  </>
                ) : (
                  <>
                    <strong>{filters.view === 'selected' ? '저장한 문제가 없어요.' : '차단한 문제가 없어요.'}</strong>
                    <button className="button button--secondary" type="button" onClick={() => {
                      listHeading.current?.focus({ preventScroll: true })
                      changeFilters({ view: 'all' })
                    }}>전체 문제 보기</button>
                  </>
                )}
              </div>
            )}
            {result?.content.length > 0 && (
              <Pagination result={result} disabled={loading || Boolean(listError)} isFirstPage={isFirstPage} onChange={movePage} />
            )}
          </div>
        </section>

        <GenerationPanel options={options} optionsLoading={optionsLoading} optionsError={optionsError} onRetryOptions={retryOptions} />
      </main>
    </Layout>
  )
}
