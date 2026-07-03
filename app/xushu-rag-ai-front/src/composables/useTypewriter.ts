import { ref, onUnmounted } from 'vue'

/**
 * 打字机效果配置
 */
export interface UseTypewriterOptions {
  /** 每个字符的最小间隔（毫秒） */
  minDelay?: number
  /** 每个字符的最大间隔（毫秒） */
  maxDelay?: number
}

/**
 * 打字机效果组合式函数
 * <p>将追加的文本按字符逐步输出，模拟流式打字效果；多个字符会合并为一次 DOM 更新，
 * 降低频繁渲染带来的开销。</p>
 *
 * @author Joseph
 * @param options 打字速度配置
 */
export function useTypewriter(options: UseTypewriterOptions = {}) {
  const { minDelay = 15, maxDelay = 35 } = options

  /** 当前已显示文本 */
  const displayedText = ref('')
  /** 待输出的文本缓冲区 */
  const buffer = ref('')

  let isTyping = false
  let timerId: number | null = null

/**
 * 计算下一次输出的字符数
 * <p>缓冲区越长，一次输出越多，但上限较低，保证打字机效果可见；
 * 即使是大段文本也能感受到逐字推进。</p>
 */
function calcBurstSize(): number {
  return buffer.value.length > 80 ? 2 : 1
}


  /**
   * 输出下一个（或一组）字符并调度下一次
   */
  function scheduleNext() {
    if (buffer.value.length === 0) {
      isTyping = false
      return
    }

    const burstSize = calcBurstSize()
    const chunk = buffer.value.slice(0, burstSize)
    buffer.value = buffer.value.slice(burstSize)
    displayedText.value += chunk

    // 随机间隔模拟真实打字节奏
    const delay = Math.floor(minDelay + Math.random() * (maxDelay - minDelay))
    timerId = window.setTimeout(scheduleNext, delay)
  }

  /**
   * 追加文本到打字缓冲区
   * @param text 待追加文本
   */
  function append(text: string) {
    if (!text) return
    buffer.value += text
    if (!isTyping) {
      isTyping = true
      scheduleNext()
    }
  }

  /**
   * 重置打字机状态
   */
  function reset() {
    if (timerId !== null) {
      clearTimeout(timerId)
      timerId = null
    }
    displayedText.value = ''
    buffer.value = ''
    isTyping = false
  }

  /**
   * 立即将缓冲区剩余内容全部输出
   */
  function flush() {
    if (timerId !== null) {
      clearTimeout(timerId)
      timerId = null
    }
    displayedText.value += buffer.value
    buffer.value = ''
    isTyping = false
  }

  onUnmounted(reset)

  return {
    displayedText,
    buffer,
    append,
    reset,
    flush
  }
}
