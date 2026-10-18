import test from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import { commandTool } from '../integration/shell.mjs';

const quote = s => "'" + s.replaceAll("'", process.platform === 'win32' ? "''" : "'\\''") + "'";
for (const mode of ['exit', 'timeout', 'cancel']) {
  test(`shell returns on ${mode} despite detached descendant holding stdio`, { timeout: 6000 }, async () => {
    // Self-expiry is a fallback if the assertion or cleanup fails.
    const source = `const {spawn}=require('node:child_process'); const c=spawn(process.execPath,['-e','setTimeout(()=>{},10000)'],{detached:true,stdio:['ignore','inherit','inherit']}); console.log('descendant='+c.pid); c.unref(); ${mode === 'exit' ? '' : 'setTimeout(()=>{},10000);'}`;
    const command = `${process.platform === 'win32' ? '& ' : ''}${quote(process.execPath)} -e ${quote(source)}`;
    const controller = new AbortController();
    let pid, updates = 0;
    const start = performance.now();
    try {
      const pending = commandTool(os.tmpdir()).execute(mode, { command, timeout: mode === 'timeout' ? 1 : 5 }, controller.signal, update => {
        updates++;
        const match = update.content[0].text.match(/descendant=(\d+)/);
        if (match) { pid = Number(match[1]); if (mode === 'cancel') controller.abort(); }
      });
      if (mode === 'cancel') await assert.rejects(pending, /cancelled/);
      else {
        const result = await pending;
        assert.equal(result.details.timedOut, mode === 'timeout');
        if (mode === 'exit') assert.equal(result.details.exitCode, 0);
        assert.match(result.content[0].text, /descendant=/);
      }
      assert.ok(performance.now() - start < (mode === 'timeout' ? 2500 : 2000), 'completion must not wait for descendant');
      const count = updates;
      await new Promise(resolve => setTimeout(resolve, 100));
      assert.equal(updates, count, 'no output updates after completion');
    } finally {
      controller.abort();
      if (pid) try { process.kill(pid, 'SIGKILL'); } catch { }
    }
  });
}
