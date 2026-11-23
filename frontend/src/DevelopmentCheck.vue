<script setup lang="ts">
import { onMounted, ref } from 'vue'

/** Actuator 健康响应中开发检查页需要读取的字段。 */
interface Health {
  /** 后端的整体健康状态，请求失败时页面会显示「不可达」。 */
  status: string
  /** 按 Actuator 组件 ID 索引的状态，后端在 local profile 下提供。 */
  components?: Record<string, { status: string }>
}

const health = ref<Health>()
const websocket = ref('待检查')
const checking = ref(false)
const services = [
  { id: 'db', name: 'MySQL' },
  { id: 'redis', name: 'Redis' },
  { id: 'kafka', name: 'Kafka' },
]

/**
 * 通过 Vite 代理读取 Actuator 健康状态。请求或 JSON 解析失败时，将整体状态记为「不可达」。
 *
 * @returns 健康状态写入页面后完成的 Promise。
 */
async function checkHealth() {
  try {
    const response = await fetch('/actuator/health', { signal: AbortSignal.timeout(5000) })
    // 依赖异常时 Actuator 可能返回 503，响应体仍包含可展示的组件状态。
    health.value = await response.json() as Health
  } catch {
    health.value = { status: '不可达' }
  }
}

/**
 * 通过 Vite 代理建立临时 WebSocket 连接，打开后发送文本 ping。
 * 收到 pong 时记为 UP。超时、提前断开或响应不符合约定时记为「不可达」。
 *
 * @returns 检查结果写入页面并发起关闭已创建连接后完成的 Promise。
 */
async function checkWebSocket() {
  try {
    const socket = new WebSocket(new URL('/ws/smoke', window.location.href))
    try {
      await new Promise<void>((resolve, reject) => {
        const timeout = window.setTimeout(() => reject(new Error('WebSocket timeout')), 4000)
        socket.addEventListener('open', () => socket.send('ping'), { once: true })
        socket.addEventListener('message', (event) => {
          window.clearTimeout(timeout)
          event.data === 'pong' ? resolve() : reject(new Error('Unexpected WebSocket response'))
        }, { once: true })
        socket.addEventListener('close', () => {
          window.clearTimeout(timeout)
          reject(new Error('WebSocket closed'))
        }, { once: true })
      })
      websocket.value = 'UP'
    } finally {
      // 每次检查只使用一条临时连接，完成或超时后都关闭。
      socket.close()
    }
  } catch {
    websocket.value = '不可达'
  }
}

/**
 * 并行刷新 HTTP 和 WebSocket 检查结果，供页面首次挂载及手动刷新时调用。
 * 检查期间禁用刷新按钮，任务结束后恢复按钮状态。
 *
 * @returns 两项检查均结束且按钮状态恢复后完成的 Promise。
 */
async function checkServices() {
  checking.value = true
  try {
    await Promise.all([checkHealth(), checkWebSocket()])
  } finally {
    checking.value = false
  }
}

onMounted(checkServices)
</script>

<template>
  <main class="development-check">
    <header>
      <h1>LiveFlow 开发环境</h1>
      <button class="ui-button ui-button--primary" type="button" :disabled="checking" @click="checkServices">
        {{ checking ? '检查中…' : '重新检查' }}
      </button>
    </header>

    <dl aria-live="polite">
      <div><dt>Spring Boot</dt><dd :data-status="health?.status">{{ health?.status ?? '待检查' }}</dd></div>
      <div v-for="service in services" :key="service.id">
        <dt>{{ service.name }}</dt>
        <dd :data-status="health?.components?.[service.id]?.status">{{ health?.components?.[service.id]?.status ?? '未知' }}</dd>
      </div>
      <div><dt>WebSocket</dt><dd :data-status="websocket">{{ websocket }}</dd></div>
    </dl>
  </main>
</template>

<style scoped>
.development-check {
  width: min(calc(100% - 2rem), 38rem);
  margin: clamp(1rem, 5vw, 4rem) auto 3rem;
}
header, dl > div { display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; }
header { flex-wrap: wrap; }
h1 { margin: 0; font-size: clamp(1.4rem, 4vw, 2rem); letter-spacing: -0.035em; }
dl { padding: 0.35rem 1.15rem; border: 1px solid var(--line); border-radius: 1.25rem; background: var(--surface); }
dl > div { padding: 0.85rem 0; }
dl > div + div { border-top: 1px solid var(--line); }
dd {
  margin: 0;
  padding: 0.3rem 0.65rem;
  border-radius: 999px;
  background: #f3f3ef;
  color: var(--muted);
  font-size: 0.8rem;
  font-weight: 650;
}
dd[data-status="UP"] { background: var(--tint); color: var(--accent); }
dd:is([data-status="DOWN"], [data-status="OUT_OF_SERVICE"], [data-status="不可达"]) { background: #fcf0ed; color: var(--danger); }
</style>
