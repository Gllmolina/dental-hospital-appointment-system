<script setup>
import {onMounted, reactive, ref} from 'vue'
import {api} from '../api.js'

const props = defineProps({admin: {type: Boolean, default: false}})
const items = ref([]), form = reactive({startsAt: '', endsAt: '', reason: ''})
const notes = ref({}), busy = ref(false), message = ref(''), failed = ref(false)

async function refresh() {
  try {
    items.value = (await api.get(props.admin ? '/admin/leave-requests' : '/doctor/leave-requests')).items
  } catch (error) {
    failed.value = true
    message.value = error.message
  }
}

async function run(task) {
  busy.value = true
  failed.value = false
  try {
    await task()
    message.value = '操作成功'
    await refresh()
  } catch (error) {
    failed.value = true
    message.value = error.message
  } finally {
    busy.value = false
  }
}

function submit() {
  if (!form.startsAt || !form.endsAt) return
  run(() => api.post('/doctor/leave-requests', {
    startsAt: new Date(form.startsAt).toISOString(), endsAt: new Date(form.endsAt).toISOString(), reason: form.reason
  }))
}

function decide(item, decision) {
  run(() => api.put(`/admin/leave-requests/${item.id}/decision`, {decision, reviewNote: notes.value[item.id] || ''}))
}

const time = value => value ? new Date(value.endsWith('Z') ? value : value + 'Z').toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai', hour12: false}) : ''
onMounted(refresh)
</script>
<template>
  <section class="panel">
    <h2>{{ admin ? '请假审核' : '请假申请' }}</h2>
    <p class="status" :class="{error:failed}" role="status" aria-live="polite">{{ message }}</p>
    <form v-if="!admin" action="/hospital/api/doctor/leave-requests" method="post" aria-labelledby="leave-form-title" @submit.prevent="submit">
      <h3 id="leave-form-title">新建申请</h3>
      <div class="grid"><div><label for="leave-start">开始时间</label><input id="leave-start" v-model="form.startsAt" name="startsAt" type="datetime-local" required></div>
        <div><label for="leave-end">结束时间</label><input id="leave-end" v-model="form.endsAt" name="endsAt" type="datetime-local" required></div></div>
      <label for="leave-reason">原因</label><textarea id="leave-reason" v-model.trim="form.reason" name="reason" maxlength="500" required></textarea>
      <button class="primary" type="submit" :disabled="busy">提交请假申请</button>
    </form>
    <p v-if="!items.length">暂无申请。</p>
    <article v-for="item in items" :key="item.id" class="record">
      <strong>{{ item.doctor_name || '我的申请' }} · {{ item.status }}</strong>
      <small>{{ time(item.starts_at) }} 至 {{ time(item.ends_at) }}</small>
      <p>原因：{{ item.reason }}</p><p v-if="item.review_note">审核说明：{{ item.review_note }}</p>
      <template v-if="admin && item.status === 'PENDING'">
        <label :for="`leave-note-${item.id}`">审核说明（拒绝时必填）</label>
        <textarea :id="`leave-note-${item.id}`" v-model.trim="notes[item.id]" name="reviewNote" maxlength="500"></textarea>
        <div class="row"><button type="button" class="primary" :disabled="busy" @click="decide(item, 'APPROVED')">批准</button>
          <button type="button" class="quiet" :disabled="busy || !notes[item.id]" @click="decide(item, 'REJECTED')">拒绝</button></div>
      </template>
    </article>
  </section>
</template>
