<script setup>
import {useRouter} from 'vue-router'
import {api, session} from './api.js'

const router = useRouter()

async function logout() {
  try {
    await api.post('/auth/logout')
  } finally {
    session.token = '';
    session.role = '';
    session.username = '';
    router.push('/login')
  }
}
</script>
<template>
  <header class="site-header">
    <div class="header-inner">
      <div><strong>口腔医院预约挂号</strong><small>阶段一</small></div>
      <nav aria-label="主导航">
        <ul>
          <li v-if="session.role === 'PATIENT'">
            <RouterLink to="/patient">患者服务</RouterLink>
          </li>
          <li v-if="session.role === 'DOCTOR'">
            <RouterLink to="/doctor">医生工作台</RouterLink>
          </li>
          <li v-if="session.role === 'ADMIN'">
            <RouterLink to="/admin">管理工作台</RouterLink>
          </li>
        </ul>
      </nav>
      <div v-if="session.token" class="account"><span>{{ session.username }} · {{ session.role }}</span>
        <button type="button" class="quiet" @click="logout">退出</button>
      </div>
    </div>
  </header>
  <main>
    <RouterView/>
  </main>
</template>
