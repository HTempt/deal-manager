import { api } from '../services/api'
import { FOLLOW_SUBSCRIBE_TEMPLATE_ID } from '../config'

const preferenceKey = () => `notificationEnabled:${wx.getStorageSync('token') || ''}`

export function cacheNotificationPreference(enabled: boolean) {
  wx.setStorageSync(preferenceKey(), enabled)
}

export async function loadNotificationPreference(): Promise<void> {
  cacheNotificationPreference(false)
  try { const setting = await api.notificationSettings(); cacheNotificationPreference(setting.enabled) } catch (_) {}
}

export function requestFollowNotification(): Promise<boolean> {
  if (!wx.getStorageSync(preferenceKey())) return Promise.resolve(false)
  if (!FOLLOW_SUBSCRIBE_TEMPLATE_ID || !wx.requestSubscribeMessage) {
    wx.showToast({ title: '微信订阅模板尚未配置', icon: 'none' })
    return Promise.resolve(false)
  }
  return new Promise(resolve => {
    wx.requestSubscribeMessage({
      tmplIds: [FOLLOW_SUBSCRIBE_TEMPLATE_ID],
      success: result => {
        const status = (result as any)[FOLLOW_SUBSCRIBE_TEMPLATE_ID]
        if (status !== 'accept') {
          wx.showToast({ title: '未获得微信订阅授权', icon: 'none' })
          return resolve(false)
        }
        api.subscribeNotification(FOLLOW_SUBSCRIBE_TEMPLATE_ID, status, 86400)
          .then(() => resolve(true)).catch(() => resolve(false))
      },
      fail: () => { wx.showToast({ title: '微信订阅授权失败', icon: 'none' }); resolve(false) }
    })
  })
}
