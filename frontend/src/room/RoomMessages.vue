<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRoomMessages } from './useRoomMessages'

const props = defineProps<{
  roomId: string
  withAccess: <T>(call: (access: string) => Promise<T>) => Promise<T>
}>()

const { state, connectionError, messages, content, submitting, canSend, feedback, failed, reconnect, send,
  syncing, syncFeedback, cooldownSeconds, pendingAction, checkingReceipt, submitPending, checkReceipt, clearPending } =
  useRoomMessages(props.roomId, props.withAccess)
const log = ref<HTMLElement>()
const hasNewMessages = ref(false)
const formatter = new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})
const stateName = { connecting: '连接中', ready: '已连接', reconnecting: '正在重连', offline: '连接已断开' }

// 先从更新前的 DOM 取可见位置，等 Vue 渲染后再应用跟随或阅读锚点。
watch(messages, async updated => {
  const node = log.value
  if (!node) return
  const following = isNearBottom(node)
  const top = node.getBoundingClientRect().top
  // 记录仍保留的首个可见项，以便顶部裁剪后恢复它原来的屏幕位置。
  const retained = new Set(updated.map(item => item.messageId))
  const anchor = following ? undefined : Array.from(node.querySelectorAll<HTMLElement>('li[data-message-id]'))
    .find(row => retained.has(row.dataset.messageId ?? '') && row.getBoundingClientRect().bottom > top)
  const priorTop = anchor?.getBoundingClientRect().top
  if (!following) hasNewMessages.value = true
  await nextTick()
  if (log.value !== node) return
  if (following) {
    jumpToLatest()
  } else if (anchor && priorTop !== undefined) {
    node.scrollTop += anchor.getBoundingClientRect().top - priorTop
  } else {
    node.scrollTop = 0
  }
})

/**
 * 判断阅读位置是否在列表底部附近，容许行高和像素取整带来的少量偏差。
 * @param node 当前独立滚动的消息列表
 * @returns 距底部不超过 24 像素时为 true
 */
function isNearBottom(node: HTMLElement) {
  return node.scrollHeight - node.clientHeight - node.scrollTop <= 24
}

/** 用户滚回底部后，清除新消息提示。 */
function onScroll() {
  if (log.value && isNearBottom(log.value)) hasNewMessages.value = false
}

/** 将列表恢复到跟随最新消息的位置。 */
function jumpToLatest() {
  if (log.value) log.value.scrollTop = log.value.scrollHeight
  hasNewMessages.value = false
}

/**
 * 普通回车会发送当前弹幕，输入法组字期间保留回车确认候选词的行为。
 * @param event 弹幕输入框的键盘事件
 */
function handleEnter(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  void send()
}
</script>

