export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export async function request(path, options = {}) {
  const headers = { ...options.headers }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'

  const response = await fetch(path, {
    credentials: 'include',
    ...options,
    headers,
  })

  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new ApiError(body.message || '요청을 처리하지 못했습니다.', response.status)
  }

  if (response.status === 204) return null
  return response.json()
}

export const api = {
  login: (credentials) => request('/api/auth/login', { method: 'POST', body: JSON.stringify(credentials) }),
  signup: (credentials) => request('/api/auth/signup', { method: 'POST', body: JSON.stringify(credentials) }),
  logout: () => request('/api/auth/logout', { method: 'POST' }),
  me: () => request('/api/auth/me'),
  updateSlack: (slackId) => request('/api/users/me/slack', {
    method: 'PATCH',
    body: JSON.stringify({ slackId }),
  }),
  options: () => request('/api/exercises/options'),
  exercises: ({ view, subject, level, page, size = 9 }) => {
    const params = new URLSearchParams({ view, page: String(page), size: String(size) })
    if (subject) params.set('subject', subject)
    if (level) params.set('level', level)
    return request(`/api/exercises?${params}`)
  },
  save: (id) => request(`/api/exercises/${id}/saved`, { method: 'POST' }),
  unsave: (id) => request(`/api/exercises/${id}/saved`, { method: 'DELETE' }),
  ban: (id) => request(`/api/exercises/${id}/banned`, { method: 'POST' }),
  unban: (id) => request(`/api/exercises/${id}/banned`, { method: 'DELETE' }),
  generate: (subject, level) => request('/api/exercises/generation-requests', {
    method: 'POST',
    body: JSON.stringify({ subject, level }),
  }),
}
