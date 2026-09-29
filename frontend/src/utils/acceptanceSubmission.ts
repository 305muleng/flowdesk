import type { SubmitAcceptancePayload } from '@/api/projects'

export interface AcceptanceSubmissionForm {
  submissionNote: string
  repositoryUrl: string
  deployUrl: string
  documentUrl: string
}

export const emptyAcceptanceSubmission = (): AcceptanceSubmissionForm => ({
  submissionNote: '',
  repositoryUrl: '',
  deployUrl: '',
  documentUrl: '',
})

export function isValidHttpUrl(value: string): boolean {
  const scheme = /^https?:\/\//i.exec(value)
  if (!scheme || /[\s\\\u0000-\u001f\u007f"<>]|%(?![0-9a-f]{2})/i.test(value)) return false

  const rest = value.slice(scheme[0].length)
  const authority = rest.split(/[/?#]/, 1)[0] || ''
  if (!authority) return false

  let host: string
  let port: string | undefined
  if (authority.startsWith('[')) {
    const match = /^\[([0-9a-f:.]+)\](?::([0-9]+))?$/i.exec(authority)
    if (!match || !match[1]?.includes(':')) return false
    host = match[1]
    port = match[2]
    try {
      new URL(`http://[${host}]/`)
    } catch {
      return false
    }
  } else {
    const match = /^([^:]+)(?::([0-9]+))?$/.exec(authority)
    if (!match) return false
    host = match[1] || ''
    port = match[2]
    if (host.length > 253 || /[^\x00-\x7f]/.test(host)) return false
    if (/^[0-9.]+$/.test(host)) {
      const octets = host.split('.')
      if (octets.length !== 4 || octets.some((part) => !/^(0|[1-9][0-9]{0,2})$/.test(part) || Number(part) > 255)) return false
    } else if (!host.split('.').every((part) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(part))) {
      return false
    }
  }
  return port === undefined || (Number(port) >= 1 && Number(port) <= 65535)
}

export function acceptanceSubmissionError(form: AcceptanceSubmissionForm): string {
  if (!form.submissionNote.trim()) return '请填写验收说明'
  const links = [
    ['代码仓库', form.repositoryUrl],
    ['部署地址', form.deployUrl],
    ['项目文档', form.documentUrl],
  ] as const
  if (links.every(([, value]) => !value.trim())) return '请至少填写一个交付链接'
  for (const [label, value] of links) {
    if (value.trim() && !isValidHttpUrl(value.trim())) {
      return `${label}请输入有效的 http:// 或 https:// 链接`
    }
  }
  return ''
}

export function acceptanceSubmissionPayload(form: AcceptanceSubmissionForm): SubmitAcceptancePayload {
  return {
    submissionNote: form.submissionNote.trim(),
    repositoryUrl: form.repositoryUrl.trim() || null,
    deployUrl: form.deployUrl.trim() || null,
    documentUrl: form.documentUrl.trim() || null,
  }
}
