import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, request } from './client'

afterEach(() => vi.restoreAllMocks())

describe('request', () => {
  it('sends cookies and parses JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ id: 1 }),
    }))

    await expect(request('/api/auth/me')).resolves.toEqual({ id: 1 })
    expect(fetch).toHaveBeenCalledWith('/api/auth/me', expect.objectContaining({ credentials: 'include' }))
  })

  it('surfaces the API error message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ message: '로그인이 필요합니다.' }),
    }))

    await expect(request('/api/auth/me')).rejects.toEqual(
      expect.objectContaining({ name: 'ApiError', message: '로그인이 필요합니다.', status: 401 }),
    )
    expect(ApiError).toBeDefined()
  })
})

it('fetches a fresh CSRF token before each mutation', async () => {
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce({ ok: true, json: async () => ({ headerName: 'X-CSRF-TOKEN', token: 'fresh-token' }) })
    .mockResolvedValueOnce({ ok: true, status: 204 }))
  await request('/api/auth/logout', { method: 'POST' })
  expect(fetch).toHaveBeenNthCalledWith(1, '/api/auth/csrf', { credentials: 'include' })
  expect(fetch).toHaveBeenNthCalledWith(2, '/api/auth/logout', expect.objectContaining({ headers: { 'X-CSRF-TOKEN': 'fresh-token' } }))
})

it.each([
  [{ 'X-DCBA-Generation-Enabled': 'false' }, false],
  [{}, true],
])('uses the site generation setting in the exercise options', async (headers, generationEnabled) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
    JSON.stringify({ subjects: [], levels: [] }), { headers },
  )))

  await expect(api.options()).resolves.toEqual({ subjects: [], levels: [], generationEnabled })
})

it('sends review mutations with CSRF and surfaces Retry-After for feedback limits', async () => {
  const csrf = () => new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'review-token' }))
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(new Response(JSON.stringify({ message: '한도에 도달했어요.' }), {
      status: 429, headers: { 'Retry-After': '120' },
    })))
  await expect(api.markReview(42, true)).resolves.toBeNull()
  await expect(api.reviewFeedback(42, '답변')).rejects.toMatchObject({ status: 429, retryAfter: '120' })
  expect(fetch).toHaveBeenNthCalledWith(2, '/api/exercises/42/review-again', expect.objectContaining({
    method: 'PUT', body: '{"enabled":true}', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'review-token' }),
  }))
  expect(fetch).toHaveBeenNthCalledWith(4, '/api/exercises/42/review-feedback', expect.objectContaining({
    method: 'POST', body: '{"answer":"답변"}', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'review-token' }),
  }))
})
