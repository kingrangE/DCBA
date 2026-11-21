export class ApiError extends Error {
  constructor(message, status, retryAfter = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    if (retryAfter !== null) this.retryAfter = retryAfter
  }
}

export async function request(path, options = {}, readResponse = (response) => response.json()) {
  const headers = { ...options.headers }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'

  const method = (options.method || 'GET').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
    if (!csrfResponse.ok) throw new ApiError('요청 인증 정보를 가져오지 못했습니다.', csrfResponse.status)
    const csrf = await csrfResponse.json()
    headers[csrf.headerName] = csrf.token
  }

  const response = await fetch(path, {
    credentials: 'include',
    ...options,
    headers,
  })

  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    const retryAfter = response.status === 429 ? response.headers.get('Retry-After') : null
    throw new ApiError(body.message || '요청을 처리하지 못했습니다.', response.status, retryAfter)
  }

  if (response.status === 204) return null
  return readResponse(response)
}

export const api = {
  login: (credentials) => request('/api/auth/login', { method: 'POST', body: JSON.stringify(credentials) }),
  signup: (credentials) => request('/api/auth/signup', { method: 'POST', body: JSON.stringify(credentials) }),
  logout: () => request('/api/auth/logout', { method: 'POST' }),
  me: () => request('/api/auth/me'),
  automaticGenerationPause: () => request('/api/admin/automatic-generation/pause'),
  pauseAutomaticGeneration: () => request('/api/admin/automatic-generation/pause', { method: 'PUT' }),
  exercisePool: ({ before, after }) => {
    const params = new URLSearchParams()
    if (before) params.set('before', String(before))
    if (after) params.set('after', String(after))
    return request(`/api/admin/exercises?${params}`)
  },
  deleteExercise: (id) => request(`/api/admin/exercises/${id}`, { method: 'DELETE' }),
  reviewSample: ({ mode, subject, level }) => {
    const params = new URLSearchParams({ mode })
    if (subject) params.set('subject', subject)
    if (level) params.set('level', level)
    return request(`/api/exercises/review-sample?${params}`)
  },
  markReview: (id, enabled) => request(`/api/exercises/${id}/review-again`, {
    method: 'PUT', body: JSON.stringify({ enabled }),
  }),
  reviewFeedback: (id, answer) => request(`/api/exercises/${id}/review-feedback`, {
    method: 'POST', body: JSON.stringify({ answer }),
  }),
  updateSlack: (slackId) => request('/api/users/me/slack', {
    method: 'PATCH',
    body: JSON.stringify({ slackId }),
  }),
  options: () => request('/api/exercises/options', {}, async (response) => ({
    ...await response.json(),
    generationEnabled: response.headers.get('X-DCBA-Generation-Enabled') !== 'false',
  })),
  exercises: ({ view, subject, level, q, before, after }) => {
    const params = new URLSearchParams({ view })
    if (before) params.set('before', String(before))
    if (after) params.set('after', String(after))
    if (subject) params.set('subject', subject)
    if (level) params.set('level', level)
    if (q && (view === 'all' || view === 'selected')) params.set('q', q)
    return request(`/api/exercises?${params}`)
  },
  save: (id) => request(`/api/exercises/${id}/saved`, { method: 'POST' }),
  unsave: (id) => request(`/api/exercises/${id}/saved`, { method: 'DELETE' }),
  ban: (id) => request(`/api/exercises/${id}/banned`, { method: 'POST' }),
  unban: (id) => request(`/api/exercises/${id}/banned`, { method: 'DELETE' }),
  generate: (subject, level, learningIntent) => request('/api/exercises/generation-requests', {
    method: 'POST',
    body: JSON.stringify({ subject, level, learningIntent: learningIntent || undefined }),
  }),
  generationStatus: (id) => request(`/api/exercises/generation-requests/${encodeURIComponent(id)}`),
  recentGenerationRequests: () => request('/api/exercises/generation-requests/recent'),
  generationNotifications: (page) => request(`/api/exercises/generation-requests/notifications?page=${page}`),
  markGenerationRead: (id) => request(`/api/exercises/generation-requests/${encodeURIComponent(id)}/read`, { method: 'PUT' }),
  generatedExercise: (id) => request(`/api/exercises/generation-requests/${encodeURIComponent(id)}/exercise`),
}
