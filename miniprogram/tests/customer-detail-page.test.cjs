const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function pageFor(overrides = {}, wxOverrides = {}) {
  let page
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../pages/customer-detail/index.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  class TestDate extends Date { static now() { return Date.parse('2026-10-09T04:00:00Z') } }
  const api = {
    customer: async () => ({ id: 8, name: '王先生', status: 'ACTIVE', budget: 150000,
      createdAt: '2026-10-01T12:00:00', lastFollowTime: '2026-10-05T12:00:00' }),
    customerFollows: async () => [{ id: 1, content: '需要和家人商量', createdAt: '2026-10-05T12:00:00' }],
    customerSummary: async () => brief(), ...overrides
  }
  vm.runInNewContext(code, { exports: {}, Date: TestDate,
    require: id => id.includes('services/api') ? { api } : id.includes('utils/date') ? {
      today: () => '2026-10-09', shortDate: value => value || '未安排'
    } : { money: String, maskPhone: value => value || '', intent: () => '高意向' },
    Page: value => { page = value }, wx: wxOverrides
  })
  page.setData = update => Object.assign(page.data, update)
  page.onLoad({ id: '8' })
  return page
}

function brief(overrides = {}) {
  return { overview: '预算15万元，报价中，关注价格', suggestion: '2天后确认顾虑',
    aiGenerated: true, recordsUsed: 1, stale: false, updatedAt: '2026-10-09T12:00:00', ...overrides }
}

test('renders customer and records before a slow summary finishes', async () => {
  let resolveSummary
  const page = pageFor({ customerSummary: () => new Promise(resolve => { resolveSummary = resolve }) })
  const pending = page.load()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(page.data.customer.name, '王先生')
  assert.equal(page.data.loading, false)
  assert.equal(page.data.summaryLoading, true)
  assert.equal(page.data.records.length, 1)
  assert.equal(page.data.contactDays, 4)
  resolveSummary(brief({ stale: true }))
  await pending
  assert.equal(page.data.summary.stale, true)
  assert.equal(page.data.summaryUpdatedAt, '2026-10-09 12:00:00')
  assert.equal(page.data.summaryLoading, false)
})

test('new customer renders profile-only model analysis without follow history', async () => {
  const page = pageFor({ customerFollows: async () => [],
    customer: async () => ({ id: 8, name: '王先生', status: 'ACTIVE', createdAt: '2026-10-09T12:00:00' }),
    customerSummary: async () => brief({ overview: '王先生。具体需求待确认，预算待确认。', suggestion:'了解所需服务及计划时间', recordsUsed: 0 }) })
  page.data.summary = brief({ overview: '王先生从事汽车销售' })
  page.data.summaryUpdatedAt = '2026-10-08 12:00'
  await page.load()
  assert.equal(page.data.summary.aiGenerated, true)
  assert.equal(page.data.summary.overview.includes('汽车销售'), false)
  assert.equal(page.data.summaryUpdatedAt, '2026-10-09 12:00:00')
  assert.equal(page.data.hasContactHistory, false)
  assert.equal(page.data.contactDays, 0)
})

test('dormant customer detail opens the wakeup scene while recent customer keeps the normal assistant', async () => {
  let route
  const dormant = pageFor({ customer: async () => ({ id: 8, name: '王先生', status: 'ACTIVE', dormancyLevel: 'DORMANT' }) },
    { navigateTo: value => { route = value.url } })
  await dormant.load()
  assert.equal(dormant.data.customer.canWake, true)
  dormant.ai()
  assert.equal(new URL(route, 'http://localhost').searchParams.get('scenario'), '唤醒客户')
  const recent = pageFor({}, { navigateTo: value => { route = value.url } })
  await recent.load()
  assert.equal(recent.data.customer.canWake, false)
  recent.ai()
  assert.equal(new URL(route, 'http://localhost').searchParams.has('scenario'), false)
})

test('failed refresh preserves original summary and retry performs an explicit refresh', async () => {
  let fail = false, requests = []
  const page = pageFor({ customerSummary: async (id, refresh) => {
    requests.push(refresh)
    if (fail) throw new Error('offline')
    return brief()
  } })
  await page.load()
  const saved = page.data.summary
  fail = true
  await page.refreshSummary()
  assert.equal(page.data.summary, saved)
  assert.match(page.data.summaryError, /原摘要已保留/)
  assert.equal(page.data.summaryLoading, false)
  fail = false
  await page.retrySummary()
  assert.equal(page.data.summaryError, '')
  assert.deepEqual(requests, [false, true, true])
})

