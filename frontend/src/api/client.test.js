import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from './client'

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
