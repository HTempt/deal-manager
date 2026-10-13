import { API_BASE_URL } from '../config'

export const setToken = (token: string) => wx.setStorageSync('token', token)

let loginPromise: Promise<void> | null = null

function ensureToken(): Promise<void> {
  if (wx.getStorageSync('token')) return Promise.resolve()
  if (loginPromise) return loginPromise
  const pending = new Promise<void>((resolve, reject) => {
    wx.login({ success(login) {
      wx.request({
        url: `${API_BASE_URL}/api/auth/wx-login`, method: 'POST', data: { code: login.code },
        success(res) {
          if (res.statusCode >= 200 && res.statusCode < 300) {
            setToken((res.data as {token:string}).token); resolve()
          } else reject(new Error('login failed'))
        }, fail: reject
      })
    }, fail: reject })
  }).finally(() => { loginPromise = null })
  loginPromise = pending
  return pending
}

type RequestOptions = Omit<WechatMiniprogram.RequestOption, 'url' | 'success' | 'fail' | 'data'> & { data?: any }

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  await ensureToken()
  return new Promise((resolve, reject) => {
    wx.request({
      ...options,
      url: `${API_BASE_URL}${path}`,
      header: {
        'content-type': 'application/json',
        Authorization: `Bearer ${wx.getStorageSync('token') || ''}`,
        ...(options.header || {})
      },
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300) return resolve(res.data as T)
        const data = res.data as { message?: string }
        if (res.statusCode === 401) wx.removeStorageSync('token')
        wx.showToast({ title: data?.message || '请求失败，请重试', icon: 'none' })
        reject(new Error(data?.message || 'request failed'))
      },
      fail(err) {
        wx.showToast({ title: '网络开小差了', icon: 'none' })
        reject(err)
      }
    })
  })
}
