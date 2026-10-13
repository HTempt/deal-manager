export function money(value?: number | string | null) {
  const parsed = Number(value)
  const n = Number.isFinite(parsed) ? parsed : 0
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}
export function intent(score = 0) {
  if (score >= 90) return '极高'
  if (score >= 70) return '高意向'
  if (score >= 40) return '较高'
  return '普通'
}
export function maskPhone(phone?: string) {
  return phone && phone.length >= 7 ? phone.replace(/(\d{3})\d{4}(\d+)/, '$1****$2') : (phone || '未填写')
}
