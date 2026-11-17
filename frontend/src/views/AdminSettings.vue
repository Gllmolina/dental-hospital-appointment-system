<script setup>
import {onMounted, ref} from 'vue'
import {api} from '../api.js'

const items = ref([]), edits = ref({}), reasons = ref({})
const busy = ref(false), message = ref(''), failed = ref(false)

async function refresh() {
  try {
    items.value = (await api.get('/admin/settings')).items
    for (const item of items.value) edits.value[item.config_key] = Number(item.config_value)
  } catch (error) {
    failed.value = true
    message.value = error.message
  }
}

async function save(item) {
  busy.value = true
  failed.value = false
  try {
    await api.put(`/admin/settings/${item.config_key}`, {
      value: edits.value[item.config_key], version: item.version, reason: reasons.value[item.config_key]
    })
    message.value = '配置已生效'
    reasons.value[item.config_key] = ''
    await refresh()
  } catch (error) {
    failed.value = true
    message.value = error.message
    if (error.response?.status === 409) await refresh()
  } finally {
    busy.value = false
  }
}

onMounted(refresh)
</script>
<template>
  <section class="panel">
    <h2>业务参数</h2>
    <p class="status" :class="{error:failed}" role="status" aria-live="polite">{{ message }}</p>
    <p>修改后只影响新创建的预约及之后发布的排班；历史时段和截止时间保留原快照。</p>
    <form v-for="item in items" :key="item.config_key" :aria-labelledby="`setting-${item.config_key}`"
          :action="`/hospital/api/admin/settings/${item.config_key}`" method="post" class="record"
          @submit.prevent="save(item)">
      <h3 :id="`setting-${item.config_key}`">{{ item.config_key }} · 版本 {{ item.version }}</h3>
      <div class="grid"><div><label :for="`value-${item.config_key}`">数值</label>
        <input :id="`value-${item.config_key}`" v-model.number="edits[item.config_key]" name="value" type="number" min="0" required></div>
        <div><label :for="`reason-${item.config_key}`">修改原因</label>
          <input :id="`reason-${item.config_key}`" v-model.trim="reasons[item.config_key]" name="reason" maxlength="100" required></div></div>
      <button class="primary" type="submit" :disabled="busy">保存参数</button>
    </form>
  </section>
</template>
