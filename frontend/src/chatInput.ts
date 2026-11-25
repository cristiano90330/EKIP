/** 统一问答和 Agent 输入框的发送键判定，处理换行与输入法组合状态。 */

/**
 * 判断键盘事件是否表示发送，兼顾 Shift 换行和输入法合成。
 * 接收页面事件，返回布尔判定。
 * 由 AgentView.vue 的 onTextareaKeydown、AskView.vue 的 onTextareaKeydown 触发。
 */
export function isChatSendKey(event: KeyboardEvent): boolean {
  // 部分浏览器在输入法确认候选词时只报告 229，仍应让输入法处理回车。
  return event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229
}
