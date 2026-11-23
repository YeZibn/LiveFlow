import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ApiRequestError } from '../api/requestJson'
import { authClient, type CurrentUser, type TokenResponse } from './authClient'

const lockName = 'liveflow:auth:write'
// 跨标签页共享的是会话标识或重登状态，凭证只留在内存或 HttpOnly Cookie 中。
const sessionMarker = 'liveflow:auth:session'
const reloginMarker = 'relogin'
const terminalErrors = new Set([
  'AUTH_REQUIRED', 'AUTH_ACCESS_INVALID', 'AUTH_SESSION_INACTIVE', 'AUTH_SESSION_REPLACED',
  'AUTH_REFRESH_INVALID', 'AUTH_REFRESH_REUSED',
])

/**
 * 在同源标签页间串行处理认证写操作。
 * @param action 持锁后才开始的操作，包含响应处理
 * @returns 操作结果
 * @throws ApiRequestError 浏览器缺少安全互斥能力
 */
async function withSessionLock<T>(action: () => Promise<T>): Promise<T> {
  if (!navigator.locks) {
    return Promise.reject(new ApiRequestError(0, 'AUTH_LOCK_UNAVAILABLE',
      '当前浏览器环境无法安全协调登录，请通过 HTTPS 或本地回环地址打开受支持的浏览器。'))
  }
  return navigator.locks.request(lockName, action)
}

/**
 * 构造会话切换错误，供等待中的旧请求退出。
 * @returns 不暴露凭证内容的认证错误
 */
function sessionChanged() {
  return new ApiRequestError(409, 'AUTH_SESSION_CHANGED', '登录会话已变化，请重新读取登录状态。')
}

/**
 * 将未获确认的刷新表示为需要手动登录的错误。
 * @returns 用于停止自动刷新的认证错误
 */
function refreshUncertain() {
  return new ApiRequestError(0, 'AUTH_REFRESH_UNCERTAIN', '无法确认登录续期结果，请重新登录。')
}

/**
 * 维护当前标签页的内存凭证和登录状态。
 * @returns 认证界面状态、会话标识以及登录、退出和受保护请求入口
 */
