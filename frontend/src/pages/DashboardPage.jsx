import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import ExerciseCard from '../components/ExerciseCard'
import GenerationPanel from '../components/GenerationPanel'
import Layout from '../components/Layout'
import Pagination from '../components/Pagination'

const emptyOptions = { subjects: [], levels: [] }
const tabs = [
  { value: 'all', label: '전체 문제' },
  { value: 'selected', label: '저장한 문제' },
  { value: 'banned', label: '차단한 문제' },
]

export default function DashboardPage() {
  const navigate = useNavigate()
  const [options, setOptions] = useState(emptyOptions)
  const [filters, setFilters] = useState({ view: 'all', subject: '', level: '', page: 0 })
  const [result, setResult] = useState({ content: [], page: 0, totalPages: 0, totalElements: 0 })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busyId, setBusyId] = useState(null)

  const loadExercises = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const data = await api.exercises(filters)
      setResult(data)
    } catch (requestError) {
      if (requestError.status === 401) navigate('/login', { replace: true })
      else setError(requestError.message)
    } finally {
      setLoading(false)
    }
  }, [filters, navigate])

  useEffect(() => {
    api.options().then(setOptions).catch((requestError) => setError(requestError.message))
  }, [])

  useEffect(() => { loadExercises() }, [loadExercises])

  const updateFilter = (key, value) => setFilters((current) => ({ ...current, [key]: value, page: 0 }))

  const toggleSave = async (exercise) => {
    setBusyId(exercise.id)
    try {
      await (exercise.saved ? api.unsave(exercise.id) : api.save(exercise.id))
      await loadExercises()
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setBusyId(null)
    }
  }

  const toggleBan = async (exercise) => {
    setBusyId(exercise.id)
    try {
      await (exercise.banned ? api.unban(exercise.id) : api.ban(exercise.id))
      await loadExercises()
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setBusyId(null)
    }
  }

  return (
    <Layout>
      <main>
        <section className="hero">
          <div>
            <p className="eyebrow">TODAY'S PRACTICE</p>
            <h1>기본기를 채우는<br />오늘의 CS 문제.</h1>
          </div>
          <p className="hero-copy">분야와 난이도를 골라 학습하고, 다시 볼 문제는 저장하세요. 익숙한 문제는 차단해 새로운 문제에 집중할 수 있습니다.</p>
        </section>

        <GenerationPanel options={options} />

        <section className="exercise-section">
          <div className="section-heading">
            <div>
              <p className="eyebrow">QUESTION LIBRARY</p>
              <h2>문제 보관함 <span>{result.totalElements}</span></h2>
            </div>
            <div className="filters">
              {filters.view === 'all' && <>
                <select aria-label="분야 필터" value={filters.subject} onChange={(e) => updateFilter('subject', e.target.value)}>
                  <option value="">모든 분야</option>
                  {options.subjects.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                </select>
                <select aria-label="난이도 필터" value={filters.level} onChange={(e) => updateFilter('level', e.target.value)}>
                  <option value="">모든 난이도</option>
                  {options.levels.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                </select>
              </>}
            </div>
          </div>
          <div className="tabs" role="tablist">
            {tabs.map((tab) => <button key={tab.value} role="tab" aria-selected={filters.view === tab.value} className={filters.view === tab.value ? 'is-active' : ''} onClick={() => updateFilter('view', tab.value)}>{tab.label}</button>)}
          </div>
          {error && <p className="notice notice--error" role="alert">{error}</p>}
          {loading ? (
            <div className="empty-state">문제를 불러오는 중...</div>
          ) : result.content.length ? (
            <div className="exercise-grid">
              {result.content.map((exercise) => <ExerciseCard key={exercise.id} exercise={exercise} onSave={toggleSave} onBan={toggleBan} busy={busyId === exercise.id} />)}
            </div>
          ) : (
            <div className="empty-state"><strong>표시할 문제가 없습니다.</strong><span>필터를 바꾸거나 새 문제 생성을 요청해 보세요.</span></div>
          )}
          <Pagination page={result.page} totalPages={result.totalPages} onChange={(page) => setFilters((current) => ({ ...current, page }))} />
        </section>
      </main>
    </Layout>
  )
}
