<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, session } from '../api.js'
const router = useRouter()
const login = reactive({ identifier: '', password: '' })
const registration = reactive({ username: '', phone: '', name: '', password: '' })
const mode = ref('login'), busy = ref(false), message = ref(''), failed = ref(false)
async function submitLogin() {
  busy.value = true; failed.value = false
  try {
    const result = await api.post('/auth/login', login)
    session.token = result.accessToken; session.role = result.role; session.username = login.identifier; session.mustChangePassword = result.mustChangePassword
    router.push(result.mustChangePassword ? '/change-password' : `/${result.role.toLowerCase()}`)
  } catch (e) { message.value = e.message; failed.value = true } finally { busy.value = false }
}
async function submitRegistration() {
  busy.value = true; failed.value = false
  try { await api.post('/auth/register', registration); message.value = '注册成功，请登录'; mode.value = 'login'; login.identifier = registration.username; login.password = '' }
  catch (e) { message.value = e.message; failed.value = true } finally { busy.value = false }
}
</script>
<template>
  <div class="panel login-panel"><h1>欢迎使用预约挂号系统</h1><p class="muted">请选择账号入口。医生和管理员账号由管理员创建。</p>
    <div class="row"><button type="button" class="quiet switch" :aria-pressed="mode === 'login'" @click="mode='login'">登录</button><button type="button" class="quiet switch" :aria-pressed="mode === 'register'" @click="mode='register'">患者注册</button></div>
    <form v-if="mode === 'login'" aria-labelledby="login-title" action="/hospital/api/auth/login" method="post" @submit.prevent="submitLogin"><h2 id="login-title">账号登录</h2><label for="identifier">用户名或手机号</label><input id="identifier" v-model.trim="login.identifier" name="identifier" autocomplete="username" required><label for="password">密码</label><input id="password" v-model="login.password" name="password" type="password" autocomplete="current-password" required><button class="primary" type="submit" :disabled="busy">登录</button></form>
    <form v-else aria-labelledby="register-title" action="/hospital/api/auth/register" method="post" @submit.prevent="submitRegistration"><h2 id="register-title">患者注册</h2><label for="reg-user">用户名（4–32 位字母、数字或下划线）</label><input id="reg-user" v-model.trim="registration.username" name="username" pattern="[A-Za-z0-9_]{4,32}" autocomplete="username" required><label for="reg-phone">手机号</label><input id="reg-phone" v-model.trim="registration.phone" name="phone" type="tel" inputmode="tel" pattern="1[0-9]{10}" autocomplete="tel" required><label for="reg-name">姓名</label><input id="reg-name" v-model.trim="registration.name" name="name" autocomplete="name" maxlength="30" required><label for="reg-pwd">密码（8–64 位）</label><input id="reg-pwd" v-model="registration.password" name="password" type="password" autocomplete="new-password" minlength="8" maxlength="64" required><button class="primary" type="submit" :disabled="busy">注册患者账号</button></form>
    <p class="status" :class="{error:failed}" role="status" aria-live="polite">{{ message }}</p>
  </div>
</template>
<style scoped>.login-panel{max-width:520px;margin:3rem auto}.login-panel form{display:grid;gap:.3rem}.login-panel form button{margin-top:1rem}.switch[aria-pressed="true"]{background:#075a6b;color:white}</style>
