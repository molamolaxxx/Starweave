import { build } from 'esbuild';
import { mkdir, copyFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = path.dirname(fileURLToPath(import.meta.url));
process.chdir(root);
await mkdir('dist', { recursive: true });
await build({
  entryPoints: ['src/background.mjs', 'src/options.mjs', 'src/clipboard.mjs'], outdir: 'dist', outExtension: { '.js': '.js' },
  bundle: true, format: 'esm', platform: 'browser', target: 'chrome125', sourcemap: true,
  plugins: [{ name: 'pi-browser', setup(b) {
    b.onResolve({ filter: /^@earendil-works\/pi-agent-core$/ }, () => ({ path: path.join(root, 'node_modules/@earendil-works/pi-agent-core/dist/agent.js') }));
    b.onResolve({ filter: /^@earendil-works\/pi-ai$/ }, () => ({ path: path.join(root, 'src/pi-browser.mjs') }));
    b.onResolve({ filter: /^@earendil-works\/pi-ai\/dist\// }, args => ({ path: path.join(root, 'node_modules', args.path) }));
  }}]
});
for (const f of ['manifest.json', 'options.html', 'options.css', 'clipboard.html', 'THIRD_PARTY_NOTICES.md']) await copyFile(f, `dist/${f}`);
