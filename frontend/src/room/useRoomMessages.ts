import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiRequestError } from '../api/requestJson'
import { authClient } from '../auth/authClient'
import { decodeRoomFrame, openRoomSocket, requestRoomTicket, sendRoomMessage,
  type MessageSubmission, type RoomMessage } from './roomMessagesClient'

type WithAccess = <T>(call: (access: string) => Promise<T>) => Promise<T>

const RECONNECT_DELAYS = [500, 1200, 3000]
const HISTORY_LIMIT = 100

/**
 * 管理当前房间组件的一次接收生命周期和弹幕提交状态。
 * @param roomId 已成功加载的房间 UUID
 * @param withAccess 当前登录会话的受保护请求入口
 * @returns 面板所需连接状态、列表、输入与操作
 */
export function useRoomMessages(roomId: string, withAccess: WithAccess) {
  const state = ref<'connecting' | 'ready' | 'reconnecting' | 'offline'>('connecting')
  const connectionError = ref('')
  const messages = ref<RoomMessage[]>([])
  const content = ref('')
  const submitting = ref(false)
  const feedback = ref('')
  const failed = ref(false)
  let socket: WebSocket | undefined
  let reconnectTimer: number | undefined
  let readyTimer: number | undefined
  let retryCount = 0
  let generation = 0
  let active = true
  // 保留发送结果不明的原请求。用户重试相同输入时沿用客户端消息标识。
  let uncertain: MessageSubmission | undefined

  const canSend = computed(() => state.value === 'ready' && !submitting.value && content.value.trim().length > 0)

  onMounted(() => void connect())
  onBeforeUnmount(() => {
    // 房间和会话由父组件的 key 隔离。使所有未完成的异步回调立即失效。
    active = false
    generation++
    window.clearTimeout(readyTimer)
    window.clearTimeout(reconnectTimer)
    socket?.close()
  })

  /**
   * 确认异步回调仍属于当前尝试，防止关掉旧连接后覆盖新连接状态。
   * @param attempt 发起回调时记录的连接代次
   * @param connection 可选的具体连接，用于检查 WebSocket 事件
   * @returns 页面仍在且连接尝试未被替换时为 true
   */
  function isCurrent(attempt: number, connection?: WebSocket) {
    return active && attempt === generation && (!connection || socket === connection)
  }

  /**
   * 按设定的延迟顺序安排下一次重连，耗尽次数后交给手动重连。
   * @param attempt 刚结束或失败的连接代次
   */
  function scheduleReconnect(attempt: number) {
    if (!isCurrent(attempt)) return
    const delay = RECONNECT_DELAYS[retryCount]
    if (delay === undefined) {
      state.value = 'offline'
      return
    }
    retryCount++
    state.value = 'reconnecting'
    reconnectTimer = window.setTimeout(() => {
      if (isCurrent(attempt)) void connect()
    }, delay)
  }

  /**
   * 申请新票据并开启接收。旧的请求、计时器和连接无法更新新尝试。
   * @returns 启动连接或安排下一次尝试后完成
   */
  async function connect() {
    const attempt = ++generation
    window.clearTimeout(readyTimer)
    window.clearTimeout(reconnectTimer)
    socket?.close()
    socket = undefined
    connectionError.value = ''
    state.value = retryCount === 0 ? 'connecting' : 'reconnecting'
    try {
      const issued = await withAccess(access => requestRoomTicket(access, roomId))
      if (!isCurrent(attempt)) return
      const connection = openRoomSocket()
      socket = connection
      // 收到应用层 ready 才表示入房成功。超时关闭当前连接，由关闭回调统一处理重连。
      readyTimer = window.setTimeout(() => {
        if (isCurrent(attempt, connection)) connection.close()
      }, 8000)

      connection.onopen = () => {
        if (!isCurrent(attempt, connection)) return
        try {
          connection.send(JSON.stringify({ type: 'auth', ticket: issued.ticket }))
        } catch {
          connectionError.value = '连接未能提交票据，请重试。'
          connection.close()
        }
      }
      connection.onmessage = event => {
        if (!isCurrent(attempt, connection)) return
        const frame = decodeRoomFrame(event.data)
        if (!frame || (frame.type === 'ready' && frame.roomId !== roomId)) {
          connectionError.value = '连接响应无效，请重试。'
          connection.close()
          return
        }
        if (frame.type === 'ready') {
          // 只有成功加入对应房间才重置自动重连次数并启用发送。
          window.clearTimeout(readyTimer)
          retryCount = 0
          state.value = 'ready'
        } else if (frame.type === 'error') {
          connectionError.value = frame.message
          connection.close()
        } else if (state.value === 'ready' && !messages.value.some(item => item.messageId === frame.messageId)) {
          // HTTP 回执只确认提交，列表始终从广播追加。
          messages.value = [...messages.value, frame].slice(-HISTORY_LIMIT)
        }
      }
      connection.onclose = () => {
        if (!isCurrent(attempt, connection)) return
        socket = undefined
        window.clearTimeout(readyTimer)
        scheduleReconnect(attempt)
      }
    } catch (cause) {
      if (!isCurrent(attempt)) return
      if (cause instanceof ApiRequestError) {
        connectionError.value = cause.message
        if (cause.code === 'AUTH_CSRF_INVALID') {
          await authClient.csrf().catch(() => undefined)
        }
        if (!isCurrent(attempt)) return
        if (cause.status === 401 || cause.status === 404 || cause.status === 400) {
          state.value = 'offline'
          return
        }
      } else {
        connectionError.value = '无法建立弹幕连接，请重试。'
      }
      scheduleReconnect(attempt)
    }
  }

  /** 清除自动重连次数，立即申请新票据并重新连接。 */
  function reconnect() {
    retryCount = 0
    void connect()
  }

  /**
   * 提交当前正文的固定快照。结果未知时允许用户用同一标识重试。
   * @returns 更新发送反馈后完成，广播独立处理
   */
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
    const submission = uncertain?.content === raw ? uncertain : { clientMessageId: crypto.randomUUID(), content: raw }
    submitting.value = true
    try {
      await withAccess(access => sendRoomMessage(access, roomId, submission))
      if (!active) return
      uncertain = undefined
      // 只清除与这次回执对应的输入，已变更的草稿保留。
      if (content.value === raw) content.value = ''
      feedback.value = '弹幕已提交'
    } catch (cause) {
      if (!active) return
      failed.value = true
      if (!(cause instanceof ApiRequestError) || cause.status === 0
        || (cause.status >= 500 && cause.code !== 'ROOM_MESSAGES_UNAVAILABLE' && cause.code !== 'AUTH_SERVICE_UNAVAILABLE')) {
        // 请求可能已经被接受。手动重试复用同一快照，使服务端能够返回原结果。
        uncertain = submission
        feedback.value = '发送结果不确定。可重试原消息，超过 60 秒或服务重启后可能重复。'
      } else if (cause.code === 'MESSAGE_RATE_LIMITED' && cause.retryAfterSeconds !== undefined) {
        feedback.value = `发送过快，请等待 ${cause.retryAfterSeconds} 秒。`
      } else {
        feedback.value = cause.message
        if (cause.code === 'AUTH_CSRF_INVALID') await authClient.csrf().catch(() => undefined)
      }
    } finally {
      if (active) submitting.value = false
    }
  }

  return { state, connectionError, messages, content, submitting, canSend, feedback, failed, reconnect, send }
}
