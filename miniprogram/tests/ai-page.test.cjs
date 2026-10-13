const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function pageFor(api, wx = {}, notification = {}) {
  let page
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../pages/ai/index.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  vm.runInNewContext(code, { exports: {},
    require: id => id.includes('services/api') ? { api } : id.includes('utils/date') ? { shortDate: value => value || '未设置' } : {
      loadNotificationPreference: async () => {}, requestFollowNotification: async () => {}, ...notification
    }, Page: value => { page = value }, wx: { showToast() {}, ...wx }
  })
  page.setData = update => Object.assign(page.data, update)
  page.data.id = '8'
  page.data.customer = { id: 8, status: 'ACTIVE' }
  page.data.loading = false
  return page
}

const advice = () => ({ content: '请问主要顾虑是什么？', assessment: '可能是预算问题', suggestedAction: '先确认预算',
  followUpDays: 2, followUpReason: '留出确认时间', nextFollowTime: '2027-01-02T10:00:00', remainingToday: 9 })

test('generates complete advice and copies only script', async () => {
  let copied
  const page = pageFor({ generateAi: async () => advice() }, { setClipboardData: value => { copied = value.data } })
  await page.generate()
  assert.equal(page.data.result.assessment, '可能是预算问题')
  assert.equal(page.data.nextFollowDisplay, '2027-01-02T10:00:00')
  page.copy()
  assert.equal(copied, '请问主要顾虑是什么？')
})

test('dormant entry selects wakeup scenario and sends it with full assistant workflow', async () => {
  let request
  const page = pageFor({
    customer: async () => ({ id: 8, status: 'ACTIVE', daysWithoutFollow: 18, hasContactHistory: true }),
    customerFollows: async () => [{ content: '需要再考虑报价' }],
    generateAi: async data => { request = data; return advice() }
  })
  await page.onLoad({ id: '8', scenario: '唤醒客户' })
  assert.equal(page.data.scenario, '唤醒客户')
  assert.equal(page.data.customer.daysWithoutFollow, 18)
  assert.equal(page.data.lastFeedback, '需要再考虑报价')
  await page.generate()
  assert.equal(request.scenario, '唤醒客户')
  assert.equal(page.data.result.assessment, advice().assessment)
  assert.equal(page.data.result.suggestedAction, advice().suggestedAction)
  page.choose({ currentTarget: { dataset: { value: '报价后跟进' } } })
  assert.equal(page.data.scenario, '报价后跟进')
  assert.equal(page.data.result, null)
})

test('failed regenerate retains old complete advice and sends only previous script', async () => {
  let request
  const page = pageFor({ generateAi: async data => { request = data; throw new Error('offline') } })
  page.data.result = advice()
  await page.generate({ currentTarget: { dataset: { action: 'REGENERATE' } } })
  assert.equal(request.previousContent, advice().content)
  assert.equal(page.data.result.followUpDays, 2)
  assert.equal(page.data.generating, false)
  assert.match(page.data.generationError,/原建议已保留/)
})

test('first model failure displays a persistent error and retry replaces it with model advice', async () => {
  let failed=true
  const page=pageFor({generateAi:async()=>{if(failed)throw {errMsg:'request:fail timeout'};return advice()}})
  await page.generate()
  assert.equal(page.data.result,null)
  assert.match(page.data.generationError,/请求超时/)
  failed=false; await page.generate()
  assert.equal(page.data.generationError,'')
  assert.equal(page.data.result.followUpReason,advice().followUpReason)
})

test('missing model reason is rejected rather than filled with fixed advice', async () => {
  const page=pageFor({generateAi:async()=>({...advice(),followUpReason:''})})
  await page.generate()
  assert.equal(page.data.result,null)
  assert.match(page.data.generationError,/未返回完整跟进建议/)
})

test('adopting asks before replacing old plan, schedules once and marks adopted', async () => {
  let scheduled = 0, prompt, subscribed = 0
  const page = pageFor({
    customer: async () => ({ status: 'ACTIVE', nextFollowTime: '2027-01-01T10:00:00' }),
    scheduleCustomer: async (id, time) => { scheduled++; assert.equal(id, '8'); assert.equal(time, advice().nextFollowTime); return { status: 'ACTIVE', nextFollowTime: time } }
  }, { showModal: options => { prompt = options.content; options.success({ confirm: true }) } },
  { requestFollowNotification: async () => { subscribed++ } })
  page.data.result = advice()
  await page.adoptFollowUp()
  await page.adoptFollowUp()
  assert.match(prompt, /2027-01-01/)
  assert.equal(scheduled, 1)
  assert.equal(subscribed, 1)
  assert.equal(page.data.adopted, true)
  assert.equal(page.data.scheduling, false)
})

test('cancel leaves existing schedule unchanged', async () => {
  let scheduled = 0
  const page = pageFor({ customer: async () => ({ status: 'ACTIVE' }), scheduleCustomer: async () => { scheduled++ } },
    { showModal: options => options.success({ confirm: false }) })
  page.data.result = advice()
  await page.adoptFollowUp()
  assert.equal(scheduled, 0)
  assert.equal(page.data.adopted, false)
})

test('latest terminal status prevents scheduling despite earlier active state', async () => {
  let scheduled = 0, confirmed = 0
  const page = pageFor({ customer: async () => ({ status: 'DEAL' }), scheduleCustomer: async () => { scheduled++ } },
    { showModal: () => { confirmed++ } })
  page.data.result = advice()
  await page.adoptFollowUp()
  assert.equal(scheduled, 0)
  assert.equal(confirmed, 0)
})

test('schedule failure does not mark adoption and permits retry', async () => {
  let fail = true
  const page = pageFor({ customer: async () => ({ status: 'ACTIVE' }), scheduleCustomer: async () => {
    if (fail) throw new Error('offline'); return { status: 'ACTIVE', nextFollowTime: advice().nextFollowTime }
  } }, { showModal: options => options.success({ confirm: true }) })
  page.data.result = advice()
  await page.adoptFollowUp()
  assert.equal(page.data.adopted, false)
  assert.equal(page.data.scheduling, false)
  fail = false
  await page.adoptFollowUp()
  assert.equal(page.data.adopted, true)
})