test('missing summary service does not prevent calls or records and retry recovers', async () => {
  let fail = true
  const page = pageFor({ customerSummary: async () => {
    if (fail) throw new Error('model unavailable')
    return brief()
  } })
  await page.load()
  assert.equal(page.data.customer.id, 8)
  assert.equal(page.data.records.length, 1)
  assert.equal(page.data.loadError, '')
  assert.match(page.data.summaryError, /加载失败/)
  fail = false
  await page.retrySummary()
  assert.equal(page.data.summary.overview, brief().overview)
})

test('a summary from a previous page load cannot overwrite the current result', async () => {
  const pendingResponses = []
  const page = pageFor({ customerSummary: () => new Promise(resolve => pendingResponses.push(resolve)) })
  const first = page.load()
  await new Promise(resolve => setImmediate(resolve))
  const second = page.load()
  await new Promise(resolve => setImmediate(resolve))
  pendingResponses[1](brief({ overview: '新摘要' }))
  await second
  pendingResponses[0](brief({ overview: '旧摘要' }))
  await first
  assert.equal(page.data.summary.overview, '新摘要')
  assert.equal(page.data.summaryLoading, false)
})

test('unload ignores a late model response and repeated refresh is guarded', async () => {
  let finish, calls = 0
  const page = pageFor({ customerSummary: () => { calls++; return new Promise(resolve => { finish = resolve }) } })
  const pending = page.load()
  await new Promise(resolve => setImmediate(resolve))
  await page.refreshSummary()
  assert.equal(calls, 1)
  page.onUnload()
  finish(brief())
  await pending
  assert.equal(page.data.summary, null)
})

test('never contacted customer shows creation age and latest record is a fallback contact time', async () => {
  const page = pageFor({ customer: async () => ({ id: 8, name: '新客户', createdAt: '2026-10-01T12:00:00' }),
    customerFollows: async () => [] })
  await page.load()
  assert.equal(page.data.hasContactHistory, false)
  assert.equal(page.data.contactDays, 8)
  const contacted = pageFor({ customer: async () => ({ id: 8, name: '客户', createdAt: '2026-10-01T12:00:00' }) })
  await contacted.load()
  assert.equal(contacted.data.hasContactHistory, true)
  assert.equal(contacted.data.contactDays, 4)
})

test('invalid response preserves cached summary and base-data failure is retryable', async () => {
  const page = pageFor()
  await page.load()
  const saved = page.data.summary
  const invalid = pageFor({ customerSummary: async () => ({ overview: '缺少建议' }) })
  invalid.data.summary = saved
  await invalid.load()
  assert.equal(invalid.data.summary, saved)
  assert.match(invalid.data.summaryError, /原摘要已保留/)
  let fail = true, summaryCalls = 0
  const failed = pageFor({ customer: async () => {
    if (fail) throw new Error('offline')
    return { id: 8, name: '重试客户' }
  }, customerSummary: async () => { summaryCalls++; return brief() } })
  await failed.load()
  assert.equal(failed.data.customer, null)
  assert.match(failed.data.loadError, /加载失败/)
  assert.equal(failed.data.loading, false)
  assert.equal(summaryCalls, 0)
  fail = false
  await failed.load()
  assert.equal(failed.data.customer.name, '重试客户')
  assert.equal(failed.data.loadError, '')
})

test('explicit refresh rereads customer and follows before replacing the summary and keeps completion visible', async () => {
  let changed=false, customers=0, records=0
  const requests=[]
  const page=pageFor({
    customer:async()=>{customers++;return {id:8,name:'王先生',need:changed?'厨房装修':'装修',budget:changed?80000:150000}},
    customerFollows:async()=>{records++;return changed?[{id:2,content:'确认厨房方案'},{id:1,content:'咨询装修'}]:[{id:1,content:'咨询装修'}]},
    customerSummary:async(id,refresh)=>{requests.push(refresh);return changed?brief({overview:'厨房装修，预算8万元',recordsUsed:2,updatedAt:'2026-10-09T12:00:35'}):brief()}
  })
  await page.load(); changed=true; await page.refreshSummary()
  assert.equal(customers,2);assert.equal(records,2)
  assert.deepEqual(requests,[false,true])
  assert.equal(page.data.customer.need,'厨房装修');assert.equal(page.data.records.length,2)
  assert.equal(page.data.summary.overview,'厨房装修，预算8万元')
  assert.equal(page.data.summaryNotice,'客户摘要已更新')
  assert.equal(page.data.summaryUpdatedAt,'2026-10-09 12:00:35')
  assert.equal(page.data.summaryLoading,false);assert.equal(page.data.loading,false)
})

