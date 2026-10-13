const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function pageFor(home, dailyAdvice) {
  let page, route
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../pages/home/index.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  vm.runInNewContext(code, { exports: {}, Page: value => { page = value },
    require: id => id.includes('services/api') ? { api: { home, dailyAdvice } } : { money: value => Number(value).toLocaleString('zh-CN') },
    wx: { navigateTo: value => { route = value.url }, pageScrollTo: () => {} } })
  page.setData = update => Object.assign(page.data, update)
  return { page, route: () => route }
}

const plan = {
  date: '2026-02-14', overview: '先联系报价后未回复的客户，再跟进昨天主动咨询的机会',
  candidateCount: 6, updatedAt: '2026-02-14T09:30:00', stale: false, aiGenerated: true,
  items: [
    { customerId: 8, customerName: '王先生', priority: 'S', advice: '报价 ¥38,000，3 天未回复，建议今天下午电话联系',
      stage: '报价中', budget: 38000, intentionScore: 80, daysWithoutContact: 3, hasContactHistory: true,
      planTime: '2026-02-11T10:00:00', overdueDays: 3 },
    { customerId: 9, customerName: '李女士', priority: 'A', advice: '昨天主动咨询，建议今天上午先微信确认需求',
      stage: '需求沟通', budget: null, intentionScore: 75, daysWithoutContact: 1, hasContactHistory: true,
      planTime: null, overdueDays: 0 }
  ]
}

test('renders the AI daily plan with real priorities, facts and generation time', async () => {
  const t = pageFor(async () => ({}), async () => plan)
  await t.page.loadAdvice(false)
  const advice = t.page.data.advice
  assert.equal(advice.loading, false)
  assert.equal(advice.error, '')
  assert.equal(advice.items.length, 2)
  assert.equal(advice.items[0].priority, 'S')
  assert.equal(advice.items[0].priorityClass, 'priority-s')
  assert.equal(advice.items[0].avatar, '王')
  assert.equal(advice.items[0].budgetText, '¥38,000')
  assert.equal(advice.items[0].contactText, '3 天未联系')
  assert.equal(advice.items[0].overdueText, '逾期3天')
  assert.equal(advice.items[0].advice, '报价 ¥38,000，3 天未回复，建议今天下午电话联系')
  assert.equal(advice.items[1].priorityClass, 'priority-a')
  assert.equal(advice.items[1].budgetText, '待确认')
  assert.equal(advice.items[1].overdueText, '')
  assert.equal(advice.updatedText, '生成于 02-14 09:30')
  assert.equal(advice.stale, false)
})

test('regenerate asks the backend for a refresh and keeps the previous plan when the model fails', async () => {
  const calls = []
  let fail = false
  const t = pageFor(async () => ({}), async refresh => {
    calls.push(refresh)
    if (fail) throw Error('AI 未返回有效今日建议，本次不扣次数，请重试')
    return plan
  })
  await t.page.loadAdvice(false)
  assert.deepEqual(calls, [false])
  t.page.refreshAdvice()
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(calls, [false, true])
  assert.equal(t.page.data.advice.items.length, 2)

  fail = true
  await t.page.loadAdvice(true)
  assert.match(t.page.data.advice.error, /未返回有效今日建议/)
  assert.equal(t.page.data.advice.loading, false)
  assert.equal(t.page.data.advice.items.length, 2)
})

test('a missing or empty plan does not break the rest of the home page', async () => {
  const t = pageFor(async () => ({ customerCount: 3, todayTasks: [], unscheduledCount: 0 }), async () => {
    throw Error('网络开小差了')
  })
  await t.page.load()
  await t.page.loadAdvice(false)
  assert.equal(t.page.data.loadError, '')
  assert.equal(t.page.data.overview.customerCount, 3)
  assert.match(t.page.data.advice.error, /网络开小差了/)

  const empty = pageFor(async () => ({}), async () => ({
    overview: '今天暂无需要重点跟进的客户。', items: [], candidateCount: 0, aiGenerated: false, stale: false
  }))
  await empty.page.loadAdvice(false)
  assert.equal(empty.page.data.advice.items.length, 0)
  assert.match(empty.page.data.advice.overview, /暂无需要重点跟进/)
  assert.equal(empty.page.data.advice.aiGenerated, false)
})