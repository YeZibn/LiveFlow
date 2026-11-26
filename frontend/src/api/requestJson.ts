/** 同源 API 的公开失败，status 为 0 表示传输结果无法确认。 */
export class ApiRequestError extends Error {
  /**
   * 构造供界面按状态码、业务错误码和字段提示处理的错误。
   * @param status HTTP 状态，0 表示无可确认的 HTTP 结果
   * @param code 服务端错误码或客户端映射的错误码
   * @param message 可直接展示的简短说明
   * @param fieldErrors 可选的表单字段错误
   * @param retryAfterSeconds 限流时建议等待的整数秒数
   * @param details 由具体接口负责校验的错误附加字段
   */
  constructor(readonly status: number, readonly code: string, message: string,
    readonly fieldErrors?: Record<string, string>, readonly retryAfterSeconds?: number, readonly details?: unknown) {
    super(message)
  }
}

export type CsrfScope = 'pre-auth' | 'session'

/**
 * @param scope 匿名写请求或认证态写请求使用的凭证用途
 * @returns 浏览器可读的对应 CSRF 值，凭证缺失时为空
 */
export function readCsrfCookie(scope: CsrfScope): string | undefined {
  const prefix = window.location.protocol === 'https:' ? '__Host-' : ''
  const name = prefix + (scope === 'pre-auth' ? 'lf_csrf_pre' : 'lf_csrf') + '='
  return document.cookie.split(';').map(cookie => cookie.trim()).find(cookie => cookie.startsWith(name))?.slice(name.length)
}

/**
 * 调用同源 JSON 接口。写请求附带 CSRF，受保护的请求由调用方提供 Bearer。
 * @param path 以 /api/ 开头的接口路径
 * @param options HTTP 方法、正文、可选的 Access Token 和写请求的 CSRF 用途
 * @returns 成功响应的数据，204 时返回 undefined
 * @throws ApiRequestError HTTP 失败、响应无效或网络错误
 */
export async function requestJson<T>(path: string, options: {
  method?: 'GET' | 'POST' | 'PUT'
  body?: object
  access?: string
  csrfScope?: CsrfScope
} = {}): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  if (options.body) {
    headers.set('Content-Type', 'application/json')
  }
  if (options.method === 'POST' || options.method === 'PUT') {
    // Refresh Cookie 由浏览器自动发送，脚本只读取可访问的 CSRF Cookie。
    const token = readCsrfCookie(options.csrfScope ?? 'session')
    if (token) headers.set('X-XSRF-TOKEN', token)
  }
  if (options.access) {
    headers.set('Authorization', `Bearer ${options.access}`)
  }

  try {
    const response = await fetch(path, {
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
      // HTTP 错误即使没有 JSON 正文，也保留状态码供调用方区分过期、限流和结果不明。
      const retryAfter = response.headers.get('Retry-After')
      const seconds = retryAfter && /^\d+$/.test(retryAfter) ? Number(retryAfter) : undefined
      throw new ApiRequestError(response.status, body?.code ?? 'HTTP_ERROR',
        body?.message ?? '服务暂不可用，请稍后再试。', body?.fieldErrors, seconds, body)
    }
    if (body == null) {
      throw new ApiRequestError(0, 'RESPONSE_INVALID', '服务响应无法确认。')
    }
    return body as T
  } catch (error) {
    if (error instanceof ApiRequestError) {
      throw error
    }
    throw new ApiRequestError(0, 'NETWORK_ERROR', '无法确认请求结果，请检查网络。')
  }
}
