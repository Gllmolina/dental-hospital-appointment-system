<script setup>
import {onMounted, ref} from 'vue'
import {api} from '../api.js'

const items = ref([]), status = ref(''), reply = ref({}), busy = ref(false), message = ref(''), failed = ref(false)

async function refresh() {
  try {
    items.value = (await api.get('/admin/feedback', {params: status.value ? {status: status.value} : {}})).items
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
    message.value = '处理成功'
    await refresh()
  } catch (error) {
    failed.value = true
    message.value = error.message
  } finally {
    busy.value = false
  }
}

onMounted(refresh)
</script>
<template>
  <section class="panel">
    <h2>患者反馈</h2>
    <p class="status" :class="{error:failed}" role="status" aria-live="polite">{{ message }}</p>
    <label for="feedback-status">状态</label>
    <select id="feedback-status" v-model="status" name="status" @change="refresh">
      <option value="">全部</option><option value="PENDING">待处理</option>
      <option value="REPLIED">已回复</option><option value="CLOSED">已关闭</option>
    </select>
    <p v-if="!items.length">暂无反馈。</p>
    <article v-for="item in items" :key="item.id" class="record">
      <strong>#{{ item.id }} · {{ item.type }} · {{ item.status }}</strong>
      <p>{{ item.content }}</p><p v-if="item.reply">回复：{{ item.reply }}</p>
      <form v-if="item.status !== 'CLOSED'" :aria-labelledby="`reply-title-${item.id}`"
            :action="`/hospital/api/admin/feedback/${item.id}/reply`" method="post"
            @submit.prevent="run(() => api.put(`/admin/feedback/${item.id}/reply`, {reply: reply[item.id]}))">
        <h3 :id="`reply-title-${item.id}`">回复反馈 #{{ item.id }}</h3>
        <label :for="`reply-${item.id}`">回复内容</label>
        <textarea :id="`reply-${item.id}`" v-model.trim="reply[item.id]" name="reply" maxlength="1000" required></textarea>
        <button class="primary" type="submit" :disabled="busy">发送回复</button>
      </form>
      <button v-if="item.status === 'REPLIED'" class="quiet" type="button" :disabled="busy"
              @click="run(() => api.put(`/admin/feedback/${item.id}/close`))">关闭反馈</button>
    </article>
  </section>
</template>
