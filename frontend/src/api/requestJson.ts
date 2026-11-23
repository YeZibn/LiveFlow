/** 同源 API 的公开失败，status 为 0 表示传输结果无法确认。 */
export class ApiRequestError extends Error {
  constructor(readonly status: number, readonly code: string, message: string,
    readonly fieldErrors?: Record<string, string>) {
    super(message)
  }
}

/**
 * 调用同源 JSON 接口。写请求附带 CSRF，受保护的请求由调用方提供 Bearer。
 * @param path 以 /api/ 开头的接口路径
 * @param options HTTP 方法、正文和可选的 Access Token
 * @returns 成功响应的数据，204 时返回 undefined
 * @throws ApiRequestError HTTP 失败、响应无效或网络错误
 */
export async function requestJson<T>(path: string, options: {
  method?: 'GET' | 'POST' | 'PUT'
  body?: object
  access?: string
} = {}): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  if (options.body) {
    headers.set('Content-Type', 'application/json')
  }
  if (options.method === 'POST' || options.method === 'PUT') {
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
      throw new ApiRequestError(response.status, body?.code ?? 'HTTP_ERROR',
        body?.message ?? '服务暂不可用，请稍后再试。', body?.fieldErrors)
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
