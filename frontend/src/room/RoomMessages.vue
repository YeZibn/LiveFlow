<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { WithAccess } from '../auth/useAuth'
import { useRoomMessages } from './useRoomMessages'

const props = defineProps<{
  roomId: string
  withAccess: WithAccess
}>()

const { state, connectionError, messages, content, submitting, canSend, feedback, failed, reconnect, send,
  syncing, syncFeedback, cooldownSeconds, showSubmissionActions, pendingContent, canCheckReceipt,
  checkingReceipt, checkReceipt, dismissSubmission, isReading, olderStatus, hasNewMessages,
  canFollowFromBottom, beginReading, endReading, loadOlder } =
  useRoomMessages(props.roomId, props.withAccess)
const log = ref<HTMLElement>()
const LOAD_EARLIER_DISTANCE = 48
let lastScrollTop = 0
const formatter = new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})
const stateName = { connecting: '连接中', ready: '已连接', reconnecting: '正在重连', offline: '连接已断开' }

// 先读取更新前的可见项位置，插入上页后再恢复同一个阅读锚点。
watch(messages, async updated => {
  const node = log.value
  if (!node) return
  const anchors = isReading.value ? visibleAnchors(node) : []
  await nextTick()
  if (log.value !== node || messages.value !== updated) return
  if (!isReading.value) {
    scrollTo(node, node.scrollHeight)
    return
  }

  const rendered = Array.from(node.querySelectorAll<HTMLElement>('li[data-message-id]'))
  for (const anchor of anchors) {
    // 持久记录纠正消息 ID 时，优先把同一序号留在原位，其次保留相邻可见项。
    const row = rendered.find(item => item.dataset.messageId === anchor.id)
      ?? rendered.find(item => item.dataset.roomSeq === anchor.sequence)
    if (row) {
      const offset = row.getBoundingClientRect().top - node.getBoundingClientRect().top
      scrollTo(node, node.scrollTop + offset - anchor.offset)
      return
    }
  }
  lastScrollTop = node.scrollTop
})

/**
 * @param node 独立滚动的弹幕容器
 * @returns 按展示顺序记录的可见消息和它们相对容器的顶部偏移
 */
function visibleAnchors(node: HTMLElement) {
  const { top, bottom } = node.getBoundingClientRect()
  const anchors = []
  for (const row of Array.from(node.querySelectorAll<HTMLElement>('li[data-message-id]'))) {
    const bounds = row.getBoundingClientRect()
    if (bounds.top >= bottom) break
    if (bounds.bottom > top) {
      anchors.push({ id: row.dataset.messageId, sequence: row.dataset.roomSeq, offset: bounds.top - top })
    }
  }
  return anchors
}

/**
 * 写入程序滚动位置并同步比较基准，后续 scroll 事件不会被视为用户上翻。
 * @param node 弹幕滚动容器
 * @param top 目标位置，浏览器会将其限制在可滚动范围内
 */
function scrollTo(node: HTMLElement, top: number) {
  node.scrollTop = top
  lastScrollTop = node.scrollTop
}

/**
 * 判断阅读位置是否在列表底部附近，容许行高和像素取整带来的少量偏差。
 * @param node 当前独立滚动的消息列表
 * @returns 距底部不超过 24 像素时为 true
 */
function isNearBottom(node: HTMLElement) {
  return node.scrollHeight - node.clientHeight - node.scrollTop <= 24
}

/** 只在用户向上移动并接近顶部时自动加载。失败后的重试由按钮触发。 */
function onScroll() {
  const node = log.value
  if (!node || node.scrollTop === lastScrollTop) return
  const movingUp = node.scrollTop < lastScrollTop
  lastScrollTop = node.scrollTop
  if (isNearBottom(node)) {
    if (canFollowFromBottom.value) endReading()
    return
  }
  beginReading()
  if (movingUp && node.scrollTop <= LOAD_EARLIER_DISTANCE && olderStatus.value === 'idle') void loadOlder()
}

/** 将列表恢复到跟随最新消息的位置。 */
async function jumpToLatest() {
  endReading()
  await nextTick()
  const node = log.value
  if (!node) return
  scrollTo(node, node.scrollHeight)
  node.focus({ preventScroll: true })
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
      <div ref="log" class="message-log" role="log" aria-label="直播间弹幕"
        :aria-live="isReading ? 'off' : 'polite'" tabindex="0" @scroll="onScroll">
        <p v-if="messages.length === 0" class="empty">
          {{ state === 'ready' ? '还没有弹幕' : '连接后即可接收弹幕' }}
        </p>
        <template v-else>
          <div class="older">
            <p v-if="olderStatus === 'end'" class="feedback" role="status">没有更早的弹幕</p>
            <button v-else type="button" class="ui-button reconnect" :disabled="olderStatus === 'loading'"
              @click="loadOlder">
              {{ olderStatus === 'loading' ? '正在加载…' : olderStatus === 'error' ? '重试加载更早弹幕' : '加载更早' }}
            </button>
          </div>
          <ol>
            <li v-for="item in messages" :key="item.messageId" :data-message-id="item.messageId"
              :data-room-seq="item.roomSeq">
              <time :datetime="item.sentAt">{{ formatter.format(new Date(item.sentAt)) }}</time>
              <span class="line"><strong>{{ item.senderName }}</strong>：{{ item.content }}</span>
            </li>
          </ol>
        </template>
      </div>
      <button v-if="isReading" class="ui-button latest" type="button" @click="jumpToLatest">
        {{ hasNewMessages ? '查看新消息' : '回到最新' }}
      </button>
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
      <p v-if="pendingContent" class="feedback submission-text">{{ pendingContent }}</p>
      <div v-if="showSubmissionActions" class="submission-actions">
        <button v-if="canCheckReceipt" type="button" class="ui-button reconnect" :disabled="checkingReceipt"
          @click="() => checkReceipt()">查询回执</button>
        <button type="button" class="ui-button reconnect" @click="dismissSubmission">发送新内容</button>
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
.older { display: grid; place-items: center; min-height: 2.4rem; padding-block: 0.2rem; }
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
.submission-text { overflow-wrap: anywhere; }
.submission-actions { display: flex; flex-wrap: wrap; gap: 0.35rem; }

@media (max-width: 50rem) {
  .room-messages { height: min(29rem, max(22rem, 60svh)); }
}
</style>
