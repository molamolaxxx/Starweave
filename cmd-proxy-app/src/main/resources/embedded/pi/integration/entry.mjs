import { Writable, Readable } from 'node:stream';
import fs from 'node:fs/promises';
import { AgentSideConnection, ndJsonStream } from '@agentclientprotocol/sdk';

const log = (...args) => process.stderr.write(args.map(value => typeof value === 'string' ? value : JSON.stringify(value)).join(' ') + '\n');
console.log = console.info = console.warn = console.debug = log;
let agent;
try {
  const config = JSON.parse(process.env.STARWEAVE_PI_CONFIG || '{}');
  // Set before loading Pi: its configuration module resolves directories at import time.
  process.env.PI_CODING_AGENT_DIR = config.agentDir;
  await fs.mkdir(config.agentDir, { recursive: true });
  if (process.env.HTTP_PROXY || process.env.HTTPS_PROXY || process.env.http_proxy || process.env.https_proxy) {
    const { EnvHttpProxyAgent, setGlobalDispatcher } = await import('undici');
    setGlobalDispatcher(new EnvHttpProxyAgent());
  }
  const { EmbeddedPiAgent } = await import('./agent.mjs');
  const connection = new AgentSideConnection(conn => (agent = new EmbeddedPiAgent(conn, config)),
    ndJsonStream(Writable.toWeb(process.stdout), Readable.toWeb(process.stdin)));
  const shutdown = async () => { await agent?.shutdown(); process.exit(0); };
  process.on('SIGTERM', shutdown); process.on('SIGINT', shutdown);
  await connection.closed; await shutdown();
} catch (error) {
  // Configuration errors never include credentials or the complete configuration.
  log('内嵌 Pi 启动失败:', String(error.message || error).replaceAll(JSON.parse(process.env.STARWEAVE_PI_CONFIG || '{}').apiKey || '\0', '[REDACTED]'));
  await agent?.shutdown(); process.exitCode = 1;
}
