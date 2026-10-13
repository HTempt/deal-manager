const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function pageFor(customers, wxOverrides = {}) {
  let page
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../pages/customer/index.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  vm.runInNewContext(code, { exports: {}, setTimeout, clearTimeout,
    require: id => id.includes('services/api') ? { api: { customers } } : id.includes('utils/notification') ? {
      loadNotificationPreference: async () => {}, requestFollowNotification: async () => {}
    } : id.includes('utils/date') ? { shortDate: value => value || '未安排', today: () => '2026-10-09' }
      : { money: value => String(value || 0), intent: () => '高意向' },
    Page: value => { page = value }, wx: { getStorageSync() {}, removeStorageSync() {}, ...wxOverrides }
  })
  page.setData = (update, callback) => { Object.assign(page.data, update); callback?.() }
  return page
}

const customer = (id = 8, overrides = {}) => ({ id, name: '王先生', status: 'ACTIVE', budget: 38000,
  stage: '报价中', daysWithoutFollow: 18, hasContactHistory: true, dormancyLevel: 'DORMANT', ...overrides })

test('at-risk subfilter keeps aggregate dormant count and displays factual customer context', async () => {
  const queries = []
  const page = pageFor(async query => {
    const params = new URLSearchParams(query.slice(1)); queries.push(params)
    const filter = params.get('filter')
    if (filter === 'AT_RISK') return { total: 2, records: [customer(8, { daysWithoutFollow: 14, dormancyLevel: 'AT_RISK' })] }
    return { total: filter === 'DORMANT_ALL' ? 5 : filter === 'ALL' ? 12 : 0, records: [] }
  })
  page.data.active = 'DORMANT_ALL'; page.data.dormancyFilter = 'AT_RISK'; page.data.keyword = '王 &先生'
  await page.load()
  assert.equal(page.data.total, 2)
  assert.equal(page.data.tabs.find(tab => tab.k === 'DORMANT_ALL').count, 5)
  assert.equal(page.data.list[0].inactivityText, '14 天未联系')
  assert.equal(page.data.list[0].dormancyText, '即将沉睡')
  assert.equal(page.data.list[0].canWake, true)
  assert.equal(page.data.list[0].budgetText, '38000')
  assert.equal(queries[0].get('filter'), 'AT_RISK')
  assert.equal(queries[0].get('keyword'), '王 &先生')
})

test('never-contacted customer uses creation age and terminal customers have no wake button', () => {
  const page = pageFor(async () => ({ records: [], total: 0 }))
  const items = page.formatCustomers([customer(1, { hasContactHistory: false }),
    customer(2, { status: 'DEAL' }), customer(3, { status: 'LOST' })])
  assert.equal(items[0].inactivityText, '尚未跟进，创建已 18 天')
  assert.equal(items[0].canWake, true)
  assert.equal(items[1].canWake, false)
  assert.equal(items[2].canWake, false)
})

test('home or membership entry resets the subfilter and wake route selects the correct scenario', async () => {
  let removed, route
  const page = pageFor(async () => ({ records: [customer()], total: 1 }), {
    getStorageSync: () => 'DORMANT_ALL', removeStorageSync: key => { removed = key },
    navigateTo: value => { route = value.url }
  })
  page.data.dormancyFilter = 'LONG_DORMANT'
  page.onShow()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(page.data.active, 'DORMANT_ALL')
  assert.equal(page.data.dormancyFilter, 'DORMANT_ALL')
  assert.equal(removed, 'customerFilter')
  page.wake({ currentTarget: { dataset: { id: 8 } } })
  const url = new URL(route, 'http://localhost')
  assert.equal(url.searchParams.get('id'), '8')
  assert.equal(url.searchParams.get('scenario'), '唤醒客户')
})

test('pagination loads remaining dormant customers only once and failure permits retry', async () => {
  let fail = true, moreCalls = 0
  const page = pageFor(async query => {
    const params = new URLSearchParams(query.slice(1))
    if (params.get('page') === '2') {
      moreCalls++
      if (fail) throw new Error('offline')
      return { total: 3, records: [customer(3)] }
    }
    return { total: 3, records: [customer(1), customer(2)] }
  })
  page.data.active = 'DORMANT_ALL'
  await page.load()
  assert.equal(page.data.hasMore, true)
  await page.loadMore()
  assert.equal(page.data.list.length, 2)
  assert.match(page.data.loadMoreError, /加载失败/)
  assert.equal(page.data.page, 1)
  fail = false
  await Promise.all([page.loadMore(), page.loadMore()])
  assert.equal(page.data.list.length, 3)
  assert.equal(page.data.page, 2)
  assert.equal(page.data.hasMore, false)
  assert.equal(page.data.loadMoreError, '')
  assert.equal(moreCalls, 2)
})

test('switching filters discards a late pagination response from the previous cohort', async () => {
  let finishMore
  const page = pageFor(async query => {
    const params = new URLSearchParams(query.slice(1))
    if (params.get('page') === '2') return new Promise(resolve => { finishMore = resolve })
    if (params.get('filter') === 'ALL') return { total: 1, records: [customer(9)] }
    return { total: 3, records: [customer(1)] }
  })
  page.data.active = 'DORMANT_ALL'
  await page.load()
  const more = page.loadMore()
  page.data.active = 'ALL'
  await page.load()
  finishMore({ total: 3, records: [customer(3)] })
  await more
  assert.equal(page.data.list.length, 1)
  assert.equal(page.data.list[0].id, 9)
  assert.equal(page.data.loadingMore, false)
})

test('load failure is shown and reload restores the cohort', async () => {
  let fail = true
  const page = pageFor(async () => { if (fail) throw new Error('offline'); return { records: [customer()], total: 1 } })
  await page.load()
  assert.match(page.data.loadError, /加载失败/)
  assert.equal(page.data.loading, false)
  fail = false
  await page.load()
  assert.equal(page.data.loadError, '')
  assert.equal(page.data.list.length, 1)
})
