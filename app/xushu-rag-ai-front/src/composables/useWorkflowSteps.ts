import { ref, computed, nextTick } from 'vue'

/**
 * 工作流步骤数据模型（内联展示版本）
 * <p>步骤紧贴AI答案气泡展示，支持折叠、耗时统计</p>
 *
 * @author Joseph
 */
export interface WorkflowStep {
  /** 步骤类型: thinking/tool/error */
  type: 'thinking' | 'tool' | 'error'
  /** 步骤描述文本 */
  content: string
  /** 到达时间戳 */
  timestamp: number
  /** 单步耗时（毫秒），后续可由后端提供 */
  durationMs?: number
}

/** 步骤图标映射 */
const STEP_ICONS: Record<string, string> = {
  thinking: '💭',
  tool: '🔧',
  error: '❌'
}

/**
 * 步骤状态管理组合式函数（内联展示版）
 *
 * @author Joseph
 */
export function useWorkflowSteps() {
  /** 步骤列表 */
  const steps = ref<WorkflowStep[]>([])

  /** 总开始时间 */
  let startTime = 0

  /** 折叠状态（默认折叠） */
  const collapsed = ref(false)

  /** 总耗时（ms） */
  const totalDurationMs = ref(0)

  /** 是否完成 */
  const completed = ref(false)

  /** 有无步骤 */
  const hasSteps = computed(() => steps.value.length > 0)

  /** 步骤数量 */
  const stepCount = computed(() => steps.value.length)

  /** 格式化耗时 */
  function formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`
    return `${(ms / 1000).toFixed(1)}s`
  }

  /** 获取步骤图标 */
  function getStepIcon(type: string): string {
    return STEP_ICONS[type] || '📌'
  }

  /**
   * 开始一轮新的执行（清空旧状态）
   */
  function startSession() {
    steps.value = []
    collapsed.value = false
    completed.value = false
    totalDurationMs.value = 0
    startTime = Date.now()
  }

  /**
   * 添加步骤（去重：同内容不重复添加）
   */
  function addStep(type: string, content: string) {
    const exists = steps.value.some(s => s.content === content)
    if (exists) return

    const now = Date.now()
    // 第一个步骤的 durationMs = 0，后续步骤的 durationMs = 距离上一步骤的耗时
    const lastTs = steps.value.length > 0
      ? steps.value[steps.value.length - 1].timestamp
      : startTime
    const duration = now - lastTs

    steps.value.push({
      type: (type as WorkflowStep['type']) || 'thinking',
      content,
      timestamp: now,
      durationMs: duration
    })
  }

  /**
   * 结束一轮执行（计算总耗时）
   */
  function endSession() {
    if (startTime > 0) {
      totalDurationMs.value = Date.now() - startTime
    }
    completed.value = true
  }

  /** 切换折叠 */
  function toggleCollapsed() {
    collapsed.value = !collapsed.value
  }

  return {
    steps,
    collapsed,
    hasSteps,
    stepCount,
    totalDurationMs,
    completed,
    getStepIcon,
    formatDuration,
    addStep,
    startSession,
    endSession,
    toggleCollapsed
  }
}
