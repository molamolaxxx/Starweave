// Run with Node 22.19+ and a JDK: node scripts/build-embedded-pi.mjs
// Downloads happen only during this explicit maintainer build, never during Agent startup.
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

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
const [major, minor] = process.versions.node.split('.').map(Number);
if (major < 22 || (major === 22 && minor < 19)) throw new Error('Node 22.19.0+ required');
const assets = {};
run('npm', ['ci', '--ignore-scripts', '--omit=optional', '--no-audit', '--no-fund'], resource);
await fs.rm(path.join(resource, 'runtime/dependencies.zip'), { force: true });
run('jar', ['cf', path.join(resource, 'runtime/dependencies.zip'), '-C', resource, 'node_modules']);
assets['runtime/dependencies.zip'] = digest(await fs.readFile(path.join(resource, 'runtime/dependencies.zip')));
const sourceManifest = JSON.parse(await fs.readFile(path.join(resource, 'upstream/manifest.json'), 'utf8'));
await fs.writeFile(path.join(resource, 'manifest.json'), JSON.stringify({ version: '1.0.0', minimumNodeVersion: '22.19.0', piVersion: '0.75.3', sources: sourceManifest, assets }, null, 2) + '\n');
console.log('Embedded Pi resources built and checksummed.');
