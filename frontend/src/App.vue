<script setup lang="ts">
import AuthView from './auth/AuthView.vue'
import DevelopmentCheck from './DevelopmentCheck.vue'

const pathname = window.location.pathname
const showDevelopmentCheck = pathname === '/dev'
// 房间地址由浏览器保留，登录和刷新都使用同一个目标。
const roomId = pathname.startsWith('/rooms/') ? pathname.slice('/rooms/'.length) : undefined
const showDevelopmentLink = import.meta.env.DEV && !showDevelopmentCheck
</script>

<template>
  <header class="site-header">
    <a class="wordmark" href="/" aria-label="LiveFlow 首页">
      <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>
      LiveFlow
    </a>
    <a v-if="showDevelopmentLink" class="ui-button" href="/dev">开发检查</a>
    <a v-else-if="showDevelopmentCheck" class="ui-button" href="/">返回首页</a>
  </header>
  <DevelopmentCheck v-if="showDevelopmentCheck" />
  <AuthView v-else :room-id="roomId" />
</template>

<style scoped>
.site-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  min-height: 4.75rem;
  padding: 0.75rem clamp(1rem, 3.5vw, 3.5rem);
}

.wordmark {
  display: inline-flex;
  align-items: center;
  gap: 0.65rem;
  color: var(--text);
  font-size: 1.25rem;
  font-weight: 750;
  letter-spacing: -0.045em;
  text-decoration: none;
}

.brand-mark {
  display: grid;
  align-content: center;
  justify-items: center;
  gap: 0.18rem;
  inline-size: 2.15rem;
  block-size: 2.15rem;
  border-radius: 0.7rem;
  background: var(--accent);
}

.brand-mark i { width: 0.8rem; height: 0.13rem; border-radius: 1rem; background: #dcf1db; }
.brand-mark i:first-child { translate: -0.12rem 0; }
.brand-mark i:last-child { translate: 0.12rem 0; }
</style>
