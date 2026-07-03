import { ref, computed, nextTick } from 'vue'
import { shouldShowFallbackPlaceholder, buildFallbackText } from './workflowFallback'

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

  /** fallback 占位:距上次 step 超过该阈值(毫秒)就显示"正在执行 [上一节点]..." */
  const FALLBACK_THRESHOLD_MS = 1500

  /** fallback 占位是否可见(派生状态,定时器周期性刷新) */
  const fallbackVisible = ref(false)

  /** fallback 占位的展示文本 */
  const fallbackText = ref<string | null>(null)

  /** fallback 定时器句柄 */
  let fallbackTimer: ReturnType<typeof setInterval> | null = null

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
    // 启动 fallback 定时器:每 500ms 检查一次,距上次 step 超过阈值就显示占位
    startFallbackTimer()
  }

  /**
   * 添加步骤（去重：同内容不重复添加）
   *
   * @param type    步骤类型
   * @param content 步骤描述
   * @param ts      节点完成时刻的服务端时间戳(毫秒,可选);
   *                传入时 durationMs 用后端时间计算,避免后端攒批发送时
   *                第一个 step 的耗时被错误算成"首字节到现在的总耗时"
   */
  function addStep(type: string, content: string, ts?: number) {
    const exists = steps.value.some(s => s.content === content)
    if (exists) return

    // 优先使用后端节点完成时间戳,缺失时再回退到前端时间
    const now = typeof ts === 'number' ? ts : Date.now()
    // 第一个 step 的 durationMs = 0(没有"上一节点"可比),
    // 后续 step 的 durationMs = 该节点完成后端时间 - 上一节点完成后端时间
    const lastTs = steps.value.length > 0
      ? steps.value[steps.value.length - 1].timestamp
      : null
    const duration = lastTs === null ? 0 : Math.max(0, now - lastTs)

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
    // 停止 fallback 定时器,清理占位状态
    stopFallbackTimer()
    fallbackVisible.value = false
    fallbackText.value = null
  }

  /**
   * 启动 fallback 定时器(每 500ms 检查一次,决定 fallback 占位是否可见)
   * <p>针对后端 SSE 被 Netty 写缓冲/Nagle 算法攒批的情况,前端兜底显示
   * "正在执行 [上一个 step]..."避免 UI 长时间停留在"思考中"占位</p>
   */
  function startFallbackTimer() {
    stopFallbackTimer()
    fallbackTimer = setInterval(refreshFallback, 500)
  }

  /**
   * 停止 fallback 定时器
   */
  function stopFallbackTimer() {
    if (fallbackTimer !== null) {
      clearInterval(fallbackTimer)
      fallbackTimer = null
    }
  }

  /**
   * 由定时器调用,周期性计算 fallback 状态
   * <p>完全基于纯函数 shouldShowFallbackPlaceholder / buildFallbackText,
   * 这两个函数由 Node test 覆盖</p>
   */
  function refreshFallback() {
    const lastTs = steps.value.length > 0
      ? steps.value[steps.value.length - 1].timestamp
      : null
    const visible = shouldShowFallbackPlaceholder(
      steps.value,
      Date.now(),
      lastTs,
      FALLBACK_THRESHOLD_MS
    )
    fallbackVisible.value = visible
    fallbackText.value = visible ? buildFallbackText(steps.value) : null
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
    fallbackVisible,
    fallbackText,
    getStepIcon,
    formatDuration,
    addStep,
    startSession,
    endSession,
    toggleCollapsed
  }
}
