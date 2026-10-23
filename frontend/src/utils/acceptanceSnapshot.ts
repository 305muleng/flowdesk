export function acceptancePresentation(source: string | null | undefined) {
  if (source === 'SUBMISSION_SNAPSHOT') {
    return {
      isSnapshot: true,
      prefix: '本轮提交时的',
      label: '本轮提交快照',
      notice: '以下项目资料、交付链接、任务统计和成员贡献已固定为本轮提交时的数据；当前项目状态和审核结果继续反映最新进展。外链内容本身未归档。',
      memberDescription: '包含提交时保留的历史成员，姓名、项目角色及贡献均按本轮提交时的数据展示。',
    }
  }
  if (source === 'LEGACY_CURRENT') {
    return {
      isSnapshot: false,
      prefix: '当前',
      label: '旧记录 · 当前数据',
      notice: '此记录未保存提交快照，下列内容为当前数据，无法还原提交时状态。',
      memberDescription: '包含已退出或被移除的历史成员；姓名、角色及贡献来自当前保留的数据，并非当时的快照。',
    }
  }
  return {
    isSnapshot: false,
    prefix: '数据来源待确认的',
    label: '数据来源未确认',
    notice: '无法确认数据来源，请刷新页面或联系管理员；不能将这些内容视为提交时快照。',
    memberDescription: '数据来源尚未确认。',
  }
}
