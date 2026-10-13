const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function pageFor(summaryApi) {
  let page
  const api = { dataOverview: async () => ({ newCustomers: 5, dealAmount: 2000, dealCustomers: 2 }),
    dataSource: async () => [], dataFunnel: async () => ({ stages: [], lostCustomers: 0, dealConversionPercent: null, quoteToDealPercent: null }), dataSummary: summaryApi }
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../pages/data/index.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  vm.runInNewContext(code, { exports: {}, Page: value => { page = value },
    require: id => id.includes('services/api') ? { api } : id.includes('utils/date') ? { currentMonth: () => '2026-10' } : { money: String }
  })
  page.setData = (update, callback) => { Object.assign(page.data, update); callback?.() }
  return page
}

test('changed global settings show stale state and reanalysis replaces it with current output', async () => {
  const requests = []
  const page = pageFor(async (month, refresh) => {
    requests.push({ month, refresh })
    return { content: refresh ? '按新设置生成的分析' : '原有分析', aiGenerated: true, stale: !refresh }
  })
  await page.load()
  assert.equal(page.data.summaryStale, true)
  assert.equal(page.data.summary, '原有分析')
  await page.reanalyze()
  assert.equal(page.data.summaryStale, false)
  assert.equal(page.data.summary, '按新设置生成的分析')
  assert.deepEqual(requests, [{ month: '2026-10', refresh: false }, { month: '2026-10', refresh: true }])
})

test('failed reanalysis retains the old summary and its stale state until retry succeeds', async () => {
  let fail = true
  const page = pageFor(async (month, refresh) => {
    if (refresh && fail) throw new Error('offline')
    return { content: '经营复盘', aiGenerated: true, stale: !refresh }
  })
  await page.load()
  await page.reanalyze()
  assert.equal(page.data.summary, '经营复盘')
  assert.equal(page.data.summaryStale, true)
  assert.match(page.data.summaryError, /原复盘已保留/)
  fail = false
  await page.retrySummary()
  assert.equal(page.data.summaryStale, false)
  assert.equal(page.data.summaryError, '')
})
