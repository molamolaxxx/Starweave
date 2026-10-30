// Pi usage fields are normalized: input excludes cacheRead/cacheWrite.
// Image tokens depend on the provider and dimensions; use a provisional budget
// until the next successful response supplies authoritative usage.
const imageTokens = 1200;
const positive = value => Number.isFinite(value) && value > 0 ? value : 0;
function textTokens(text) {
  let units = 0;
  for (const char of String(text || '')) units += char.codePointAt(0) > 127 ? 3 : 1;
  return Math.ceil(units / 3);
}
export function estimateMessage(message) {
  let tokens = 8;
  const content = typeof message.content === 'string' ? [{ type: 'text', text: message.content }] : message.content || [];
  for (const block of content) {
    if (block.type === 'image') tokens += imageTokens;
    else if (block.type === 'text') tokens += textTokens(block.text);
    else if (block.type === 'thinking') tokens += textTokens(block.thinking);
    else if (block.type === 'toolCall') tokens += textTokens(block.name) + textTokens(JSON.stringify(block.arguments || {}));
  }
  return tokens;
}
export function contextTokens(state) {
  const messages = state.messages || [];
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i];
    if (message.role !== 'assistant' || ['error', 'aborted'].includes(message.stopReason) || !message.usage) continue;
    const usage = message.usage;
    const actual = positive(usage.totalTokens) || ['input', 'output', 'cacheRead', 'cacheWrite'].reduce((sum, key) => sum + positive(usage[key]), 0);
    if (actual > 0) return actual + messages.slice(i + 1).reduce((sum, m) => sum + estimateMessage(m), 0);
  }
  // Only include system/tool definitions when there is no usage baseline.
  // A successful API response already accounts for both in its input tokens.
  const tools = (state.tools || []).map(t => ({ name: t.name, description: t.description, parameters: t.parameters }));
  return textTokens(state.systemPrompt) + textTokens(JSON.stringify(tools)) + messages.reduce((sum, m) => sum + estimateMessage(m), 0);
}
