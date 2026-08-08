// useChartExport.ts
import { ref, type Ref } from 'vue'
import html2canvas from 'html2canvas'
import type { CandleItem } from '@/types/indicators'

export function useChartExport(chartContainer: Ref<HTMLElement | null>) {
  const exporting = ref(false)

  const exportPNG = async (filename: string) => {
    if (!chartContainer.value || exporting.value) return
    exporting.value = true
    try {
      const canvas = await html2canvas(chartContainer.value, {
        backgroundColor: null,
        scale: 2
      })
      const link = document.createElement('a')
      link.download = filename
      link.href = canvas.toDataURL('image/png')
      link.click()
    } finally {
      exporting.value = false
    }
  }

  const exportCSV = (filename: string, data: CandleItem[], indicatorData?: Record<string, number[]>) => {
    const headers = ['date', 'open', 'high', 'low', 'close', 'volume']
    if (indicatorData) {
      headers.push(...Object.keys(indicatorData))
    }
    const rows = [headers.join(',')]
    data.forEach((d, i) => {
      const row = [d.time, d.open, d.high, d.low, d.close, d.volume ?? '']
      if (indicatorData) {
        for (const key of Object.keys(indicatorData)) {
          row.push(indicatorData[key][i] ?? '')
        }
      }
      rows.push(row.join(','))
    })
    const blob = new Blob([rows.join('\n')], { type: 'text/csv;charset=utf-8;' })
    const link = document.createElement('a')
    link.download = filename
    link.href = URL.createObjectURL(blob)
    link.click()
    URL.revokeObjectURL(link.href)
  }

  return { exporting, exportPNG, exportCSV }
}
