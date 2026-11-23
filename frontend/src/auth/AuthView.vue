<script setup lang="ts">
import { ref } from 'vue'
import ProfileView from '../profile/ProfileView.vue'
import RegisterForm from './RegisterForm.vue'
import { useAuth } from './useAuth'

const username = ref('')
const password = ref('')
const registering = ref(false)
const notice = ref('')
const { loggedIn, restoring, busy, error, restoreFailed, sessionId, restore, login, logout, withAccess } = useAuth()
const showDev = import.meta.env.DEV

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
  <div class="auth-shell">
    <header class="topbar">
      <a class="wordmark" href="/" aria-label="LiveFlow 首页"><span aria-hidden="true"></span>LiveFlow</a>
      <a v-if="showDev" class="dev-link" href="/dev">开发检查</a>
    </header>

    <main class="auth-main">
      <ProfileView v-if="!restoring && loggedIn" :key="sessionId" :with-access="withAccess"
        :auth-busy="busy" :auth-error="error" @logout="logout" />
      <section v-else class="panel" aria-labelledby="auth-title" :aria-busy="busy || restoring">
        <template v-if="restoring">
          <h1 id="auth-title">正在恢复登录</h1>
          <p role="status">正在检查当前会话…</p>
        </template>
        <RegisterForm v-else-if="registering" @registered="completeRegistration" @back="showRegistration(false)" />
        <template v-else>
          <h1 id="auth-title">登录 LiveFlow</h1>
          <p v-if="notice" class="notice" role="status">{{ notice }}</p>
          <form @submit.prevent="submit">
            <div class="field">
              <label for="username">用户名</label>
              <input id="username" v-model="username" autocomplete="username" required
                autocapitalize="none" spellcheck="false" minlength="3" maxlength="32"
                pattern="[A-Za-z0-9][A-Za-z0-9_]{2,31}" :disabled="busy" />
            </div>
            <div class="field">
              <label for="password">密码</label>
              <input id="password" v-model="password" type="password" autocomplete="current-password"
                required :disabled="busy" />
            </div>
            <p v-if="error" class="error" role="alert">{{ error }}</p>
            <button class="primary" :disabled="busy" type="submit">{{ busy ? '正在登录…' : '登录' }}</button>
            <button class="secondary" :disabled="busy" type="button" @click="showRegistration(true)">注册</button>
            <button v-if="restoreFailed" class="secondary" type="button" :disabled="busy" @click="restore">
              重试恢复登录
            </button>
          </form>
        </template>
      </section>
    </main>
  </div>
</template>

<style scoped>
.auth-shell {
  min-height: 100vh;
  color: #152238;
  background: #f4f7fb;
  font-family: system-ui, sans-serif;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 4rem;
  padding: 0 clamp(1.25rem, 6vw, 4.5rem);
  background: #fff;
  border-bottom: 1px solid #dfe6ef;
}

.wordmark, .dev-link { text-decoration: none; }
.wordmark { display: flex; align-items: center; gap: 0.5rem; color: #162745; font-size: 1.2rem; font-weight: 750; }
.wordmark span { width: 0.8rem; height: 0.8rem; border-radius: 50%; background: #2464d1; }
.dev-link { color: #38577f; font-size: 0.9rem; }

.auth-main {
  display: grid;
  place-items: start center;
  padding: clamp(2.5rem, 12vh, 7rem) 1rem 3rem;
  margin: 0;
  max-width: none;
}

.panel {
  box-sizing: border-box;
  width: min(100%, 26rem);
  padding: clamp(1.4rem, 5vw, 2.4rem);
  background: #fff;
  border: 1px solid #dfe6ef;
  border-radius: 1rem;
}

h1 { margin: 0 0 1.6rem; font-size: 1.85rem; line-height: 1.25; overflow-wrap: anywhere; }
form, .field { display: grid; gap: 0.6rem; }
form { gap: 1.2rem; }
label { font-size: 0.9rem; font-weight: 650; }
input { box-sizing: border-box; width: 100%; padding: 0.68rem 0.75rem; border: 1px solid #b7c6d9; border-radius: 0.45rem; color: inherit; font: inherit; }
button { min-height: 2.65rem; padding: 0.55rem 0.85rem; font: inherit; font-weight: 650; border-radius: 0.45rem; cursor: pointer; }
.primary { background: #245cbd; border: 1px solid #245cbd; color: #fff; }
.secondary { background: #fff; border: 1px solid #b7c6d9; color: #264876; }
button:disabled { opacity: 0.65; cursor: wait; }
:focus-visible { outline: 2px solid #2464d1; outline-offset: 3px; }
.error { margin: 0; padding: 0.65rem; border-radius: 0.4rem; background: #fff1ef; color: #97281f; }
.notice { margin: 0 0 1rem; color: #245546; }
</style>
