<script setup>
import { onMounted, ref } from 'vue'
import { api, key } from '../api.js'
const tab=ref('booking'),message=ref(''),failed=ref(false),busy=ref(false)
const departments=ref([]),doctors=ref([]),slots=ref([]),appointments=ref([]),bills=ref([]),records=ref([])
const departmentId=ref(''),doctorId=ref(''),selectedSlot=ref(null),symptom=ref('')
let bookingKey=''
async function refresh(){try{const [d,doctorsResult,a,b,r]=await Promise.all([api.get('/departments'),api.get('/doctors',{params:departmentId.value?{departmentId:departmentId.value}:{}}),api.get('/patient/appointments'),api.get('/patient/bills'),api.get('/patient/medical-records')]);departments.value=d.items;doctors.value=doctorsResult.items;appointments.value=a.items;bills.value=b.items;records.value=r.items;if(doctorId.value)await loadSlots(doctorId.value)}catch(e){message.value=e.message;failed.value=true}}
async function loadDoctors(){doctorId.value='';slots.value=[];selectedSlot.value=null;await refresh()}
async function loadSlots(id){doctorId.value=id;const r=await api.get(`/doctors/${id}/slots`);slots.value=r.items;selectedSlot.value=null}
async function act(task){busy.value=true;failed.value=false;try{await task();message.value='操作成功';await refresh()}catch(e){message.value=e.message;failed.value=true}finally{busy.value=false}}
async function book(){if(!selectedSlot.value)return;bookingKey ||= key();await act(async()=>{await api.post('/appointments',{slotId:String(selectedSlot.value.slot_id),symptomSummary:symptom.value},{headers:{'Idempotency-Key':bookingKey}});bookingKey='';symptom.value='';tab.value='appointments'})}
async function pay(b){await act(()=>api.post(`/bills/${b.id}/mock-pay`,{}, {headers:{'Idempotency-Key':key()}}))}
async function cancel(a){if(!confirm('确定取消该预约吗？'))return;await act(()=>api.put(`/appointments/${a.id}/cancel`,{reasonCode:'PATIENT_REQUEST'}))}
const time=value=>value?new Date(value.endsWith('Z')?value:value+'Z').toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):''
onMounted(refresh)
</script>
<template>
  <h1>患者服务</h1><p class="status" :class="{error:failed}" role="status" aria-live="polite">{{ message }}</p>
  <el-tabs v-model="tab"><el-tab-pane label="查找号源" name="booking"><section class="panel"><h2>选择科室与医生</h2><label for="department-filter">科室</label><select id="department-filter" v-model="departmentId" name="departmentId" @change="loadDoctors"><option value="">全部科室</option><option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option></select><div class="grid"><div v-for="d in doctors" :key="d.id" class="panel doctor-card"><h3>{{ d.name }}</h3><p>{{ d.department_name }} · {{ d.title }}</p><p class="muted">{{ d.specialty }}</p><button class="primary" type="button" @click="loadSlots(d.id)">查看可约号源</button></div></div></section><section v-if="doctorId" class="panel"><h2>可约时段</h2><p v-if="!slots.length" class="muted">当前医生暂无可约号源。</p><div v-for="s in slots" :key="s.slot_id" class="record row"><input :id="`slot-${s.slot_id}`" v-model="selectedSlot" type="radio" name="selectedSlot" :value="s" class="slot-radio"><label :for="`slot-${s.slot_id}`">{{ time(s.starts_at) }} · {{ s.room_name }} · {{ s.fee }} 元 · 余 {{ s.remaining }} 个</label></div><form v-if="selectedSlot" aria-labelledby="book-heading" action="/hospital/api/appointments" method="post" @submit.prevent="book"><h3 id="book-heading">确认预约</h3><p>所选时段：{{ time(selectedSlot.starts_at) }}，挂号费 {{ selectedSlot.fee }} 元</p><label for="symptom">症状简述（可选，最多 200 字）</label><textarea id="symptom" v-model.trim="symptom" name="symptomSummary" maxlength="200"></textarea><button class="primary" type="submit" :disabled="busy">确认挂号</button></form></section></el-tab-pane>
    <el-tab-pane label="我的预约" name="appointments"><section class="panel"><h2>预约记录</h2><p v-if="!appointments.length">暂无预约。</p><div v-for="a in appointments" :key="a.id" class="record"><strong>{{ a.appointment_no }} · {{ a.status }}</strong><small>预约 ID {{ a.id }} · {{ time(a.starts_at) }}</small><div class="row"><button v-if="['PENDING_PAYMENT','BOOKED'].includes(a.status)" class="quiet" type="button" :disabled="busy" @click="cancel(a)">取消预约</button></div></div></section></el-tab-pane>
    <el-tab-pane label="我的账单" name="bills"><section class="panel"><h2>账单</h2><p v-if="!bills.length">暂无账单。</p><div v-for="b in bills" :key="b.id" class="record"><strong>{{ b.bill_no }} · {{ b.type }}</strong><small>{{ b.amount }} 元 · {{ b.status }}</small><button v-if="b.status==='UNPAID'" class="primary" type="button" :disabled="busy" @click="pay(b)">模拟支付 {{ b.amount }} 元</button></div></section></el-tab-pane>
    <el-tab-pane label="就诊记录" name="records"><section class="panel"><h2>已发布记录</h2><p v-if="!records.length">暂无就诊记录。</p><div v-for="r in records" :key="r.id" class="record"><strong>{{ r.chief_complaint }}</strong><p>检查：{{ r.examination }}</p><p>医嘱：{{ r.advice }}</p><small>记录 ID {{ r.id }}</small></div></section></el-tab-pane>
  </el-tabs>
</template>
<style scoped>.doctor-card{margin-top:1rem}.slot-radio{width:auto;min-height:auto}.slot-radio+label{margin:0}.record small{display:block}.record button{margin-top:.5rem}</style>