export function useAuth() {
  const token = ref<TokenResponse>()
  const user = ref<CurrentUser>()
  const restoring = ref(true)
  const busy = ref(false)
  const error = ref('')
  const restoreFailed = ref(false)
  const loggedIn = computed(() => Boolean(token.value && user.value))
  const sessionId = computed(() => token.value?.sessionId)
  // 清理或替换本地会话时递增。已在途请求据此判断响应是否仍属于当前状态。
  let generation = 0
  let pendingRefresh: { sessionId: string; promise: Promise<TokenResponse> } | undefined

  /**
   * 清除本页凭证和账号摘要，同时使在途调用过期。
   * @param expected 只允许清除的会话 ID，省略时清除当前本地状态
   */
  function clearSession(expected?: string) {
    if (expected !== undefined && token.value?.sessionId !== expected) {
      return
    }
    generation++
    token.value = undefined
    user.value = undefined
  }

  /**
   * 响应其他同源标签页的登录、退出或续期不确定事件。
   * @param event 浏览器发送的存储变更事件
   */
  function handleMarkerChange(event: StorageEvent) {
    if (event.key !== sessionMarker) {
      return
    }
    // 存储事件可能排队，读取最新标记，避免旧通知误清新会话。
    const current = localStorage.getItem(sessionMarker)
    if (token.value && token.value.sessionId !== current) {
      clearSession(token.value.sessionId)
      error.value = current === reloginMarker ? refreshUncertain().message : sessionChanged().message
    }
  }

  /**
   * 在调用方持有跨页写锁时轮换浏览器 Cookie 对应的会话。
   * @param expected 要续期的原会话，页面首次恢复时可省略
   * @returns 新 Access Token 与服务端确认的会话信息
   * @throws ApiRequestError 会话切换、Cookie 无效或刷新结果无法确认
   */
  async function exchange(expected?: string): Promise<TokenResponse> {
    // 拿到锁后再读共享标记，防止排队期间发生的登录或退出改变操作目标。
    const marker = localStorage.getItem(sessionMarker)
    if (marker === reloginMarker) {
      throw refreshUncertain()
    }
    if (expected && marker !== expected) {
      throw sessionChanged()
    }

    await authClient.csrf()
    let next: TokenResponse
    try {
      next = await authClient.refresh()
    } catch (cause) {
      if (cause instanceof ApiRequestError && (cause.status === 0 || cause.status === 503)) {
        // 服务端可能已经消费旧 Cookie。通知其他标签页停止自动续期，等待重新登录。
        localStorage.setItem(sessionMarker, reloginMarker)
        throw refreshUncertain()
      }
      if (cause instanceof ApiRequestError && terminalErrors.has(cause.code)) {
        localStorage.removeItem(sessionMarker)
      }
      throw cause
    }
    localStorage.setItem(sessionMarker, next.sessionId)
    if (expected && next.sessionId !== expected) {
      throw sessionChanged()
    }
    return next
  }

  /**
   * 在本页合并原会话的续期请求。收到较晚的过期响应时优先复用已经更新的 Token。
   * @param previous 触发续期的原请求使用的凭证
   * @returns 原会话的新凭证
   * @throws ApiRequestError 续期失败或操作期间会话已变更
   */
  function refreshAccess(previous: TokenResponse): Promise<TokenResponse> {
    const current = token.value
    if (current?.sessionId !== previous.sessionId) {
      return Promise.reject(sessionChanged())
    }
    // 晚到的过期错误可以复用已换发的 Token，同时到达的请求共用一次续期。
    if (current.accessToken !== previous.accessToken) {
      return Promise.resolve(current)
    }
    if (pendingRefresh?.sessionId === previous.sessionId) {
      return pendingRefresh.promise
    }

    const version = generation
    const promise = withSessionLock(async () => {
      // 等待跨页锁时本页可能已退出，失效操作连 Refresh Cookie 都不应触碰。
      if (version !== generation) {
        throw sessionChanged()
      }
      const next = await exchange(previous.sessionId)
      // 网络往返期间本页会话也可能失效，检查通过后才写入内存。
      if (version !== generation) {
        throw sessionChanged()
      }
      token.value = next
      return next
    }).finally(() => {
      // 较早的续期结束时不能清除另一个会话已经登记的请求。
      if (pendingRefresh?.promise === promise) {
        pendingRefresh = undefined
      }
    })
    pendingRefresh = { sessionId: previous.sessionId, promise }
    return promise
  }

  /**
   * 发起一次受保护调用，仅在服务端拒绝过期 JWT 时续期并重发一次。
   * @param call 使用当前 Access Token 发请求的入口
   * @returns 属于同一会话的调用结果
   * @throws ApiRequestError 会话已变更、续期失败或受保护请求失败
   */
  async function withAccess<T>(call: (access: string) => Promise<T>): Promise<T> {
    const previous = token.value
    if (!previous) {
      throw new ApiRequestError(401, 'AUTH_REQUIRED', '请先登录。')
    }
    const version = generation
    try {
      let result: T
      try {
        result = await call(previous.accessToken)
      } catch (cause) {
        // 仅凭服务端明确的过期错误重发一次。超时等不确定结果交给调用方处理。
        if (!(cause instanceof ApiRequestError) || cause.code !== 'AUTH_ACCESS_EXPIRED') {
          throw cause
        }
        const next = await refreshAccess(previous)
        // 重发前同时检查本页会话与跨页标记，原请求只能沿用原登录身份。
        if (version !== generation || localStorage.getItem(sessionMarker) !== previous.sessionId) {
          throw sessionChanged()
        }
        result = await call(next.accessToken)
      }
      // 即使请求成功，旧会话的迟到数据也不能覆盖现在的界面。
      if (version !== generation || localStorage.getItem(sessionMarker) !== previous.sessionId) {
        throw sessionChanged()
      }
      return result
    } catch (cause) {
      if (version === generation && cause instanceof ApiRequestError
          && (terminalErrors.has(cause.code) || cause.code === 'AUTH_REFRESH_UNCERTAIN'
            || cause.code === 'AUTH_SESSION_CHANGED')) {
        clearSession(previous.sessionId)
        error.value = messageFor(cause)
      }
      throw cause
    }
  }

  /**
   * 初始化 CSRF，恢复浏览器会话并读取当前用户。
   * @returns 界面进入已登录、未登录或明确故障状态后完成
   */
  async function restore() {
    restoring.value = true
    restoreFailed.value = false
    error.value = ''
    try {
      if (!token.value) {
        await withSessionLock(async () => {
          clearSession()
          token.value = await exchange()
        })
      }
      // 同会话的账号摘要载入后，页面才进入已登录视图。
      user.value = await withAccess(authClient.me)
    } catch (cause) {
      if (cause instanceof ApiRequestError && cause.code === 'AUTH_REQUIRED') {
        return
      }
      error.value = messageFor(cause)
      restoreFailed.value = cause instanceof ApiRequestError
        && cause.code !== 'AUTH_REFRESH_UNCERTAIN' && (cause.status === 0 || cause.status === 503)
    } finally {
      restoring.value = false
    }
  }

  /**
   * 校验登录输入，由后端创建会话，并加载当前用户。
   * @param username 用户输入的账号
   * @param password 用户输入的原始密码
   * @returns 登录操作及界面状态更新后完成
   */
  async function login(username: string, password: string) {
    busy.value = true
    error.value = ''
    restoreFailed.value = false
    try {
      await withSessionLock(async () => {
        await authClient.csrf()
        const next = await authClient.login(username, password)
        clearSession()
        localStorage.setItem(sessionMarker, next.sessionId)
        token.value = next
        await authClient.csrf()
      })
      user.value = await withAccess(authClient.me)
    } catch (cause) {
      error.value = messageFor(cause)
    } finally {
      busy.value = false
    }
  }

  /**
   * 退出页面当时所见的会话，并为后续登录重新初始化 CSRF。
   * @returns 退出响应处理后完成
   */
  async function logout() {
    // 在等待锁之前固定页面所见的目标，保留用户发起退出时的操作意图。
    const target = token.value?.sessionId
    if (!target) {
      return
    }
    busy.value = true
    error.value = ''
    try {
      await withSessionLock(async () => {
        if (localStorage.getItem(sessionMarker) !== target) {
          throw sessionChanged()
        }
        await authClient.logout(target)
        // 服务端确认撤销后再通知其他标签页清理会话。
        localStorage.removeItem(sessionMarker)
        clearSession(target)
        await authClient.csrf()
      })
    } catch (cause) {
      if (cause instanceof ApiRequestError && (terminalErrors.has(cause.code)
          || cause.code === 'AUTH_SESSION_CHANGED')) {
        clearSession(target)
      }
      if (cause instanceof ApiRequestError && cause.code === 'AUTH_CSRF_INVALID') {
        try {
          await authClient.csrf()
        } catch {
          // 保留原退出错误，让用户决定是否重试。
        }
      }
      error.value = messageFor(cause)
    } finally {
      busy.value = false
    }
  }

  onMounted(() => {
    window.addEventListener('storage', handleMarkerChange)
    void restore()
  })
  onUnmounted(() => window.removeEventListener('storage', handleMarkerChange))

  return { user, loggedIn, restoring, busy, error, restoreFailed, sessionId, restore, login, logout, withAccess }
}

/**
 * 给认证界面选择可直接展示的错误说明。
 * @param cause 捕获的 API 错误或其他异常
 * @returns 已映射的 API 提示或通用说明
 */
function messageFor(cause: unknown): string {
  return cause instanceof ApiRequestError ? cause.message : '认证操作暂不可用，请稍后再试。'
}
