// Offscreen documents support runtime messaging and DOM clipboard commands.
chrome.runtime.onMessage.addListener((message, sender, reply) => {
  if (sender.id !== chrome.runtime.id || message.target !== 'starweave-clipboard') return false;
  try {
    const field = document.getElementById('clipboard'); field.value = ''; field.focus();
    if (message.action === 'write') { field.value = String(message.text ?? ''); field.select(); if (!document.execCommand('copy')) throw new Error('复制失败'); reply({ written: true }); }
    else if (message.action === 'read') { if (!document.execCommand('paste')) throw new Error('粘贴失败'); reply({ text: field.value }); }
    else throw new Error('未知剪贴板操作');
    field.value = '';
  } catch (e) { reply({ error: e.message }); }
  return false;
});
