const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function pageFor(api = {}) {
  let page
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../pages/follow/index.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  class TestDate extends Date { static now() { return Date.parse('2026-12-31T16:30:00Z') } }
  vm.runInNewContext(code, {
    exports: {}, Date: TestDate,
    require: id => id.includes('services/api') ? { api } : { money: value => String(value || 0) },
    Page: value => { page = value }, wx: {}
  })
  page.setData = update => Object.assign(page.data, update)
  return page
}

test('future tasks group by date, sort by time, and label tomorrow at Beijing year boundary', () => {
  const page = pageFor()
  page.data.active = 'upcoming'
  page.data.upcoming = [
    { taskId: 3, planTime: '2027-01-03T10:00:00' },
    { taskId: 2, planTime: '2027-01-02T15:00:00' },
    { taskId: 1, planTime: '2027-01-02T00:00:00' }
  ]
  page.updateGroups()
  assert.equal(page.data.groups.length, 2)
  assert.equal(page.data.groups[0].label, '明天')
  assert.equal(page.data.groups[1].label, '2027年1月3日')
  assert.equal(page.data.groups[0].tasks[0].taskId, 1)
  assert.equal(page.data.groups[0].tasks[1].taskId, 2)
  assert.equal(page.formatPlanDate('2027-01-02T15:00:00'), '2027年1月2日 15:00')
})

test('switching tabs shows only the selected time range', () => {
  const page = pageFor()
  page.data.today = [{ taskId: 1 }]
  page.data.overdue = [{ taskId: 2 }]
  page.tab({ currentTarget: { dataset: { tab: 'overdue' } } })
  assert.equal(page.data.groups[0].tasks[0].taskId, 2)
  page.tab({ currentTarget: { dataset: { tab: 'today' } } })
  assert.equal(page.data.groups[0].tasks[0].taskId, 1)
  page.tab({ currentTarget: { dataset: { tab: 'upcoming' } } })
  assert.equal(page.data.groups.length, 0)
})

test('loads counts for all three ranges and uses current tab after requests resolve', async () => {
  let resolveUpcoming
  const page = pageFor({
    todayFollows: async () => [{ taskId: 1 }], overdueFollows: async () => [{ taskId: 2 }],
    upcomingFollows: () => new Promise(resolve => { resolveUpcoming = resolve })
  })
  const pending = page.load()
  page.tab({ currentTarget: { dataset: { tab: 'upcoming' } } })
  resolveUpcoming([{ taskId: 3, planTime: '2027-01-02T10:00:00' }])
  await pending
  assert.equal(page.data.total, 3)
  assert.equal(page.data.groups[0].tasks[0].taskId, 3)
  assert.equal(page.data.loading, false)
})

test('failed request shows error and retry restores list', async () => {
  let fail = true
  const page = pageFor({ todayFollows: async () => [], overdueFollows: async () => [],
    upcomingFollows: async () => { if (fail) throw new Error('offline'); return [] } })
  await page.load()
  assert.equal(page.data.loadError, '任务加载失败，请重试')
  assert.equal(page.data.loading, false)
  fail = false
  await page.load()
  assert.equal(page.data.loadError, '')
  assert.equal(page.data.groups.length, 0)
})
