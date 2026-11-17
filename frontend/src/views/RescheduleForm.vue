<script setup>
import {onMounted, ref, useId} from 'vue'
import {api, key} from '../api.js'

const props = defineProps({
  appointmentId: {type: [Number, String], required: true},
  disruptionId: {type: [Number, String], default: null},
  admin: Boolean
})
const emit = defineEmits(['saved', 'close'])
const formId = useId()
const path = `${props.admin ? '/admin' : ''}/appointments/${props.appointmentId}/reschedule`
const options = ref([])
const selected = ref('')
const reason = ref('')
const busy = ref(false)
const message = ref('')
const failed = ref(false)
let attempt

function localTime(value) {
  const utc = /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : value + 'Z'
  return new Date(utc).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai', hour12: false})
}

async function load() {
  busy.value = true
  failed.value = false
  message.value = '正在查询可改约时段…'
  try {
    options.value = (await api.get(path + '-options')).items
    message.value = options.value.length ? '' : '暂无可改约的同价时段。'
  } catch (error) {
    failed.value = true
    message.value = error.message
  } finally {
    busy.value = false
  }
}

async function submit() {
  const payload = {targetSlotId: String(selected.value), reason: reason.value}
  if (props.disruptionId !== null) payload.disruptionId = String(props.disruptionId)
  const signature = JSON.stringify(payload)
  if (attempt?.signature !== signature) attempt = {signature, key: key()}
  busy.value = true
  failed.value = false
  try {
    await api.put(path, payload, {headers: {'Idempotency-Key': attempt.key}})
    emit('saved')
  } catch (error) {
    failed.value = true
    message.value = error.message
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <form :aria-labelledby="formId + '-heading'" :action="'/hospital/api' + path" method="post" @submit.prevent="submit">
    <h3 :id="formId + '-heading'">选择新的就诊时段</h3>
    <p class="status" :class="{error: failed}" role="status">{{ message }}</p>
    <template v-if="options.length">
      <label :for="formId + '-slot'">同价时段（必填）</label>
      <select :id="formId + '-slot'" v-model="selected" name="targetSlotId" required :disabled="busy">
        <option value="" disabled>请选择时段</option>
        <option v-for="slot in options" :key="slot.slot_id" :value="slot.slot_id">
          {{ localTime(slot.starts_at) }} · {{ slot.department_name }} · {{ slot.doctor_name }} · {{ slot.room_name }}
        </option>
      </select>
      <template v-if="admin">
        <label :for="formId + '-reason'">改约原因（必填）</label>
        <textarea :id="formId + '-reason'" v-model.trim="reason" name="reason" maxlength="200" required :disabled="busy"/>
      </template>
      <button class="primary" type="submit" :disabled="busy">确认改约</button>
    </template>
    <button v-if="failed || !options.length" class="quiet" type="button" :disabled="busy" @click="load">刷新时段</button>
    <button class="quiet" type="button" :disabled="busy" @click="emit('close')">关闭改约</button>
  </form>
</template>

<style scoped>
select:user-invalid {
  border-color: #b3261e;
}
</style>
