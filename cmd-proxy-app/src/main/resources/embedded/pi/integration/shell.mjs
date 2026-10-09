import { spawn } from 'node:child_process';
import path from 'node:path';

export function shellInvocation(command, platform = process.platform, env = process.env) {
  if (platform === 'win32') {
    const executable = path.win32.join(env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
    const script = '[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(); $OutputEncoding = [Console]::OutputEncoding;\n' + command;
    return { executable, args: ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')] };
  }
  return { executable: '/bin/sh', args: ['-c', command] };
}

export function commandTool(cwd) {
  return {
    name: 'shell', label: 'Shell', description: `Execute a ${process.platform === 'win32' ? 'PowerShell' : 'POSIX shell'} command in the workspace.`,
    promptSnippet: 'Run commands in the workspace.',
    parameters: { type: 'object', properties: { command: { type: 'string' }, timeout: { type: 'number', minimum: 1, maximum: 600 } }, required: ['command'], additionalProperties: false },
    async execute(_id, { command, timeout = 120 }, signal, onUpdate) {
      signal?.throwIfAborted();
      const invocation = shellInvocation(command);
      return new Promise((resolve, reject) => {
        const child = spawn(invocation.executable, invocation.args, { cwd, windowsHide: true, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
        let output = '', timedOut = false;
        const stop = () => {
          if (child.pid && process.platform === 'win32') {
            const killer = spawn(path.win32.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'taskkill.exe'), ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
            killer.on('error', () => child.kill());
            killer.on('exit', code => { if (code !== 0) child.kill(); });
          } else if (child.pid) {
            try { process.kill(-child.pid, 'SIGKILL'); } catch { child.kill('SIGKILL'); }
          }
        };
        const timer = setTimeout(() => { timedOut = true; stop(); }, Math.min(600, Math.max(1, timeout)) * 1000);
        signal?.addEventListener('abort', stop, { once: true });
        const cleanup = () => { clearTimeout(timer); signal?.removeEventListener('abort', stop); };
        const collect = data => {
          output = (output + data.toString('utf8')).slice(-64000);
          onUpdate?.({ content: [{ type: 'text', text: output }], details: {} });
        };
        child.stdout.on('data', collect); child.stderr.on('data', collect);
        child.on('error', error => { cleanup(); reject(error); });
        child.on('close', code => {
          cleanup();
          if (signal?.aborted) return reject(new Error('Command cancelled'));
          resolve({ content: [{ type: 'text', text: output + (timedOut ? '\nCommand timed out.' : `\nExit code: ${code}`) }], details: { exitCode: code, timedOut } });
        });
      });
    }
  };
}
