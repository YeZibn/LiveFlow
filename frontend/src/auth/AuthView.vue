<script setup lang="ts">
import { nextTick, ref } from 'vue'
import ProfileView from '../profile/ProfileView.vue'
import RoomView from '../room/RoomView.vue'
import RegisterForm from './RegisterForm.vue'
import { useAuth } from './useAuth'

defineProps<{ roomId?: string }>()

const username = ref('')
const password = ref('')
const registering = ref(false)
const notice = ref('')
const { loggedIn, restoring, busy, error, restoreFailed, sessionId, restore, login, logout, withAccess } = useAuth()

/** 提交输入并立即清除组件中的明文密码。 */
async function submit() {
  notice.value = ''
  const result = login(username.value, password.value)
  password.value = ''
  await result
}

/**
 * 切换认证表单并清理上一份表单的密码和错误。
 * @param next 是否打开注册表单
 */
function showRegistration(next: boolean) {
  password.value = ''
  error.value = ''
  notice.value = ''
  registering.value = next
  // 表单切换会移除原按钮，将焦点交给新表单标题。
  void nextTick(() => document.getElementById('auth-title')?.focus())
}

/**
 * 注册完成后保留服务端返回的准确账号拼写。
 * @param registeredUsername 新建账号的原样用户名
 */
function completeRegistration(registeredUsername: string) {
  showRegistration(false)
  username.value = registeredUsername
  notice.value = '注册成功，请登录。'
}
</script>

<template>
  <main v-if="!restoring && loggedIn" :class="roomId === undefined ? 'profile-main' : undefined">
    <RoomView v-if="roomId !== undefined" :key="`${sessionId}:${roomId}`" :room-id="roomId" :with-access="withAccess" />
    <ProfileView v-else :key="sessionId" :with-access="withAccess"
      :auth-busy="busy" :auth-error="error" @logout="logout" />
  </main>

  <main v-else class="auth-layout">
    <aside class="brand-panel" aria-label="LiveFlow 实时弹幕平台">
      <div class="signal-grid" aria-hidden="true">
        <div class="signal-orbit"></div>
        <span class="signal signal--first"><i></i></span>
        <span class="signal signal--second"><i></i></span>
        <span class="signal signal--third"><i></i></span>
        <span class="signal signal--fourth"><i></i></span>
      </div>
      <p>实时弹幕平台</p>
    </aside>

    <section class="auth-panel" aria-labelledby="auth-title" :aria-busy="busy || restoring">
      <div v-if="restoring" class="auth-intro">
        <h1 id="auth-title">正在恢复登录</h1>
        <p role="status">正在检查当前会话…</p>
      </div>
      <RegisterForm v-else-if="registering" @registered="completeRegistration" @back="showRegistration(false)" />
      <template v-else>
        <header class="auth-intro">
          <h1 id="auth-title" tabindex="-1">登录 LiveFlow</h1>
        </header>
        <form class="ui-form" @submit.prevent="submit">
          <p v-if="notice" class="ui-feedback" role="status">{{ notice }}</p>
          <div class="ui-field">
            <label for="username">用户名</label>
            <input id="username" v-model="username" class="ui-input" autocomplete="username" required
              autocapitalize="none" spellcheck="false" minlength="3" maxlength="32"
              pattern="[A-Za-z0-9][A-Za-z0-9_]{2,31}" :disabled="busy" />
          </div>
          <div class="ui-field">
            <label for="password">密码</label>
            <input id="password" v-model="password" class="ui-input" type="password" autocomplete="current-password"
              required :disabled="busy" />
          </div>
          <p v-if="error" class="ui-feedback ui-feedback--error" role="alert">{{ error }}</p>
          <button class="ui-button ui-button--primary auth-submit" :disabled="busy" type="submit">
            {{ busy ? '正在登录…' : '登录' }}
          </button>
        </form>
        <p class="auth-switch">
          还没有账号？
          <button class="ui-link-button" :disabled="busy" type="button" @click="showRegistration(true)">注册</button>
        </p>
        <button v-if="restoreFailed" class="ui-button" type="button" :disabled="busy" @click="restore">
          重试恢复登录
        </button>
      </template>
    </section>
  </main>
