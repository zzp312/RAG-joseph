/**
 * 工作流步骤占位兜底工具(纯函数,可独立测试)
 * <p>当后端 SSE 被 Netty 缓冲、step 事件攒批发送时,前端要兜底显示"正在执行 [上一节点]..."。
 * 该纯函数只判断"在 fallback 阈值内是否应展示 fallback 占位",不持有任何状态。</p>
 *
 * @author Joseph
 */

import type { WorkflowStep } from './useWorkflowSteps'

/**
 * 判断当前是否应显示"上一节点执行中"的 fallback 占位
 *
 * @param steps          已收到的 step 列表(按时间升序)
 * @param now            当前时间(毫秒,前端时钟)
 * @param lastStepAt     最后一个 step 到达时间(毫秒,若从未收到 step 则为 null)
 * @param fallbackMs     多久没新 step 就触发 fallback(毫秒)
 * @returns true = 应在 UI 上追加"正在执行 [最后一个 step]..."占位
 */
export function shouldShowFallbackPlaceholder(
  steps: WorkflowStep[],
  now: number,
  lastStepAt: number | null,
  fallbackMs: number
): boolean {
  // 阈值非正 = 关闭 fallback
  if (fallbackMs <= 0) return false
  // 从未收到 step:不显示 fallback(避免和已有的"思考中"占位气泡重叠)
  if (lastStepAt === null) return false
  // 距上次 step 超过阈值才显示,让 UI 看起来"卡在上一节点"
  return now - lastStepAt >= fallbackMs
}

/**
 * 生成 fallback 占位的展示文本(供 UI 渲染)
 *
 * @param steps 已收到的 step 列表
 * @returns 占位文本,如 "正在执行 检索...";若 steps 为空则返回 null
 */
export function buildFallbackText(steps: WorkflowStep[]): string | null {
  if (steps.length === 0) return null
  const last = steps[steps.length - 1]
  // 截断 step 内容(>12 字符),避免占位气泡过长
  const truncated = last.content.length > 12 ? last.content.slice(0, 12) + '…' : last.content
  return `正在执行 ${truncated}…`
}
