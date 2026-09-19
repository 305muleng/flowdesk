<script setup lang="ts">
import { BarChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { init, use, type ECharts, type EChartsCoreOption } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

use([BarChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ option: EChartsCoreOption }>()
const element = ref<HTMLElement>()
let chart: ECharts | undefined
const resize = () => chart?.resize()

const render = async () => {
  await nextTick()
  if (!element.value) return
  chart ||= init(element.value)
  chart.setOption(props.option, true)
}

watch(() => props.option, render, { deep: true })
onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
})
</script>

<template><div ref="element" class="chart" /></template>
<style scoped>
.chart {
  width: 100%;
  height: 280px;
}
</style>