test('refresh without follow history updates model analysis from edited profile', async () => {
  let changed=false
  const page=pageFor({customerFollows:async()=>[],
    customer:async()=>({id:8,name:'王先生',need:changed?'厨房装修':'装修'}),
    customerSummary:async(id,refresh)=>brief({overview:refresh?'厨房装修，预算8万元':'装修需求，范围未说明',suggestion:refresh?'了解厨房尺寸与施工时间，提供预算内方案':'了解房屋面积与装修范围',updatedAt:refresh?'2026-10-09T12:00:30':'2026-10-09T12:00:00',recordsUsed:0})})
  await page.load(); assert.equal(page.data.summary.aiGenerated,true)
  changed=true; await page.refreshSummary()
  assert.equal(page.data.customer.need,'厨房装修')
  assert.match(page.data.summary.overview,/厨房装修/)
  assert.equal(page.data.summaryNotice,'客户摘要已更新')
  assert.equal(page.data.summary.aiGenerated,true)
  assert.match(page.data.summary.suggestion,/厨房尺寸/)
  assert.equal(page.data.summaryUpdatedAt,'2026-10-09 12:00:30')
  assert.equal(page.data.summaryError,'');assert.equal(page.data.summaryLoading,false)
})

test('non-model summary response is rejected without displaying template advice', async () => {
  const page=pageFor({customerSummary:async()=>brief({aiGenerated:false,suggestion:'固定建议'})})
  await page.load()
  assert.equal(page.data.summary,null)
  assert.match(page.data.summaryError,/AI 未返回有效客户摘要/)
  assert.equal(page.data.customer.id,8)
})

test('identical regenerated text is acknowledged and the new analysis timestamp includes seconds', async () => {
  const page=pageFor({customerSummary:async(id,refresh)=>brief({updatedAt:refresh?'2026-10-09T12:00:45':'2026-10-09T12:00:00'})})
  await page.load();await page.refreshSummary()
  assert.equal(page.data.summaryNotice,'摘要已重新分析，内容与上次一致')
  assert.equal(page.data.summaryUpdatedAt,'2026-10-09 12:00:45')
})

test('model and native timeout errors remain visible with the original summary and retry recovers', async () => {
  let failure=null
  const page=pageFor({customerSummary:async()=>{if(failure)throw failure;return brief()}})
  await page.load();const saved=page.data.summary
  failure=new Error('尚未配置有效的 AI 模型密钥，请管理员配置后重试')
  await page.refreshSummary()
  assert.equal(page.data.summary,saved);assert.match(page.data.summaryError,/尚未配置/)
  assert.equal(page.data.summaryNotice,'')
  failure={errMsg:'request:fail timeout'};await page.retrySummary()
  assert.match(page.data.summaryError,/请求超时/);assert.equal(page.data.summaryLoading,false)
  failure=null;await page.retrySummary()
  assert.equal(page.data.summaryError,'');assert.ok(page.data.summaryNotice)
})

test('failed base-data refresh resets progress and retry still requests fresh analysis', async () => {
  let failure=false,summaryCalls=[]
  const page=pageFor({customer:async()=>{if(failure)throw new Error('网络异常');return {id:8,name:'王先生'}},
    customerSummary:async(id,refresh)=>{summaryCalls.push(refresh);return brief()}})
  await page.load();const saved=page.data.summary
  failure=true;await page.refreshSummary()
  assert.equal(page.data.summary,saved);assert.match(page.data.loadError,/网络异常/)
  assert.equal(page.data.summaryLoading,false);assert.equal(page.data.loading,false)
  failure=false;await page.retryLoad()
  assert.deepEqual(summaryCalls,[false,true]);assert.equal(page.data.loadError,'')
})

test('a bound load event cannot accidentally request paid analysis and slow refresh blocks repeat clicks', async () => {
  let finish,requests=[]
  const page=pageFor({customerSummary:(id,refresh)=>{requests.push(refresh);return refresh?new Promise(resolve=>{finish=resolve}):Promise.resolve(brief())}})
  await page.load({type:'tap'})
  assert.deepEqual(requests,[false])
  const pending=page.refreshSummary()
  assert.equal(page.data.summaryLoading,true)
  await page.refreshSummary()
  await new Promise(resolve=>setImmediate(resolve))
  assert.deepEqual(requests,[false,true])
  finish(brief({overview:'新的客户概况'}));await pending
  assert.equal(page.data.summaryNotice,'客户摘要已更新')
})
