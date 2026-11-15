<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, session } from '../api.js'
const router=useRouter(),form=reactive({oldPassword:'',newPassword:''}),busy=ref(false),message=ref('')
async function submit(){busy.value=true;try{await api.put('/auth/password',form);session.token='';session.role='';session.mustChangePassword=false;router.push('/login')}catch(e){message.value=e.message}finally{busy.value=false}}
</script>
<template><section class="panel login-panel"><h1>修改初始密码</h1><p>首次登录须设置新密码。修改后请重新登录。</p><form aria-labelledby="password-heading" action="/hospital/api/auth/password" method="post" @submit.prevent="submit"><h2 id="password-heading">账号密码</h2><label for="old-password">当前密码</label><input id="old-password" v-model="form.oldPassword" name="oldPassword" type="password" autocomplete="current-password" required><label for="new-password">新密码（8–64 位）</label><input id="new-password" v-model="form.newPassword" name="newPassword" type="password" autocomplete="new-password" minlength="8" maxlength="64" required><button class="primary" type="submit" :disabled="busy">保存新密码</button></form><p class="status error" role="status" aria-live="polite">{{ message }}</p></section></template>
<style scoped>.login-panel{max-width:520px;margin:3rem auto}.login-panel form{display:grid;gap:.3rem}.login-panel button{margin-top:1rem}</style>
