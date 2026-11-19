import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import ExerciseCard from '../components/ExerciseCard'
import GenerationPanel from '../components/GenerationPanel'
import Layout from '../components/Layout'
import Pagination from '../components/Pagination'
import ReviewSession from '../components/ReviewSession'
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
  const composingSearch = useRef(false)
  const requestId = useRef(0)
  const reviewRequestId = useRef(0)
  const [options, setOptions] = useState(emptyOptions)
  const [optionsError, setOptionsError] = useState('')
  const [optionsLoading, setOptionsLoading] = useState(true)
  const [optionsAttempt, setOptionsAttempt] = useState(0)
  const [filters, setFilters] = useState({ view: 'all', subject: '', level: '', q: '', ...firstPage })
  const [filtersExpanded, setFiltersExpanded] = useState(false)
  const [searchInput, setSearchInput] = useState('')
  const [searchTooLong, setSearchTooLong] = useState(false)
  const [list, setList] = useState(null)
  const [listAttempt, setListAttempt] = useState(0)
  const [actionStatus, setActionStatus] = useState('')
  const [pendingPreferences, setPendingPreferences] = useState({})
  const [reviewItems, setReviewItems] = useState(null)
  const [startingReview, setStartingReview] = useState(null)
  const [emptyReview, setEmptyReview] = useState(null)
  const [reviewError, setReviewError] = useState('')
  const current = list?.filters === filters ? list : null
  const result = current?.result
  const loading = !current || current.attempt !== listAttempt
  const listError = loading ? '' : current.error
  const isFiltered = Boolean(filters.subject || filters.level)
  const filterSummary = [
    options.subjects.find(({ value }) => value === filters.subject)?.label,
    options.levels.find(({ value }) => value === filters.level)?.label,
  ].filter(Boolean).join(' · ')
  const canSearch = filters.view !== 'banned'
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
    requestId.current += 1
    reviewRequestId.current += 1
    setStartingReview(null)
    setEmptyReview(null)
    setReviewError('')
    setActionStatus('')
    setFilters((previous) => ({ ...previous, ...firstPage, ...values }))
  }

  const submitSearch = (event) => {
    event.preventDefault()
    if (composingSearch.current) return
    const q = searchInput.trim()
    if (q.length > 100) {
      setSearchTooLong(true)
      event.currentTarget.elements.namedItem('q').focus()
      return
    }
    setSearchTooLong(false)
    setSearchInput(q)
    changeFilters({ q })
  }

  const clearSearch = () => {
    listHeading.current?.focus({ preventScroll: true })
    setSearchTooLong(false)
    setSearchInput('')
    changeFilters({ q: '' })
  }

  const startReview = async (mode) => {
    if (startingReview) return
    const id = ++reviewRequestId.current
    setStartingReview(mode)
    setEmptyReview(null)
    setReviewError('')
    try {
      const { items } = await api.reviewSample({ mode, subject: filters.subject, level: filters.level })
      if (id !== reviewRequestId.current) return
      if (items.length) setReviewItems(items)
      else setEmptyReview(mode)
    } catch (error) {
      if (id !== reviewRequestId.current) return
      if (error.status === 401) expireSession()
      else setReviewError('연습 문제를 불러오지 못했어요. 다시 시도해 주세요.')
    } finally {
      if (id === reviewRequestId.current) setStartingReview(null)
    }
  }

  const returnFromReview = () => {
    setReviewItems(null)
    requestAnimationFrame(() => listHeading.current?.focus())
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

  if (reviewItems) {
    return <Layout><ReviewSession initialItems={reviewItems} onReturn={returnFromReview} /></Layout>
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
          {canSearch && (
            <form className="keyword-search" role="search" aria-label="문제 검색" onSubmit={submitSearch}>
              <label htmlFor="exercise-keyword">문제 키워드</label>
              <div className="keyword-search-actions">
                <input
                  id="exercise-keyword"
                  name="q"
                  type="search"
                  value={searchInput}
                  aria-invalid={searchTooLong || undefined}
                  aria-describedby={searchTooLong ? 'exercise-keyword-error' : undefined}
                  placeholder="예: 교착 상태"
                  onChange={(event) => setSearchInput(event.target.value)}
                  onCompositionStart={() => { composingSearch.current = true }}
                  onCompositionEnd={() => { composingSearch.current = false }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && (composingSearch.current || event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229)) {
                      event.preventDefault()
                    }
                  }}
                />
                <button className="button button--primary" type="submit">검색</button>
                {(searchInput || filters.q) && <button className="button button--secondary" type="button" onClick={clearSearch}>검색어 지우기</button>}
              </div>
              {searchTooLong && (
                <p className="form-status form-status--error" id="exercise-keyword-error" role="alert">
                  양끝 공백을 제외한 검색어를 100자 이하로 줄여 주세요.
                </p>
              )}
              {filters.q && <p className="field-help" role="status">적용 검색어 · <strong>{filters.q}</strong></p>}
            </form>
          )}
          <button
            className="filter-toggle"
            type="button"
            aria-controls="exercise-filters"
            aria-expanded={filtersExpanded}
            onClick={() => setFiltersExpanded((expanded) => !expanded)}
          >
            <span>필터{filterSummary && <small>{filterSummary}</small>}</span>
            <span className="filter-chevron" aria-hidden="true" />
          </button>
          <div className="filters" id="exercise-filters">
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
          {filters.view === 'selected' && (
            <div className="review-start">
              <div className="card-actions">
                <button className="button button--primary" type="button" disabled={Boolean(startingReview)}
                  aria-describedby={filters.q ? 'review-filter-scope' : undefined}
                  onClick={() => startReview('random')}>랜덤으로 3문제 풀기</button>
                <button className="button button--secondary" type="button" disabled={Boolean(startingReview)}
                  aria-describedby={filters.q ? 'review-filter-scope' : undefined}
                  onClick={() => startReview('marked')}>다시 풀 문제 연습</button>
              </div>
              {filters.q && <p id="review-filter-scope" className="field-help">복습에는 분야와 난이도만 적용돼요.</p>}
              <p className="field-help">새로고침하거나 페이지를 떠나면 이번 연습과 답변이 사라질 수 있어요.</p>
              {startingReview && <p role="status">연습 문제를 고르는 중이에요.</p>}
              {reviewError && <p className="notice notice--error" role="alert">{reviewError}</p>}
              {emptyReview && (
                <div className="empty-state">
                  <p role="status">{emptyReview === 'marked' ? '현재 조건에서 다시 풀기로 표시한 문제가 없어요. 위의 랜덤 연습을 시작할 수 있어요.'
                    : '현재 조건에서 연습할 수 있는 저장 문제가 없어요.'}</p>
                  <div className="card-actions">
                    {isFiltered && <button className="button button--secondary" type="button" onClick={() => {
                      listHeading.current?.focus()
                      changeFilters({ subject: '', level: '' })
                    }}>필터 초기화</button>}
                    <button className="button button--secondary" type="button" onClick={() => {
                      listHeading.current?.focus()
                      changeFilters({ view: 'all' })
                    }}>전체 문제 보기</button>
                  </div>
                </div>
              )}
            </div>
          )}
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
          <p role="status" className="exercise-search-status">
            {canSearch && filters.q && !loading && !listError && result?.content.length > 0 ? '검색 결과를 불러왔어요.' : ''}
          </p>
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
              <div className="empty-state" role="status">문제를 불러오는 중이에요.</div>
            ) : !listError && (
              <div className="empty-state">
                {!isFirstPage ? (
                  <>
                    <strong>이 페이지에 표시할 문제가 없어요.</strong>
                    <button className="button button--secondary" type="button" onClick={() => movePage(firstPage)}>첫 페이지로</button>
                  </>
                ) : canSearch && filters.q ? (
                  <>
                    <strong role="status">{filters.view === 'selected' ? '저장한' : '전체'} 문제에서 ‘{filters.q}’에 해당하는 결과가 없어요.</strong>
                    {filters.view === 'selected' ? (
                      <button className="button button--secondary" type="button" onClick={() => {
                        listHeading.current?.focus({ preventScroll: true })
                        changeFilters({ view: 'all' })
                      }}>전체 문제에서 검색</button>
                    ) : <span>검색어를 바꾸거나 지우고, 분야나 난이도를 조정해 보세요.</span>}
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
