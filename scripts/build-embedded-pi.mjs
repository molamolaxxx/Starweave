// Run with Node 22+ and a JDK: node scripts/build-embedded-pi.mjs
// Downloads happen only during this explicit maintainer build, never during Agent startup.
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const resource = path.join(root, 'cmd-proxy-app/src/main/resources/embedded/pi');
const cache = path.join(root, 'cmd-proxy-app/target/embedded-pi-build');
await fs.mkdir(cache, { recursive: true });
await fs.mkdir(path.join(resource, 'runtime'), { recursive: true });
const run = (command, args, cwd = cache) => {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' });
  if (result.error || result.status !== 0) throw result.error || new Error(`${command} failed: ${result.status}`);
};
const digest = data => createHash('sha256').update(data).digest('hex');
const download = async (url, file) => {
  try { return await fs.readFile(file); } catch {}
  const response = await fetch(url); if (!response.ok) throw new Error(`${response.status}: ${url}`);
  const data = Buffer.from(await response.arrayBuffer()); await fs.writeFile(file, data); return data;
};
const nodeVersion = '24.21.0';
const sums = (await download(`https://nodejs.org/dist/v${nodeVersion}/SHASUMS256.txt`, path.join(cache, 'SHASUMS256.txt'))).toString();
const assets = {};
for (const platform of ['linux-x64', 'win-x64']) {
  const distribution = platform.startsWith('win') ? platform : 'linux-x64-glibc-217';
  const base = platform.startsWith('win') ? `https://nodejs.org/dist/v${nodeVersion}` : `https://unofficial-builds.nodejs.org/download/release/v${nodeVersion}`;
  const platformSums = platform.startsWith('win') ? sums : (await download(`${base}/SHASUMS256.txt`, path.join(cache, 'SHASUMS256-unofficial.txt'))).toString();
  const archiveName = `node-v${nodeVersion}-${distribution}.${platform.startsWith('win') ? 'zip' : 'tar.xz'}`;
  const archive = path.join(cache, archiveName);
  const data = await download(`${base}/${archiveName}`, archive);
  const expected = platformSums.split('\n').find(line => line.endsWith(`  ${archiveName}`))?.split(' ')[0];
  if (digest(data) !== expected) throw new Error(`Node checksum mismatch: ${archiveName}`);
  const folder = `node-v${nodeVersion}-${distribution}`;
  if (platform.startsWith('win')) run('jar', ['xf', archive, `${folder}/node.exe`, `${folder}/LICENSE`]);
  else run('tar', ['-xJf', archive, `${folder}/bin/node`, `${folder}/LICENSE`]);
  const compressed = gzipSync(await fs.readFile(path.join(cache, folder, platform.startsWith('win') ? 'node.exe' : 'bin/node')), { level: 9 });
  const name = `runtime/node-${platform}.gz`;
  await fs.writeFile(path.join(resource, name), compressed);
  assets[name] = digest(compressed);
  await fs.copyFile(path.join(cache, folder, 'LICENSE'), path.join(resource, 'runtime', `NODE-LICENSE-${platform}.txt`));
}
run('npm', ['ci', '--ignore-scripts', '--omit=optional', '--no-audit', '--no-fund'], resource);
await fs.rm(path.join(resource, 'runtime/dependencies.zip'), { force: true });
run('jar', ['cf', path.join(resource, 'runtime/dependencies.zip'), '-C', resource, 'node_modules']);
assets['runtime/dependencies.zip'] = digest(await fs.readFile(path.join(resource, 'runtime/dependencies.zip')));
const sourceManifest = JSON.parse(await fs.readFile(path.join(resource, 'upstream/manifest.json'), 'utf8'));
await fs.writeFile(path.join(resource, 'manifest.json'), JSON.stringify({ version: '1.0.0', nodeVersion, piVersion: '0.75.3', sources: sourceManifest, assets }, null, 2) + '\n');
console.log('Embedded Pi resources built and checksummed.');
