export function toApiDate(date: string, time = '10:00') {
  return date ? `${date}T${time}:00` : null
}
export function shortDate(value?: string) {
  if (!value) return '未设置'
  const d = new Date(value.replace(' ', 'T'))
  const now = new Date()
  const date = `${d.getMonth() + 1}月${d.getDate()}日`
  if (d.toDateString() === now.toDateString()) return `今天 ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return `${date} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
export function today(offset = 0) {
  const d = new Date(); d.setDate(d.getDate() + offset)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
export function currentMonth() {
  const d = new Date(); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`
}
const pad = (n: number) => String(n).padStart(2, '0')
