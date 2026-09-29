import { ElMessage, ElMessageBox } from 'element-plus'
import { reviewAcceptanceApi } from '@/api/acceptances'

export async function promptReviewAcceptance(id: number, action: 'APPROVE' | 'REJECT') {
  try {
    const { value } = await ElMessageBox.prompt(
      '填写审核意见',
      action === 'APPROVE' ? '通过项目验收' : '驳回项目验收',
      {
        inputType: 'textarea',
        inputPlaceholder:
          action === 'APPROVE' ? '确认项目交付满足验收标准' : '说明需要补充或修改的内容',
        ...(action === 'REJECT'
          ? { inputPattern: /\S+/, inputErrorMessage: '请填写驳回原因' }
          : {}),
      },
    )
    await reviewAcceptanceApi(id, { action, reviewNote: value?.trim() || undefined })
    ElMessage.success('验收审核已完成')
    return true
  } catch (error) {
    if (error === 'cancel' || error === 'close') return false
    if (!(error instanceof Error && 'response' in error)) {
      ElMessage.error(error instanceof Error ? error.message : '审核失败，请稍后重试')
    }
    return false
  }
}
