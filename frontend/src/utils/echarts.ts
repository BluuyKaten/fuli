/**
 * ECharts 按需引入：只注册项目实际用到的图表类型与组件，
 * 避免 `import * as echarts from 'echarts'` 全量打包（约 1MB）。
 */
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer
])

export default echarts
