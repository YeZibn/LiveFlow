import { computed, onBeforeUnmount, onMounted, ref, shallowReactive, shallowRef, watch } from 'vue'
import { ApiRequestError } from '../api/requestJson'
import { authClient } from '../auth/authClient'
import type { WithAccess } from '../auth/useAuth'
import { decodeRoomFrame, openRoomSocket, requestMessageReceipt, requestRoomHistory, requestRoomTicket, sendRoomMessage,
  unknownSubmissionReceipt, type MessageSubmission, type RoomMessage, type SubmissionReceiptReference } from './roomMessagesClient'

const RECONNECT_DELAYS = [500, 1200, 3000]
const HISTORY_LIMIT = 100
const HISTORY_RETRY_DELAYS = [250, 500, 1000, 2000]
const POLL_MILLIS = 10_000

interface HistoryRefresh {
  inFlight: boolean
  queued: boolean
  failures: number
  timer?: number
}

interface ReadingHistory {
  messages: RoomMessage[]
  /** 回看开始时最晚的一条。更晚的消息只进入最近窗口。 */
  head: bigint
  before: string | null
  status: 'idle' | 'loading' | 'error' | 'end'
  loadedOlder: boolean
}

interface PendingSubmission {
  body: MessageSubmission
  draftRevision: number
  receipt?: SubmissionReceiptReference
  unknown?: boolean
}

/**
 * @param roomId 已加载房间的 UUID
 * @param withAccess 当前会话的受保护请求入口
 * @returns 面板连接、历史、草稿与发送回执状态
 */
