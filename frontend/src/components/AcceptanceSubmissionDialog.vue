<script setup lang="ts">
import { ref, watch } from 'vue'
import type { SubmitAcceptancePayload } from '@/api/projects'
import {
  acceptanceSubmissionError,
  acceptanceSubmissionPayload,
  emptyAcceptanceSubmission,
} from '@/utils/acceptanceSubmission'

const props = defineProps<{
  modelValue: boolean
  submitAction: (payload: SubmitAcceptancePayload) => Promise<void>
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const form = ref(emptyAcceptanceSubmission())
const error = ref('')
const submitting = ref(false)

watch(() => props.modelValue, (open) => {
  if (open) {
    form.value = emptyAcceptanceSubmission()
    error.value = ''
  }
})

async function submit() {
  if (submitting.value) return
  error.value = acceptanceSubmissionError(form.value)
  if (error.value) return
  submitting.value = true
  try {
    await props.submitAction(acceptanceSubmissionPayload(form.value))
    emit('update:modelValue', false)
  } catch {
    // The shared request layer displays the server's error; keep the form open.
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="提交项目验收"
    width="min(520px, 94vw)"
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <el-form-item label="验收说明" required>
        <el-input v-model="form.submissionNote" type="textarea" :rows="3" placeholder="说明交付范围、测试情况与验收依据" />
      </el-form-item>
      <p class="link-hint">至少填写一个交付链接，仅支持 http:// 或 https://。</p>
      <el-form-item label="代码仓库">
        <el-input v-model="form.repositoryUrl" maxlength="500" placeholder="https://example.com/repository" />
      </el-form-item>
      <el-form-item label="部署地址">
        <el-input v-model="form.deployUrl" maxlength="500" placeholder="https://example.com/app" />
      </el-form-item>
      <el-form-item label="项目文档">
        <el-input v-model="form.documentUrl" maxlength="500" placeholder="https://example.com/document" />
      </el-form-item>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    </el-form>
    <template #footer>
      <el-button :disabled="submitting" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交验收</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.link-hint { margin: 0 0 14px; color: var(--muted); font-size: 12px; }
</style>
