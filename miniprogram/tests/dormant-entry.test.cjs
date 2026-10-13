const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function pageFor(name, api, wx) {
  let page
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, `../pages/${name}/index.ts`), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  vm.runInNewContext(code, { exports: {}, require: id => id.includes('services/api') ? { api } : { money: String },
    Page: value => { page = value }, wx })
  page.setData = update => Object.assign(page.data, update)
  return page
}

test('homepage loads dormant total and selects the aggregate customer filter', async () => {
  let selected, route
  const page = pageFor('home', { home: async () => ({ dormantCount: 12, todayTasks: [] }) }, {
    setStorageSync: (key, value) => { assert.equal(key, 'customerFilter'); selected = value },
    switchTab: value => { route = value.url }
  })
  await page.load()
  assert.equal(page.data.overview.dormantCount, 12)
  page.goCustomers({ currentTarget: { dataset: { filter: 'DORMANT_ALL' } } })
  assert.equal(selected, 'DORMANT_ALL')
  assert.equal(route, '/pages/customer/index')
})

test('membership benefit has a working entry to the same dormant filter', () => {
  let selected, route
  const page = pageFor('membership', {}, {
    setStorageSync: (key, value) => { assert.equal(key, 'customerFilter'); selected = value },
    switchTab: value => { route = value.url }
  })
  page.dormantCustomers()
  assert.equal(selected, 'DORMANT_ALL')
  assert.equal(route, '/pages/customer/index')
  assert.equal(page.data.agreed, false)
})
