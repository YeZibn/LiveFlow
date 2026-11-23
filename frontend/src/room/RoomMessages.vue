<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRoomMessages } from './useRoomMessages'

const props = defineProps<{
  roomId: string
  withAccess: <T>(call: (access: string) => Promise<T>) => Promise<T>
}>()

const { state, connectionError, messages, content, submitting, canSend, feedback, failed, reconnect, send } =
  useRoomMessages(props.roomId, props.withAccess)
const log = ref<HTMLElement>()
const formatter = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' })
const stateName = { connecting: '连接中', ready: '已连接', reconnecting: '正在重连', offline: '连接已断开' }

watch(messages, async () => {
  const node = log.value
  if (!node || node.scrollHeight - node.clientHeight - node.scrollTop > 36) return
  // 用户停在底部附近时跟随，向上阅读时保留当前位置。
  await nextTick()
  if (log.value === node) node.scrollTop = node.scrollHeight
})

/** @param event 回车键事件，用于区分候选词确认和发送 */
function handleEnter(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  void send()
}
</script>

<template>
  <section class="room-messages" aria-labelledby="room-messages-title">
    <header class="panel-heading">
      <h2 id="room-messages-title">弹幕</h2>
      <p class="connection" :data-state="state" role="status">
        <span aria-hidden="true"></span>{{ stateName[state] }}
      </p>
    </header>

    <div ref="log" class="message-log" role="log" aria-label="直播间弹幕">
      <p v-if="messages.length === 0" class="empty">
        {{ state === 'ready' ? '还没有弹幕' : '连接后即可接收弹幕' }}
      </p>
      <ol v-else>
        <li v-for="item in messages" :key="item.messageId">
          <time :datetime="item.sentAt">{{ formatter.format(new Date(item.sentAt)) }}</time>
          <span>{{ item.content }}</span>
        </li>
      </ol>
    </div>

    <footer class="composer">
      <p v-if="connectionError" class="feedback" role="status">{{ connectionError }}</p>
      <button v-if="state === 'offline'" class="ui-button" type="button" @click="reconnect">重新连接</button>
      <form class="compose-form" :aria-busy="submitting" @submit.prevent="send">
        <input v-model="content" class="ui-input" aria-label="弹幕内容" placeholder="发送一条弹幕"
          :disabled="state !== 'ready' || submitting" autocomplete="off" @keydown.enter="handleEnter" />
        <button class="ui-button ui-button--primary" type="submit" :disabled="!canSend">
          {{ submitting ? '发送中…' : '发送' }}
        </button>
      </form>
      <p v-if="feedback" class="feedback" :class="{ failure: failed }" :role="failed ? 'alert' : 'status'">{{ feedback }}</p>
    </footer>
  </section>
</template>

<style scoped>
.room-messages {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-width: 0;
  min-height: 19rem;
  max-height: clamp(23rem, 50vw, 43rem);
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 1.15rem;
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem;
  padding: 0.9rem 1rem;
  border-bottom: 1px solid var(--line);
}

h2 { margin: 0; font-size: 1rem; }
.connection { display: flex; align-items: center; gap: 0.4rem; margin: 0; color: var(--muted); font-size: 0.76rem; }
.connection span { width: 0.45rem; height: 0.45rem; border-radius: 50%; background: currentColor; }
.connection[data-state="ready"] { color: var(--accent); }

.message-log { overflow-y: auto; overscroll-behavior: contain; padding: 0.7rem 1rem; }
.message-log ol { list-style: none; padding: 0; margin: 0; }
.message-log li { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 0.6rem; padding: 0.4rem 0; font-size: 0.88rem; }
.message-log time { color: var(--muted); font-size: 0.75rem; padding-top: 0.1rem; }
.message-log li span { overflow-wrap: anywhere; white-space: pre-wrap; }
.empty { margin-block: 1.2rem; color: var(--muted); text-align: center; font-size: 0.85rem; }

.composer { display: grid; gap: 0.5rem; padding: 0.8rem; border-top: 1px solid var(--line); }
.compose-form { display: flex; align-items: center; gap: 0.45rem; }
.compose-form .ui-input { flex: 1; min-width: 0; }
.compose-form .ui-button { flex-shrink: 0; }
.feedback { margin: 0; color: var(--muted); font-size: 0.78rem; }
.feedback.failure { color: var(--danger); }

@media (max-width: 760px) {
  .room-messages { height: 21rem; }
}
</style>
