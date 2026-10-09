import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';

export async function connectMcp(servers, cwd) {
  const clients = [], tools = [], names = new Set();
  try {
    for (const server of servers || []) {
      const client = new Client({ name: 'starweave-embedded-pi', version: '1.0.0' });
      clients.push(client);
      const headers = Object.fromEntries((server.headers || []).map(item => [item.name, item.value]));
      let transport;
      if (server.type === 'http') transport = new StreamableHTTPClientTransport(new URL(server.url), { requestInit: { headers } });
      else if (server.type === 'sse') transport = new SSEClientTransport(new URL(server.url), { requestInit: { headers }, eventSourceInit: { fetch: (url, init) => fetch(url, { ...init, headers: { ...headers, ...init?.headers } }) } });
      else {
        const env = { ...process.env, ...Object.fromEntries((server.env || []).map(item => [item.name, item.value])) };
        transport = new StdioClientTransport({ command: server.command, args: server.args || [], cwd, env, stderr: 'pipe' });
        transport.stderr?.on('data', data => process.stderr.write(data));
      }
      await client.connect(transport, { timeout: 15000 });
      let cursor;
      do {
        const result = await client.listTools(cursor ? { cursor } : undefined, { timeout: 15000 });
        for (const tool of result.tools) {
          // Preserve unique tool names: harness instructions refer to names supplied by MCP.
          let name = tool.name;
          if (names.has(name)) name = `${server.name}_${tool.name}`.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 64);
          if (names.has(name)) throw new Error(`Duplicate MCP tool: ${name}`);
          names.add(name);
          tools.push({ name, label: tool.title || name, description: tool.description || name,
            parameters: tool.inputSchema,
            async execute(_id, args, signal) {
              const response = await client.callTool({ name: tool.name, arguments: args }, undefined, { signal, timeout: 120000 });
              if (response.isError) throw new Error(JSON.stringify(response.content));
              return { content: (response.content || []).map(block => {
                if (block.type === 'text' || block.type === 'image') return block;
                return { type: 'text', text: JSON.stringify(block) };
              }), details: response.structuredContent || {} };
            }
          });
        }
        cursor = result.nextCursor;
      } while (cursor);
    }
    return { tools, close: () => Promise.allSettled(clients.map(client => client.close())) };
  } catch (error) {
    await Promise.allSettled(clients.map(client => client.close()));
    throw error;
  }
}
