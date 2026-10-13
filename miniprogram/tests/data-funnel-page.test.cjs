const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
function pageFor(funnelApi, overrides = {}) {
  let page
  const api = { dataOverview: async () => ({ newCustomers: 126 }), dataSource: async () => [],
    dataSummary: async () => ({content:'经营复盘',aiGenerated:true}), dataFunnel: funnelApi, ...overrides }
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../pages/data/index.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  vm.runInNewContext(code, { exports: {}, Page: value => { page = value },
    require: id => id.includes('services/api') ? { api } : id.includes('utils/date') ? { currentMonth: () => '2026-10' } : { money: String } })
  page.setData = (update, callback) => { Object.assign(page.data, update); callback?.() }
  return page
}
const stages = ['新增客户', '需求沟通', '方案中', '报价中', '谈判中', '成交']
const result = (counts=[126,92,61,43,21,12]) => ({ stages:stages.map((stage,i)=>({stage,customers:counts[i]})), lostCustomers:2, dealConversionPercent:9.5,quoteToDealPercent:27.9,inquiryToQuotePercent:34.1 })
test('funnel presents the six cumulative stages and one-decimal conversion rates',async()=>{
  const page=pageFor(async()=>result()); await page.load()
  assert.equal(page.data.funnel.stages.length,6)
  assert.equal(page.data.funnel.stages[0].width,100)
  assert.equal(page.data.funnel.stages[3].customers,43)
  assert.equal(page.data.funnel.dealConversionDisplay,'9.5%')
  assert.equal(page.data.funnel.quoteToDealDisplay,'27.9%')
  assert.equal(page.data.funnel.inquiryToQuoteDisplay,'34.1%')
  assert.equal(page.data.funnel.quotedCustomers,43)
  assert.equal(page.data.funnelLoading,false)
})
test('zero denominators and absent rates do not invent conversions or minimum bars',async()=>{
  const page=pageFor(async()=>({...result([0,0,0,0,0,0]),dealConversionPercent:null,quoteToDealPercent:null})); await page.load()
  assert.equal(page.data.funnel.dealConversionDisplay,'—'); assert.equal(page.data.funnel.quoteToDealDisplay,'—')
  assert.ok(page.data.funnel.stages.every(stage=>stage.width===0))
})
test('failed funnel leaves business data and AI summary available',async()=>{
  const page=pageFor(async()=>{throw Error('offline')}); await page.load()
  assert.match(page.data.funnelError,/加载失败/); assert.equal(page.data.overview.newCustomers,126)
  assert.equal(page.data.summary,'经营复盘'); assert.equal(page.data.summaryError,'')
})
test('failed overview does not prevent independent funnel and summary',async()=>{
  const page=pageFor(async()=>result(),{dataOverview:async()=>{throw Error('offline')}}); await page.load()
  assert.equal(page.data.funnel.dealConversionDisplay,'9.5%'); assert.equal(page.data.summary,'经营复盘')
  assert.match(page.data.dataError,/加载失败/)
})
test('older month responses cannot overwrite the new month funnel',async()=>{
  let resolveOld
  const page=pageFor(month=>month==='2026-10'?new Promise(resolve=>{resolveOld=resolve}):Promise.resolve(result([3,2,1,1,1,1])))
  const old=page.load(); page.data.month='2026-11'; const latest=page.load(); await latest
  resolveOld(result()); await old
  assert.equal(page.data.funnel.stages[0].customers,3)
})