<template>
  <section class="room-messages" aria-labelledby="room-messages-title">
    <header class="panel-heading">
      <div class="panel-title">
        <h2 id="room-messages-title">弹幕</h2>
        <p class="connection" :data-state="state" role="status">
          <span aria-hidden="true"></span>{{ stateName[state] }}
        </p>
      </div>
      <p v-if="connectionError" class="feedback" role="status">{{ connectionError }}</p>
      <p v-else-if="syncing || syncFeedback" class="feedback" role="status">
        {{ syncing ? '正在同步弹幕…' : syncFeedback }}
      </p>
      <button v-if="state === 'offline' || state === 'reconnecting'" class="ui-button reconnect"
        type="button" @click="reconnect">重连</button>
    </header>

    <div class="message-area">
      <div ref="log" class="message-log" role="log" aria-label="直播间弹幕" tabindex="0" @scroll="onScroll">
        <p v-if="messages.length === 0" class="empty">
          {{ state === 'ready' ? '还没有弹幕' : '连接后即可接收弹幕' }}
        </p>
        <ol v-else>
          <li v-for="item in messages" :key="item.messageId" :data-message-id="item.messageId">
            <time :datetime="item.sentAt">{{ formatter.format(new Date(item.sentAt)) }}</time>
            <span class="line"><strong>{{ item.senderName }}</strong>：{{ item.content }}</span>
          </li>
        </ol>
      </div>
      <button v-if="hasNewMessages" class="ui-button latest" type="button" @click="jumpToLatest">查看新消息</button>
    </div>

    <footer class="composer">
      <form class="compose-form" :aria-busy="submitting" @submit.prevent="send">
        <input v-model="content" class="ui-input" aria-label="弹幕内容" placeholder="发送一条弹幕"
          :disabled="state !== 'ready'" autocomplete="off" @keydown.enter="handleEnter" />
        <button class="ui-button ui-button--primary" type="submit" :disabled="!canSend">
          {{ submitting ? '发送中…' : '发送' }}
        </button>
      </form>
      <p v-if="cooldownSeconds > 0" class="feedback">请等待 {{ cooldownSeconds }} 秒后发送下一条弹幕。</p>
      <p v-if="feedback" class="feedback" :class="{ failure: failed }" :role="failed ? 'alert' : 'status'">{{ feedback }}</p>
      <div v-if="pendingAction !== 'none'" class="submission-actions">
        <button v-if="pendingAction === 'retry'" type="button" class="ui-button reconnect"
          :disabled="state !== 'ready' || submitting" @click="submitPending">重试原弹幕</button>
        <button v-else type="button" class="ui-button reconnect" :disabled="checkingReceipt"
          @click="() => checkReceipt()">查询回执</button>
        <button type="button" class="ui-button reconnect" @click="clearPending">发送新内容</button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.room-messages {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-width: 0;
  height: clamp(24rem, 43vw, 43rem);
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0.85rem;
}

.panel-heading {
  display: grid;
  justify-items: start;
  gap: 0.4rem;
  padding: 0.9rem 1rem;
  border-bottom: 1px solid var(--line);
}
.panel-title {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem;
}

h2 { margin: 0; font-size: 1rem; }
.connection { display: flex; align-items: center; gap: 0.4rem; margin: 0; color: var(--muted); font-size: 0.76rem; }
.connection span { width: 0.45rem; height: 0.45rem; border-radius: 50%; background: currentColor; }
.connection[data-state="ready"] { color: var(--room-highlight); }
.reconnect { min-height: 1.95rem; font-size: 0.8rem; }

.message-area { position: relative; min-height: 0; }
.message-log { height: 100%; overflow-y: auto; overflow-anchor: none; overscroll-behavior: contain; padding: 0.65rem 0.9rem; }
.message-log:focus-visible { outline-offset: -3px; }
.message-log ol { list-style: none; padding: 0; margin: 0; }
.message-log li { display: grid; grid-template-columns: 8ch minmax(0, 1fr); gap: 0.4rem; padding: 0.35rem 0; font-size: 0.875rem; line-height: 1.5; }
.message-log time { color: var(--muted); font-variant-numeric: tabular-nums; white-space: nowrap; }
.line { overflow-wrap: anywhere; white-space: pre-wrap; }
.line strong { color: var(--room-highlight); }
.latest { position: absolute; left: 50%; bottom: 0.6rem; translate: -50%; color: var(--room-highlight); }
.empty { margin-block: 1.2rem; color: var(--muted); text-align: center; font-size: 0.85rem; }

.composer { display: grid; gap: 0.5rem; padding: 0.8rem; border-top: 1px solid var(--line); }
.compose-form { display: flex; align-items: center; gap: 0.45rem; }
.compose-form .ui-input { flex: 1; min-width: 0; }
.compose-form .ui-input:disabled { background: var(--tint); }
.compose-form .ui-button { flex-shrink: 0; }
.feedback { margin: 0; color: var(--muted); font-size: 0.78rem; }
.feedback.failure { color: var(--danger); }
.submission-actions { display: flex; flex-wrap: wrap; gap: 0.35rem; }

@media (max-width: 50rem) {
  .room-messages { height: min(29rem, max(22rem, 60svh)); }
}
</style>
