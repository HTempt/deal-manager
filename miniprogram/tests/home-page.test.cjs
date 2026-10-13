const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function pageFor(home) {
  let page, route, scroll
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../pages/home/index.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  vm.runInNewContext(code, { exports: {}, Page: value => { page = value },
    require: id => id.includes('services/api') ? { api: { home } } : { money: value => Number(value).toLocaleString('zh-CN') },
    wx: { navigateTo: value => { route = value.url }, pageScrollTo: value => { scroll = value.selector } } })
  page.setData = update => Object.assign(page.data, update)
  return { page, route: () => route, scroll: () => scroll }
}

test('preserves backend value order and reasons, including taskless opportunities and unknown amounts', async () => {
  const t = pageFor(async () => ({ todayTasks: [
    { customerId: 2, customerName: '李女士', budget: 20000, reasonText: '¥20,000 + 昨天主动咨询', hasContactHistory: true, daysWithoutFollow: 1 },
    { customerId: 1, customerName: '王先生', budget: null, reasonText: '高意向 + 尚未安排下次跟进', hasContactHistory: false }
  ] }))
  await t.page.load()
  const items = t.page.data.overview.todayTasks
  assert.deepEqual(Array.from(items, item => item.customerId), [2, 1])
  assert.equal(items[0].reasonText, '¥20,000 + 昨天主动咨询')
  assert.equal(items[0].budgetText, '¥20,000')
  assert.equal(items[1].budgetText, '待确认')
  assert.equal(items[1].contactText, '尚未联系')
  assert.equal(t.page.data.loading, false)
})

test('record follow-up omits absent task IDs and preserves real task IDs', () => {
  const t = pageFor(async () => ({}))
  for (const task of [undefined, null, '']) {
    t.page.goRecord({ currentTarget: { dataset: { id: 2, task } } })
    assert.equal(t.route(), '/pages/follow-edit/index?customerId=2')
  }
  t.page.goRecord({ currentTarget: { dataset: { id: 2, task: 18 } } })
  assert.equal(t.route(), '/pages/follow-edit/index?customerId=2&taskId=18')
  t.page.goPriority()
  assert.equal(t.scroll(), '#priority-customers')
})

test('load failure can be retried without presenting an empty recommendation as success', async () => {
  let fail = true
  const t = pageFor(async () => { if (fail) throw Error('offline'); return { todayTasks: [] } })
  await t.page.load()
  assert.match(t.page.data.loadError, /加载失败/)
  assert.equal(t.page.data.loading, false)
  fail = false
  await t.page.load()
  assert.equal(t.page.data.loadError, '')
  assert.equal(t.page.data.overview.todayTasks.length, 0)
})
