const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const example = () => ({ expectedTasks: 86, completedTasks: 72, pendingTasks: 14, completionPercent: 83.7,
  followRecords: 38, followedCustomers: 10, averageFollowCount: 3.8, dealCycleCustomers: 12, averageDealCycleDays: 12.4,
  asOf: '2026-10-10T10:00:00' })

function pageFor(overrides = {}) {
  let page
  const api = { dataOverview: async () => ({ newCustomers: 126, dealAmount: 286000, dealCustomers: 12 }),
    dataSource: async () => [], dataFunnel: async () => ({ stages: [] }),
    dataSummary: async () => ({ content: '经营复盘', aiGenerated: true }), dataFollowEfficiency: async () => example(), ...overrides }
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../pages/data/index.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  vm.runInNewContext(code, { exports: {}, Page: value => { page = value },
    require: id => id.includes('services/api') ? { api } : id.includes('utils/date') ? { currentMonth: () => '2026-10' } : { money: String } })
  page.setData = (update, callback) => { Object.assign(page.data, update); callback?.() }
  return page
}

test('follow efficiency displays actual one-decimal rates, averages, gaps and sample sizes', async () => {
  const page = pageFor(); await page.load()
  const e = page.data.efficiency
  assert.equal(e.expectedTasks, 86); assert.equal(e.completedTasks, 72); assert.equal(e.pendingTasks, 14)
  assert.equal(e.completionDisplay, '83.7%'); assert.equal(e.completionWidth, 83.7)
  assert.equal(e.averageFollowDisplay, '3.8次'); assert.equal(e.averageCycleDisplay, '12.4天')
  assert.equal(e.followedCustomers, 10); assert.equal(e.dealCycleCustomers, 12)
  assert.equal(e.asOfText, '2026-10-10 10:00'); assert.equal(page.data.efficiencyLoading, false)
})

test('no samples display dashes while a real zero completion or cycle displays zero', async () => {
  const page = pageFor({ dataFollowEfficiency: async () => ({ ...example(), expectedTasks: 0, completedTasks: 0,
    followedCustomers: 0, dealCycleCustomers: 0, completionPercent: null, averageFollowCount: null, averageDealCycleDays: null }) })
  await page.load()
  assert.equal(page.data.efficiency.completionDisplay, '—'); assert.equal(page.data.efficiency.completionWidth, 0)
  assert.equal(page.data.efficiency.averageFollowDisplay, '—'); assert.equal(page.data.efficiency.averageCycleDisplay, '—')
  const zero = pageFor({ dataFollowEfficiency: async () => ({ ...example(), completedTasks: 0, completionPercent: 0, averageDealCycleDays: 0 }) })
  await zero.load()
  assert.equal(zero.data.efficiency.completionDisplay, '0.0%'); assert.equal(zero.data.efficiency.averageCycleDisplay, '0.0天')
})

test('efficiency errors leave overview, sources and summary available and retry recovers only efficiency', async () => {
  let fail = true, summaries = 0
  const page = pageFor({ dataFollowEfficiency: async () => { if (fail) throw Error('offline'); return example() },
    dataSummary: async () => { summaries++; return { content: '经营复盘', aiGenerated: true } } })
  await page.load()
  assert.match(page.data.efficiencyError, /加载失败/); assert.equal(page.data.overview.newCustomers, 126)
  assert.equal(page.data.summary, '经营复盘'); assert.equal(page.data.efficiencyLoading, false)
  fail = false; await page.retryEfficiency()
  assert.equal(page.data.efficiencyError, ''); assert.equal(page.data.efficiency.completionDisplay, '83.7%')
  assert.equal(summaries, 1)
})

test('source failure is independent and zero new customers get no fabricated source bar', async () => {
  let fail = true
  const page = pageFor({ dataSource: async () => { if (fail) throw Error('offline'); return [{ source: '微信', newCustomers: 0, dealAmount: 100 }] } })
  await page.load()
  assert.match(page.data.sourceError, /加载失败/); assert.equal(page.data.dataError, '')
  assert.equal(page.data.overview.dealCustomers, 12); assert.equal(page.data.efficiency.completionDisplay, '83.7%')
  fail = false; await page.retrySources(); assert.equal(page.data.sourceError, '')
  assert.equal(page.data.sources[0].width, 0)
})

test('late responses for another month cannot overwrite efficiency data or its loading state', async () => {
  let resolveOld
  const page = pageFor({ dataFollowEfficiency: month => month === '2026-10' ? new Promise(resolve => { resolveOld = resolve })
    : Promise.resolve({ ...example(), expectedTasks: 3, completedTasks: 3, completionPercent: 100 }) })
  const old = page.load(); page.data.month = '2026-11'; await page.load()
  assert.equal(page.data.efficiency.expectedTasks, 3)
  resolveOld(example()); await old
  assert.equal(page.data.efficiency.expectedTasks, 3); assert.equal(page.data.efficiency.completionDisplay, '100.0%')
  assert.equal(page.data.efficiencyLoading, false)
})

test('switching the month resets old averages immediately and toggles expose details', async () => {
  let resolveNew
  const page = pageFor({ dataFollowEfficiency: month => month === '2026-11' ? new Promise(resolve => { resolveNew = resolve }) : Promise.resolve(example()) })
  await page.load()
  page.month({ detail: { value: '2026-11' } })
  assert.equal(page.data.efficiency.averageCycleDisplay, '—'); assert.equal(page.data.efficiencyLoading, true)
  resolveNew(example()); await new Promise(resolve => setImmediate(resolve))
  assert.equal(page.data.month, '2026-11'); assert.equal(page.data.efficiencyLoading, false)
  page.toggleFunnel(); page.toggleBasis()
  assert.equal(page.data.funnelExpanded, true); assert.equal(page.data.showBasis, true)
})