export function useRoomMessages(roomId: string, withAccess: WithAccess) {
  const state = ref<'connecting' | 'ready' | 'reconnecting' | 'offline'>('connecting')
  const connectionError = ref('')
  const recent = shallowRef<RoomMessage[]>([])
  const reading = shallowRef<ReadingHistory>()
  const messages = computed(() => reading.value?.messages ?? recent.value)
  const isReading = computed(() => reading.value !== undefined)
  const olderStatus = computed(() => reading.value?.status ?? 'idle')
  const hasNewMessages = computed(() => {
    const latest = recent.value.at(-1)
    return !!reading.value && !!latest && BigInt(latest.roomSeq) > reading.value.head
  })
  const canFollowFromBottom = computed(() => !!reading.value && !reading.value.loadedOlder && !hasNewMessages.value)
  const content = ref('')
  const submitting = ref(false)
  const feedback = ref('')
  const cooldownSeconds = ref(0)
  const failed = ref(false)
  const syncFeedback = ref('')
  const syncing = ref(false)
  const showSubmissionActions = ref(false)
  const pending = ref<PendingSubmission>()
  const pendingContent = computed(() => pending.value?.unknown ? pending.value.body.content : '')
  const canCheckReceipt = computed(() => pending.value?.receipt !== undefined)
  const checkingReceipt = ref(false)
  let latestSeen = 0n
  let historyRefresh: HistoryRefresh | undefined
  let receiptTimer: number | undefined
  let receiptExpiry: number | undefined
  let cooldownTimer: number | undefined
  let socket: WebSocket | undefined
  let reconnectTimer: number | undefined
  let readyTimer: number | undefined
  let retries = 0
  let generation = 0
  let active = true
  let draftRevision = 0
  watch(content, () => { draftRevision++ }, { flush: 'sync' })

  const canSend = computed(() => state.value === 'ready' && !submitting.value
    && cooldownSeconds.value === 0 && pending.value === undefined && content.value.trim().length > 0)

  onMounted(() => void connect())
  onBeforeUnmount(() => {
    // 父组件以房间和会话为 key。所有未完成的旧回调在卸载后失效。
    active = false
    generation++
    window.clearTimeout(readyTimer)
    window.clearTimeout(reconnectTimer)
    cancelHistory()
    endReading()
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
    }, delay + Math.floor(Math.random() * delay / 4))
  }

  /** @returns 申请票据，注册当前连接的生命周期及消息回调 */
  async function connect() {
    const attempt = ++generation
    cancelHistory()
    window.clearTimeout(readyTimer)
    window.clearTimeout(reconnectTimer)
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
          if (state.value !== 'ready') {
            state.value = 'ready'
            historyRefresh = { inFlight: false, queued: false, failures: 0 }
            refreshHistory()
          }
        } else if (frame.type === 'error') {
          connectionError.value = frame.message
          connection.close()
        } else if (state.value === 'ready') {
          if (frame.type === 'message') {
            receiveMessages([frame], false)
          } else if (frame.type === 'resync_required'
            || (frame.type === 'history_progress' && BigInt(frame.latestStoredSeq) > latestSeen)) {
            refreshHistory()
          }
        }
      }
      connection.onclose = () => {
        if (!current(attempt, connection)) return
        socket = undefined
        cancelHistory()
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

  /** 清除自动重试次数并申请新票据，保留当前房间及会话的消息列表。 */
  function reconnect() {
    retries = 0
    void connect()
  }

  /**
   * 将实时帧和最近历史合入跟随窗口，同时纠正回看范围内的记录。
   * @param incoming 最新收到的实时帧或最近历史
   * @param persisted 是否来自已落库的历史查询
   */
  function receiveMessages(incoming: RoomMessage[], persisted: boolean) {
    const view = reading.value
    if (view) {
      const first = BigInt(view.messages[0].roomSeq)
      const visibleRange = incoming.filter(item => {
        const sequence = BigInt(item.roomSeq)
        return sequence >= first && sequence <= view.head
      })
      view.messages = mergeMessages(view.messages, visibleRange, persisted)
    }
    const last = incoming.at(-1)
    if (last && BigInt(last.roomSeq) > latestSeen) latestSeen = BigInt(last.roomSeq)
    recent.value = mergeMessages(recent.value, incoming, persisted, HISTORY_LIMIT)
  }

  /** 冻结当前列表的阅读范围，首次向上查询用实际显示的最小序号。 */
  function beginReading() {
    if (reading.value || recent.value.length === 0) return
    const displayed = recent.value
    reading.value = shallowReactive<ReadingHistory>({
      messages: displayed,
      head: BigInt(displayed.at(-1)!.roomSeq),
      before: displayed[0].roomSeq,
      status: 'idle',
      loadedOlder: false,
    })
  }

  /** 释放当前回看和它的请求身份，使迟到结果失效。 */
  function endReading() {
    reading.value = undefined
  }

  /**
   * 读取并合并更早一页。失败时保留游标，供用户重试。
   * @returns 本次加载结束或无可用游标时完成
   */
  async function loadOlder() {
    beginReading()
    const view = reading.value
    if (!view || view.before === null || view.status === 'loading') return
    const beforeSeq = view.before
    view.status = 'loading'
    try {
      const page = await withAccess(access => requestRoomHistory(access, roomId, { beforeSeq }))
      // 连接重建不影响历史 GET，只有退出回看或更换房间会作废本次结果。
      if (view !== reading.value) return
      view.messages = mergeMessages(view.messages, page.items, true)
      view.before = page.nextBeforeSeq
      if (page.items.length > 0) view.loadedOlder = true
      view.status = view.before === null ? 'end' : 'idle'
    } catch {
      if (view === reading.value) view.status = 'error'
    }
  }

  /**
   * 以消息 ID 和房间序号合并，保持两种唯一标识与房间顺序。
   * @param existing 当前窗口或回看的消息
   * @param incoming 当前实时帧或历史返回的消息
   * @param persisted 历史记录具有权威性，可以替换冲突的实时记录
   * @param limit 最近窗口的条数上限，回看累积用户主动加载的页
   * @returns 合并后的升序列表，无可见变化时复用原列表
   */
  function mergeMessages(existing: RoomMessage[], incoming: RoomMessage[], persisted: boolean, limit?: number) {
    if (incoming.length === 0) return existing
    const bySequence = new Map(existing.map(message => [message.roomSeq, message]))
    const byId = new Map(existing.map(message => [message.messageId, message]))
    let changed = false
    for (const item of incoming) {
      const sameSequence = bySequence.get(item.roomSeq)
      const sameId = byId.get(item.messageId)
      if (sameSequence || sameId) {
        if (!persisted) continue
        if (sameSequence === sameId && sameId?.senderName === item.senderName
          && sameId.content === item.content && sameId.sentAt === item.sentAt) continue
        // 一条历史消息可能同时与两个不同的实时项撞键，移除各自的两个索引。
        for (const match of new Set([sameSequence, sameId])) {
          if (match) {
            bySequence.delete(match.roomSeq)
            byId.delete(match.messageId)
          }
        }
      }
      bySequence.set(item.roomSeq, item)
      byId.set(item.messageId, item)
      changed = true
    }
    if (!changed) return existing
    const merged = Array.from(bySequence.values()).sort((a, b) => BigInt(a.roomSeq) < BigInt(b.roomSeq) ? -1 : 1)
    const visible = limit === undefined ? merged : merged.slice(-limit)
    // 窗口外的迟到项被裁剪时无需重新渲染，避免打断正在上翻的读者。
    return visible.length !== existing.length || visible.some((item, index) => item !== existing[index])
      ? visible
      : existing
  }

  /** 连接换代或退出时作废计时器和在途响应，历史回调不能写入新连接。 */
  function cancelHistory() {
    window.clearTimeout(historyRefresh?.timer)
    historyRefresh = undefined
    syncing.value = false
    syncFeedback.value = ''
  }

  /** 将多次同步提示合并成一个请求，已在途时最多保留一次后续刷新。 */
  function refreshHistory() {
    const refresh = historyRefresh
    if (!refresh) return
    if (!refresh.inFlight && refresh.timer === undefined) refresh.failures = 0
    refresh.queued = true
    scheduleHistory(refresh, 0)
  }

  /**
   * @param refresh 当前连接的历史查询状态
   * @param delay 开始下次 GET 前等待的毫秒数
   */
  function scheduleHistory(refresh: HistoryRefresh, delay: number) {
    if (refresh !== historyRefresh || refresh.inFlight || refresh.timer !== undefined || !refresh.queued) return
    refresh.timer = window.setTimeout(() => {
      refresh.timer = undefined
      if (refresh === historyRefresh) void synchronize(refresh)
    }, delay)
  }

  /** @param refresh 发起本轮查询的连接状态，用对象身份隔离迟到响应 */
  async function synchronize(refresh: HistoryRefresh) {
    refresh.queued = false
    refresh.inFlight = true
    syncing.value = true
    let delay = 0
    try {
      const page = await withAccess(access => requestRoomHistory(access, roomId))
      if (refresh !== historyRefresh) return
      receiveMessages(page.items, true)
      refresh.failures = 0
      syncFeedback.value = ''
    } catch (failure) {
      if (refresh !== historyRefresh) return
      syncFeedback.value = '暂时无法读取历史弹幕，仍可接收实时消息。'
      const transient = failure instanceof ApiRequestError && (failure.status === 0 || failure.status >= 500)
      const retryDelay = transient ? HISTORY_RETRY_DELAYS[refresh.failures++] : undefined
      if (retryDelay !== undefined) {
        refresh.queued = true
        delay = retryDelay
      }
    } finally {
      if (refresh === historyRefresh) {
        refresh.inFlight = false
        syncing.value = false
        scheduleHistory(refresh, delay)
      }
    }
  }

  /** @returns 校验草稿、创建本次标识并至多发出一个发送 POST */
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
    pending.value = { body: { clientMessageId: crypto.randomUUID(), content: raw }, draftRevision }
    const submission = pending.value
    submitting.value = true
    try {
      const queued = await withAccess(access => sendRoomMessage(access, roomId, submission.body), { replayOnExpired: false })
      if (!active || pending.value !== submission) return
      trackReceipt(submission, queued)
      feedback.value = '已入队，等待发送确认。'
      const until = Date.now() + POLL_MILLIS
      receiptTimer = window.setTimeout(() => void checkReceipt(until, 500), 500)
    } catch (cause) {
      if (!active || pending.value !== submission) return
      failed.value = true
      if (!(cause instanceof ApiRequestError) || cause.status === 0
        || (cause.status >= 500 && !['ROOM_MESSAGES_UNAVAILABLE', 'AUTH_SERVICE_UNAVAILABLE'].includes(cause.code))) {
        submission.unknown = true
        // 将未知提交留在结果区，避免其文本作为尚未提交的草稿再次发送。
        clearSubmittedDraft(submission)
        const receipt = unknownSubmissionReceipt(cause)
        if (receipt) trackReceipt(submission, receipt)
        showSubmissionActions.value = true
        feedback.value = '发送结果未确认，消息可能已送达。'
      } else {
        const waitSeconds = cause.retryAfterSeconds
        const limited = cause.code === 'MESSAGE_RATE_LIMITED' && waitSeconds !== undefined && waitSeconds > 0
        if (limited) {
          startCooldown(waitSeconds)
          feedback.value = ''
        } else {
          feedback.value = ['ROOM_MESSAGES_UNAVAILABLE', 'AUTH_SERVICE_UNAVAILABLE'].includes(cause.code)
            ? '弹幕暂未发送，服务暂时不可用。' : cause.message
        }
        clearPending()
        if (cause.code === 'AUTH_CSRF_INVALID') await authClient.csrf().catch(() => undefined)
      }
    } finally {
      if (active) submitting.value = false
    }
  }

  /**
   * @param submission 原操作，换房或放弃跟踪后不会接收迟到结果
   * @param receipt 服务端验证后的只读查询标识和截止时间
   */
  function trackReceipt(submission: PendingSubmission, receipt: SubmissionReceiptReference) {
    submission.receipt = receipt
    receiptExpiry = window.setTimeout(() => expireSubmission(submission),
      Math.max(0, Date.parse(receipt.receiptExpiresAt) - Date.now()))
  }

  /**
   * @param until 自动查询截止时间，手动查询时省略
   * @param delay 本轮查询退避的毫秒数
   * @returns 更新终态、等待反馈或安排下一轮查询
   */
  async function checkReceipt(until?: number, delay = 500) {
    const submission = pending.value
    if (!submission?.receipt || checkingReceipt.value || !active) return
    const { submissionId, receiptExpiresAt } = submission.receipt
    if (Date.now() >= Date.parse(receiptExpiresAt)) {
      expireSubmission(submission)
      return
    }
    checkingReceipt.value = true
    try {
      const receipt = await withAccess(access => requestMessageReceipt(access, roomId, submissionId))
      if (!active || pending.value !== submission) return
      if (receipt.status === 'accepted') {
        clearSubmittedDraft(submission)
        failed.value = false
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
      if (active && pending.value === submission) checkingReceipt.value = false
    }
    const next = Math.min(2 * delay, 2500)
    if (until !== undefined && Date.now() + next < until) {
      receiptTimer = window.setTimeout(() => void checkReceipt(until, next), next)
    } else {
      feedback.value = submission.unknown ? '发送结果未确认，消息可能已送达。' : '弹幕待确认，可手动查询。'
      showSubmissionActions.value = true
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

  /** @param submission 达到回执查询截止的操作 */
  function expireSubmission(submission: PendingSubmission) {
    if (!active || pending.value !== submission) return
    failed.value = true
    feedback.value = '回执查询期限已结束，发送结果仍未确认。'
    clearSubmittedDraft(submission)
    clearPending()
  }

  /** 停止追踪当前提交，保留草稿供用户明确提交新内容。 */
  function clearPending() {
    pending.value = undefined
    showSubmissionActions.value = false
    checkingReceipt.value = false
    window.clearTimeout(receiptTimer)
    window.clearTimeout(receiptExpiry)
  }

  /** @param submission 使用提交时的草稿代次，避免迟到的回执清除用户新编辑的内容 */
  function clearSubmittedDraft(submission: PendingSubmission) {
    if (draftRevision === submission.draftRevision) content.value = ''
  }

  /** 结束跟踪，保留用户已编辑的新草稿。 */
  function dismissSubmission() {
    if (pending.value) clearSubmittedDraft(pending.value)
    clearPending()
    feedback.value = ''
    failed.value = false
  }

  return { state, connectionError, messages, content, submitting, canSend, feedback, failed, reconnect, send,
    syncing, syncFeedback, cooldownSeconds, showSubmissionActions, pendingContent, canCheckReceipt,
    checkingReceipt, checkReceipt, dismissSubmission, isReading, olderStatus, hasNewMessages,
    canFollowFromBottom, beginReading, endReading, loadOlder }
}