</template>

<style scoped>
.auth-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: stretch;
  min-height: calc(100svh - 4.75rem);
  padding: 0 clamp(1rem, 2.5vw, 2.5rem) clamp(1rem, 2.5vw, 2.5rem);
}

.brand-panel {
  position: relative;
  display: grid;
  align-content: end;
  isolation: isolate;
  overflow: hidden;
  min-height: 32rem;
  padding: clamp(1.5rem, 5vw, 5rem);
  border-radius: 1.8rem;
  color: #effaf1;
  background: #132e26;
}

.brand-panel p {
  z-index: 1;
  margin: 0;
  font-size: clamp(1.6rem, 2.6vw, 2.8rem);
  font-weight: 650;
  letter-spacing: 0.04em;
}

.signal-grid {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background:
    linear-gradient(0deg, #132e26 2%, transparent 38%),
    radial-gradient(ellipse at 73% 45%, #3b7258, transparent 65%),
    repeating-linear-gradient(0deg, transparent 0 3.25rem, #a7e7b619 3.25rem calc(3.25rem + 1px));
}

.signal-orbit {
  position: absolute;
  inset: 2% -20% 22% 23%;
  border: 1px solid #c2efd34d;
  border-radius: 50%;
  box-shadow: 0 0 0 2.8rem #b2eac01c, 0 0 0 6rem #b2eac00f;
  rotate: -28deg;
}

.signal {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 0.65rem;
  width: var(--length);
  height: clamp(1.8rem, 4vw, 3.5rem);
  padding: 0 0.75rem;
  border: 1px solid #c0e9d65c;
  border-radius: 2rem;
  color: #cdedd5;
  background: #305345;
  box-shadow: 0 0.6rem 2rem #102b2260;
}

.signal i { flex: none; width: 0.5rem; height: 0.5rem; border-radius: 50%; background: currentColor; }
.signal::after { content: ''; width: 60%; height: 2px; border-radius: 2px; background: currentColor; opacity: 0.55; }
.signal--first { top: 20%; left: 10%; --length: 32%; }
.signal--second { top: 34%; left: 35%; --length: 61%; color: #183a2e; background: #bfe8c5; }
.signal--third { top: 49%; left: 17%; --length: 48%; background: #416d57; }
.signal--fourth { top: 63%; left: 53%; --length: 29%; }

.auth-panel {
  align-self: center;
  justify-self: center;
  width: min(100%, 25rem);
  padding: 2.5rem 0;
  margin: 0 auto;
}

.auth-panel :deep(.auth-intro) { margin-bottom: 1.65rem; }
.auth-panel :deep(.auth-intro) h1 { margin: 0; font-size: clamp(1.9rem, 3vw, 2.65rem); line-height: 1.2; letter-spacing: -0.045em; }
.auth-panel :deep(.auth-intro) p { margin: 0.75rem 0 0; color: var(--muted); }
.auth-panel :deep(.auth-submit) { min-height: 3.125rem; margin-top: 0.2rem; }
.auth-panel :deep(.auth-switch) { margin: 0.65rem 0 0; color: var(--muted); font-size: 0.9rem; text-align: center; }
.profile-main { padding: clamp(1.25rem, 5vw, 4rem) 1rem 3rem; }

@media (min-width: 761px) {
  .auth-panel { width: min(calc(100% - 4rem), 25rem); }
}

@media (max-width: 760px) {
  .auth-layout { grid-template-columns: minmax(0, 1fr); align-content: start; }
  .brand-panel { display: none; }
  .auth-panel { padding: clamp(2rem, 9vh, 5rem) 0 2rem; }
}
</style>
