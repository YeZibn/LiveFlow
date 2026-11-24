import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiRequestError } from '../api/requestJson'
import { authClient } from '../auth/authClient'
import { decodeRoomFrame, openRoomSocket, requestMessageReceipt, requestRoomHistory, requestRoomTicket, sendRoomMessage,
  type MessageSubmission, type QueuedSubmission, type RoomMessage } from './roomMessagesClient'

type WithAccess = <T>(call: (access: string) => Promise<T>) => Promise<T>
const RECONNECT_DELAYS = [500, 1200, 3000]
const HISTORY_LIMIT = 100
const BUFFER_LIMIT = 300
const RETRY_MILLIS = 600_000
const POLL_MILLIS = 10_000

interface PendingSubmission {
  body: MessageSubmission
  retryUntil: number
  queued?: QueuedSubmission
  wasUncertain?: boolean
}

/**
 * @param roomId 已加载房间的 UUID
 * @param withAccess 当前会话的受保护请求入口
 * @returns 面板连接、历史、草稿与发送回执状态
 */
export function useRoomMessages(roomId: string, withAccess: WithAccess) {
  const state = ref<'connecting' | 'ready' | 'reconnecting' | 'offline'>('connecting')
  const connectionError = ref('')
  const messages = ref<RoomMessage[]>([])
  const content = ref('')
  const submitting = ref(false)
  const feedback = ref('')
  const cooldownSeconds = ref(0)
  const failed = ref(false)
  const syncFeedback = ref('')
  const syncing = ref(false)
  const pendingAction = ref<'retry' | 'receipt' | 'none'>('none')
  const pending = ref<PendingSubmission>()
  const checkingReceipt = ref(false)
  const buffered = new Map<bigint, RoomMessage>()
  let continuous: bigint | undefined
  let wanted = 0n
  let pinned: bigint | undefined
  let forceHistory = false
  let historyInFlight = false
  let historyRevision = 0
  let historyDelay = 250
  let historyTimer: number | undefined
  let receiptTimer: number | undefined
  let receiptExpiry: number | undefined
  let cooldownTimer: number | undefined
  let socket: WebSocket | undefined
  let reconnectTimer: number | undefined
  let readyTimer: number | undefined
  let retries = 0
  let generation = 0
  let active = true

  const canSend = computed(() => state.value === 'ready' && !submitting.value
    && cooldownSeconds.value === 0 && pending.value === undefined && content.value.trim().length > 0)

  onMounted(() => void connect())
  onBeforeUnmount(() => {
    // 父组件以房间和会话为 key。所有未完成的旧回调在卸载后失效。
    active = false
    generation++
    window.clearTimeout(readyTimer)
    window.clearTimeout(reconnectTimer)
    window.clearTimeout(historyTimer)
    historyTimer = undefined
    window.clearTimeout(cooldownTimer)
    clearPending()
    socket?.close()
  })

  /**
   * @param attempt 发起回调的连接代次
   * @param connection 可选的具体连接
   * @returns 活动页面和当前连接仍匹配时为 true
   */
  function current(attempt: number, connection?: WebSocket) {
    return active && attempt === generation && (!connection || socket === connection)
  }

  /** @param attempt 失败的连接代次 */
  function scheduleReconnect(attempt: number) {
    if (!current(attempt)) return
    const delay = RECONNECT_DELAYS[retries]
    if (delay === undefined) {
      state.value = 'offline'
      return
    }
    retries++
    state.value = 'reconnecting'
    reconnectTimer = window.setTimeout(() => {
      if (current(attempt)) void connect()
    }, delay)
  }

  /** @returns 申请票据，注册当前连接的生命周期及消息回调 */
  async function connect() {
    const attempt = ++generation
    historyRevision++
    window.clearTimeout(historyTimer)
    historyTimer = undefined
    window.clearTimeout(readyTimer)
    window.clearTimeout(reconnectTimer)
    pinned = undefined
    forceHistory = true
    socket?.close()
    socket = undefined
    connectionError.value = ''
    state.value = retries === 0 ? 'connecting' : 'reconnecting'
    try {
      const issued = await withAccess(access => requestRoomTicket(access, roomId))
      if (!current(attempt)) return
      const connection = openRoomSocket()
      socket = connection
      readyTimer = window.setTimeout(() => {
        if (current(attempt, connection)) connection.close()
      }, 8000)
      connection.onopen = () => {
        if (!current(attempt, connection)) return
        try {
          connection.send(JSON.stringify({ type: 'auth', ticket: issued.ticket }))
        } catch {
          connectionError.value = '连接未能提交票据，请重试。'
          connection.close()
        }
      }
      connection.onmessage = event => {
        if (!current(attempt, connection)) return
        const frame = decodeRoomFrame(event.data)
        if (!frame || ('roomId' in frame && frame.roomId !== roomId)) {
          connectionError.value = '连接响应无效，请重试。'
          connection.close()
          return
        }
        if (frame.type === 'ready') {
          window.clearTimeout(readyTimer)
          retries = 0
          state.value = 'ready'
          scheduleHistory(0)
        } else if (frame.type === 'error') {
          connectionError.value = frame.message
          connection.close()
        } else if (state.value === 'ready') {
          if (frame.type === 'message') {
            buffer(frame)
            drain()
          } else if (frame.type === 'history_progress') {
            wanted = max(wanted, BigInt(frame.storedThrough))
          } else if (frame.type === 'resync_required') {
            forceHistory = true
          }
          if (needsHistory()) scheduleHistory(0)
        }
      }
      connection.onclose = () => {
        if (!current(attempt, connection)) return
        socket = undefined
        historyRevision++
        window.clearTimeout(historyTimer)
        historyTimer = undefined
        window.clearTimeout(readyTimer)
        scheduleReconnect(attempt)
      }
    } catch (cause) {
      if (!current(attempt)) return
      if (cause instanceof ApiRequestError) {
        connectionError.value = cause.message
        if (cause.code === 'AUTH_CSRF_INVALID') await authClient.csrf().catch(() => undefined)
        if (!current(attempt)) return
        if ([400, 401, 404].includes(cause.status)) {
          state.value = 'offline'
          return
        }
      } else {
        connectionError.value = '无法建立弹幕连接，请重试。'
      }
      scheduleReconnect(attempt)
    }
  }

  /** 清除自动重试次数并申请新票据，保留本房间连续游标。 */
  function reconnect() {
    retries = 0
    void connect()
  }

  /**
   * @param message 待合并消息
   * @returns 本轮仍可继续合并时为 true
   */
  function buffer(message: RoomMessage): boolean {
    const number = BigInt(message.roomSeq)
    const known = buffered.get(number) ?? (continuous !== undefined && number <= continuous
      ? messages.value.find(item => item.roomSeq === message.roomSeq) : undefined)
    if (known && known.messageId !== message.messageId) {
      resetHistory('消息顺序需要重新同步，正在读取最近历史。')
      return false
    }
    if (known || (continuous !== undefined && number <= continuous)) return true
    buffered.set(number, message)
    if (buffered.size > BUFFER_LIMIT) {
      resetHistory('待同步弹幕较多，正在重新读取最近历史。')
      return false
    }
    return true
  }

  /** 只展示紧邻当前游标且无缺口的一段，保留最新 100 条。 */
  function drain() {
    if (continuous === undefined) return
    const additions: RoomMessage[] = []
    let next = continuous + 1n
    while (buffered.has(next)) {
      additions.push(buffered.get(next)!)
      buffered.delete(next)
      continuous = next
      next++
    }
    if (additions.length > 0) messages.value = [...messages.value, ...additions].slice(-HISTORY_LIMIT)
  }

  /** @param reason 清空旧合并状态并重读最近窗口的原因 */
  function resetHistory(reason: string) {
    syncFeedback.value = reason
    historyRevision++
    buffered.clear()
    continuous = undefined
    wanted = 0n
    pinned = undefined
    forceHistory = true
    scheduleHistory(0)
  }

  /** @returns 从实时缺口和已落库水位得到的补拉目标 */
  function catchupTarget() {
    let nearest: bigint | undefined
    for (const number of buffered.keys()) if (nearest === undefined || number < nearest) nearest = number
    return nearest === undefined ? wanted : max(wanted, nearest - 1n)
  }

  function needsHistory() {
    return continuous === undefined || forceHistory || catchupTarget() > continuous
  }

  /** @param delay 到下一次历史查询的毫秒数 */
  function scheduleHistory(delay: number) {
    if (!active || state.value !== 'ready' || historyInFlight || historyTimer !== undefined) return
    historyTimer = window.setTimeout(() => {
      historyTimer = undefined
      void synchronize()
    }, delay)
  }

  /** 最近窗口建立基线。跨页固定上界，实时帧仍可在请求期间进入缓冲。 */
  async function synchronize() {
    if (historyInFlight || state.value !== 'ready' || !needsHistory()) return
    historyInFlight = true
    syncing.value = true
    const revision = historyRevision
    const after = continuous
    const target = catchupTarget()
    const through = after === undefined ? undefined : (pinned ?? (target > after ? target : undefined))
    forceHistory = false
    let delay = 0
    try {
      const page = await withAccess(access => requestRoomHistory(access, roomId,
        after === undefined ? undefined : { after, through }))
      if (!active || revision !== historyRevision) return
      if (after === undefined) {
        for (const item of page.items) {
          const realtime = buffered.get(BigInt(item.roomSeq))
          if (realtime && realtime.messageId !== item.messageId) {
            resetHistory('消息顺序需要重新同步，正在读取最近历史。')
            return
          }
        }
        continuous = BigInt(page.storedThrough)
        messages.value = page.items
        for (const number of buffered.keys()) if (number <= continuous) buffered.delete(number)
      } else {
        // 每合并一条就推进能够展示的前缀，历史页不会额外挤占实时缓冲。
        for (const item of page.items) {
          if (!buffer(item)) return
          drain()
        }
        const targetThrough = BigInt(page.targetThrough)
        pinned = continuous !== undefined && continuous < targetThrough ? targetThrough : undefined
        wanted = max(wanted, targetThrough)
        if (pinned !== undefined && page.nextAfterSeq === null) {
          delay = historyDelay
          historyDelay = Math.min(historyDelay * 2, 3000)
        }
      }
      drain()
      if (delay === 0) historyDelay = 250
      if (syncFeedback.value) syncFeedback.value = '已同步最近弹幕。'
    } catch (cause) {
      if (!active || revision !== historyRevision) return
      if (cause instanceof ApiRequestError && cause.code === 'ROOM_HISTORY_GAP') {
        resetHistory('部分弹幕已过保存期限，正在读取最近历史。')
      } else {
        if (!syncFeedback.value) syncFeedback.value = '弹幕历史暂不可用，正在重试。'
        forceHistory = true
        delay = historyDelay
        historyDelay = Math.min(historyDelay * 2, 3000)
      }
    } finally {
      historyInFlight = false
      syncing.value = false
      if (active && needsHistory()) scheduleHistory(delay)
    }
  }

  /** @returns 校验当前输入，并用固定的标识、正文和期限发起提交 */
  async function send() {
    if (!canSend.value) return
    const raw = content.value
    const normalized = raw.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '')
    failed.value = false
    feedback.value = ''
    if (/[\u0000-\u001f\u007f-\u009f\u2028\u2029\uD800-\uDFFF]/u.test(raw)
      || [...normalized].length < 1 || [...normalized].length > 100) {
      failed.value = true
      feedback.value = '请输入 1–100 个字符的单行弹幕。'
      return
    }
    pending.value = { body: { clientMessageId: crypto.randomUUID(), content: raw },
      retryUntil: Date.now() + RETRY_MILLIS }
    await submitPending()
  }

  /** @returns 使用原正文和标识完成 POST，入队后开始限时查询终态 */
  async function submitPending() {
    const submission = pending.value
    if (!submission || submission.queued || submitting.value || state.value !== 'ready') return
    if (Date.now() >= submission.retryUntil) {
      expireSubmission(submission)
      return
    }
    submitting.value = true
    failed.value = false
    pendingAction.value = 'none'
    feedback.value = ''
    try {
      const queued = await withAccess(access => sendRoomMessage(access, roomId, submission.body))
      if (!active || pending.value !== submission) return
      submission.queued = queued
      submission.retryUntil = Math.min(submission.retryUntil, Date.parse(queued.retryUntil))
      feedback.value = '已入队，等待发送确认。'
      window.clearTimeout(receiptExpiry)
      receiptExpiry = window.setTimeout(() => expireSubmission(submission),
        Math.max(0, Date.parse(queued.receiptExpiresAt) - Date.now()))
      const until = Date.now() + POLL_MILLIS
      receiptTimer = window.setTimeout(() => void checkReceipt(until, 500), 500)
    } catch (cause) {
      if (!active || pending.value !== submission) return
      failed.value = true
      if (!(cause instanceof ApiRequestError) || cause.status === 0
        || (cause.status >= 500 && !['ROOM_MESSAGES_UNAVAILABLE', 'AUTH_SERVICE_UNAVAILABLE'].includes(cause.code))) {
        submission.wasUncertain = true
        pendingAction.value = 'retry'
        feedback.value = '发送结果待确认，可重试原弹幕。'
        window.clearTimeout(receiptExpiry)
        receiptExpiry = window.setTimeout(() => expireSubmission(submission),
          Math.max(0, submission.retryUntil - Date.now()))
      } else {
        const waitSeconds = cause.retryAfterSeconds
        const limited = cause.code === 'MESSAGE_RATE_LIMITED' && waitSeconds !== undefined && waitSeconds > 0
        if (limited) {
          startCooldown(waitSeconds)
          feedback.value = ''
        } else {
          feedback.value = cause.message
        }
        if (submission.wasUncertain && (limited || cause.status >= 500 || cause.code === 'AUTH_CSRF_INVALID')) {
          pendingAction.value = 'retry'
        } else {
          clearPending()
        }
        if (cause.code === 'AUTH_CSRF_INVALID') await authClient.csrf().catch(() => undefined)
      }
    } finally {
      if (active) submitting.value = false
    }
  }

  /**
   * @param until 自动查询截止时间，手动查询时省略
   * @param delay 本轮查询退避的毫秒数
   * @returns 更新终态、等待反馈或安排下一轮查询
   */
  async function checkReceipt(until?: number, delay = 500) {
    const submission = pending.value
    if (!submission?.queued || checkingReceipt.value || !active) return
    const { submissionId, receiptExpiresAt } = submission.queued
    if (Date.now() >= Date.parse(receiptExpiresAt)) {
      expireSubmission(submission)
      return
    }
    checkingReceipt.value = true
    pendingAction.value = 'none'
    try {
      const receipt = await withAccess(access => requestMessageReceipt(access, roomId, submissionId))
      if (!active || pending.value !== submission) return
      if (receipt.status === 'accepted') {
        if (content.value === submission.body.content) content.value = ''
        feedback.value = '弹幕已发送。'
        clearPending()
        return
      }
      if (receipt.status === 'rejected') {
        failed.value = true
        feedback.value = receipt.code === 'MESSAGE_ID_CONFLICT' ? '提交标识已绑定其他弹幕。' : '弹幕提交已过期。'
        clearPending()
        return
      }
    } catch (cause) {
      if (!active || pending.value !== submission) return
      if (cause instanceof ApiRequestError && cause.status === 401) {
        failed.value = true
        feedback.value = cause.message
        clearPending()
        return
      }
    } finally {
      if (active) checkingReceipt.value = false
    }
    const next = Math.min(2 * delay, 2500)
    if (until !== undefined && Date.now() + next < until) {
      receiptTimer = window.setTimeout(() => void checkReceipt(until, next), next)
    } else {
      feedback.value = '弹幕待确认，可手动查询。'
      pendingAction.value = 'receipt'
    }
  }

  /**
   * 以单调时钟计算本地剩余时间，后台标签页定时器延迟时直接追上实际截止。
   * @param seconds 服务端 Retry-After 给出的整数秒
   */
  function startCooldown(seconds: number) {
    const deadline = performance.now() + seconds * 1000
    window.clearTimeout(cooldownTimer)
    function update() {
      const remainingMillis = deadline - performance.now()
      cooldownSeconds.value = Math.max(0, Math.ceil(remainingMillis / 1000))
      if (cooldownSeconds.value > 0) {
        const nextTick = remainingMillis - (cooldownSeconds.value - 1) * 1000
        cooldownTimer = window.setTimeout(update, nextTick)
      }
    }
    update()
  }

  /** @param submission 达到重试或查询截止的操作 */
  function expireSubmission(submission: PendingSubmission) {
    if (!active || pending.value !== submission) return
    failed.value = true
    feedback.value = submission.queued ? '回执查询期限已结束。' : '原弹幕已超过重试期限。'
    clearPending()
  }

  /** 停止追踪当前提交，保留草稿供用户明确提交新内容。 */
  function clearPending() {
    pending.value = undefined
    pendingAction.value = 'none'
    window.clearTimeout(receiptTimer)
    window.clearTimeout(receiptExpiry)
  }

  return { state, connectionError, messages, content, submitting, canSend, feedback, failed, reconnect, send,
    syncing, syncFeedback, cooldownSeconds, pendingAction, checkingReceipt, submitPending, checkReceipt, clearPending }
}

function max(first: bigint, second: bigint) {
  return first > second ? first : second
}
