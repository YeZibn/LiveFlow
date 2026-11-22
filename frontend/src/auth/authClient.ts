const base = '/api/v1/auth'

export interface TokenResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
  sessionId: string
  sessionExpiresAt: string
}

export interface CurrentUser {
  userId: string
  username: string
}

/** 认证接口的公开失败，status 为 0 表示传输结果无法确认。 */
export class AuthRequestError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message)
  }
}

/**
 * 调用同源认证接口。写请求附带 CSRF，受保护的请求由调用方提供 Bearer。
 * @param path 认证模块内的接口路径
 * @param options HTTP 方法、正文和可选的 Access Token
 * @returns 成功响应的数据，204 时返回 undefined
 * @throws AuthRequestError HTTP 失败、响应无效或网络错误
 */
async function request<T>(path: string, options: {
  method?: 'GET' | 'POST'
  body?: object
  access?: string
} = {}): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  if (options.body) {
    headers.set('Content-Type', 'application/json')
  }
  if (options.method === 'POST') {
    // Refresh Cookie 由浏览器自动发送，脚本只读取可访问的 CSRF Cookie。
    const cookie = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='))
    if (cookie) {
      headers.set('X-XSRF-TOKEN', decodeURIComponent(cookie.slice('XSRF-TOKEN='.length)))
    }
  }
  if (options.access) {
    headers.set('Authorization', `Bearer ${options.access}`)
  }

  try {
    const response = await fetch(base + path, {
      method: options.method,
      headers,
      body: options.body && JSON.stringify(options.body),
      credentials: 'same-origin',
      cache: 'no-store',
      signal: AbortSignal.timeout(8000),
    })
    // CSRF 初始化和退出都是空响应，直接返回会避免把成功当作 JSON 解析失败。
    if (response.status === 204) {
      return undefined as T
    }
    const body = await response.json().catch(() => undefined)
    if (!response.ok) {
      throw new AuthRequestError(response.status, body?.code ?? 'AUTH_SERVICE_UNAVAILABLE',
        body?.message ?? '认证服务暂不可用，请稍后再试。')
    }
    if (body == null) {
      throw new AuthRequestError(0, 'AUTH_RESPONSE_INVALID', '认证响应无法确认。')
    }
    return body as T
  } catch (error) {
    if (error instanceof AuthRequestError) {
      throw error
    }
    throw new AuthRequestError(0, 'AUTH_NETWORK_ERROR', '无法确认认证请求结果，请检查网络。')
  }
}

export const authClient = {
  csrf: () => request<void>('/csrf'),
  login: (username: string, password: string) =>
    request<TokenResponse>('/login', { method: 'POST', body: { username, password } }),
  refresh: () => request<TokenResponse>('/refresh', { method: 'POST' }),
  logout: (sessionId: string) => request<void>('/logout', { method: 'POST', body: { sessionId } }),
  me: (access: string) => request<CurrentUser>('/me', { access }),
}
