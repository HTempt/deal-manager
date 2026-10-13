const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function pageFor(pageName, customer = {}, fail = false) {
  let page, payload, modal, toast, saves = 0
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, `../pages/${pageName}/index.ts`), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  const save = async value => {
    saves++; payload = value
    if (fail) throw new Error('offline')
    return { intentionBefore: 72, intentionAfter: 78, intentionMode: value.intentionMode }
  }
  const api = { customer: async () => ({ id: 8, name: '王先生', need: '装修', stage: '报价中',
    intentionScore: 72, intentionMode: 'AUTO', ...customer }), createFollow: save,
    createCustomer: save, updateCustomer: async (id, value) => save(value) }
  vm.runInNewContext(code, { exports: {}, setTimeout: () => {},
    require: id => id.includes('services/api') ? { api } : id.includes('utils/notification') ? {
      loadNotificationPreference() {}, requestFollowNotification: async () => {}
    } : { today: () => '2026-10-10', toApiDate: (date, time) => `${date}T${time}:00` },
    Page: value => { page = value }, wx: { setNavigationBarTitle() {}, navigateBack() {},
      showToast: value => { toast = value.title }, showModal: value => { modal = value } }
  })
  page.setData = update => {
    for (const [key, value] of Object.entries(update)) {
      const keys = key.split('.'); let object = page.data
      for (const part of keys.slice(0, -1)) object = object[part]
      object[keys.at(-1)] = value
    }
  }
  return { page, payload: () => payload, modal: () => modal, toast: () => toast, saves: () => saves }
}

test('follow saves observed signals and automatic mode without sending a fixed delta or feedback as stage', async () => {
  const t = pageFor('follow-edit')
  await t.page.onLoad({ customerId: '8', taskId: '2' })
  t.page.content({ detail: { value: '客户主动咨询报价' } })
  t.page.signals({ detail: { value: ['activeInquiry', 'priceInquiry'] } })
  await t.page.submit()
  const value = t.payload()
  assert.equal(value.intentionMode, 'AUTO'); assert.equal(value.intentionScore, null)
  assert.equal(value.activeInquiry, true); assert.equal(value.priceInquiry, true)
  assert.equal(value.customerStatus, null); assert.equal('intentionChange' in value, false)
  assert.equal(value.taskId, 2)
  assert.match(t.modal().content, /72 → 78/)
  assert.equal(t.page.data.saving, false)
})

test('manual score and mode are restored and zero can be saved', async () => {
  const t = pageFor('follow-edit', { intentionMode: 'MANUAL', intentionScore: 0 })
  await t.page.onLoad({ customerId: '8' })
  assert.equal(t.page.data.manualScore, true); assert.equal(t.page.data.intentionScore, '0')
  t.page.content({ detail: { value: '没有成交意愿' } })
  await t.page.submit()
  assert.equal(t.payload().intentionScore, 0); assert.equal(t.payload().intentionMode, 'MANUAL')
  t.page.manualScore({ detail: { value: false } })
  await t.page.submit()
  assert.equal(t.payload().intentionMode, 'AUTO'); assert.equal(t.payload().intentionScore, null)
})

test('no reply clears inquiry signals and lost feedback sets lost status', async () => {
  const t = pageFor('follow-edit')
  await t.page.onLoad({ customerId: '8' })
  t.page.signals({ detail: { value: ['activeInquiry', 'priceInquiry'] } })
  t.page.chooseStatus({ currentTarget: { dataset: { value: '没有回应' } } })
  assert.equal(t.page.data.activeInquiry, false); assert.equal(t.page.data.priceInquiry, false)
  t.page.chooseStatus({ currentTarget: { dataset: { value: '已流失' } } })
  t.page.content({ detail: { value: '已明确拒绝' } })
  await t.page.submit()
  assert.equal(t.payload().customerStatus, '已流失')
})

test('manual bounds and fractional or blank values cannot be submitted', async () => {
  const t = pageFor('follow-edit')
  await t.page.onLoad({ customerId: '8' })
  t.page.content({ detail: { value: '反馈' } }); t.page.manualScore({ detail: { value: true } })
  for (const value of ['', ' ', '-1', '101', '72.5', 'NaN']) {
    t.page.scoreInput({ detail: { value } }); await t.page.submit()
    assert.equal(t.saves(), 0); assert.match(t.toast(), /0～100/)
  }
})

test('save failure retains input and clears the saving state for retry', async () => {
  const t = pageFor('follow-edit', {}, true)
  await t.page.onLoad({ customerId: '8' }); t.page.content({ detail: { value: '真实反馈' } })
  await assert.rejects(t.page.submit(), /offline/)
  assert.equal(t.page.data.saving, false); assert.equal(t.page.data.content, '真实反馈')
  assert.equal(t.modal(), undefined)
})

test('customer editing restores manual score, preserves industry stage and permits return to auto', async () => {
  const t = pageFor('customer-edit', { intentionMode: 'MANUAL', intentionScore: 0, stage: '到店试驾' })
  t.page.data.id = '8'; await t.page.load('8')
  assert.equal(t.page.data.intentionMode, 'MANUAL'); assert.equal(t.page.data.form.intentionScore, 0)
  await t.page.submit()
  assert.equal(t.payload().stage, '到店试驾'); assert.equal(t.payload().intentionScore, 0)
  t.page.scoreMode({ currentTarget: { dataset: { mode: 'AUTO' } } }); await t.page.submit()
  assert.equal(t.payload().intentionMode, 'AUTO'); assert.equal(t.payload().intentionScore, null)
})

test('customer manual inputs reject invalid values and slider supplies a valid integer', async () => {
  const t = pageFor('customer-edit')
  await t.page.load('8'); t.page.scoreMode({ currentTarget: { dataset: { mode: 'MANUAL' } } })
  for (const value of ['', '72.5', '101', '-1']) {
    t.page.input({ currentTarget: { dataset: { key: 'intentionScore' } }, detail: { value } })
    await t.page.submit(); assert.equal(t.saves(), 0)
  }
  t.page.scoreSlider({ detail: { value: 78 } }); await t.page.submit()
  assert.equal(t.payload().intentionScore, 78)
})

test('customer editing restores location fields and includes them in the payload', async () => {
  const t = pageFor('customer-edit', { city: '杭州市', address: '西湖区文三路 100 号' })
  await t.page.load('8')
  assert.equal(t.page.data.form.city, '杭州市')
  assert.equal(t.page.data.form.address, '西湖区文三路 100 号')
  await t.page.submit()
  assert.equal(t.payload().city, '杭州市')
  assert.equal(t.payload().address, '西湖区文三路 100 号')
})

test('new customer cannot be submitted without a city', async () => {
  const t = pageFor('customer-edit')
  t.page.data.form.name = '王先生'
  t.page.data.form.need = '装修'
  await t.page.submit()
  assert.equal(t.saves(), 0)
  assert.equal(t.toast(), '请输入客户所在城市')
})
