// Verifies bundled Pi dependencies and executes them with system Node on Linux or Windows.
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const resource = path.join(root, 'cmd-proxy-app/src/main/resources/embedded/pi');
const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'starweave-pi-test '));
try {
  const manifest = JSON.parse(await fs.readFile(path.join(resource, 'manifest.json'), 'utf8'));
  const [major, minor] = process.versions.node.split('.').map(Number);
  if (major < 22 || (major === 22 && minor < 19)) throw new Error(`Node ${manifest.minimumNodeVersion}+ required`);
  const verify = async name => {
    const data = await fs.readFile(path.join(resource, name));
    if (createHash('sha256').update(data).digest('hex') !== manifest.assets[name]) throw new Error(`Checksum mismatch: ${name}`);
    return data;
  };
  const dependencies = path.join(temp, 'dependencies.zip'); await fs.writeFile(dependencies, await verify('runtime/dependencies.zip'));
  const extract = spawnSync('jar', ['xf', dependencies], { cwd: temp, stdio: 'inherit' });
  if (extract.error || extract.status !== 0) throw extract.error || new Error('Dependency extraction failed');
  await fs.cp(path.join(resource, 'integration'), path.join(temp, 'integration'), { recursive: true });
  await fs.cp(path.join(resource, 'test'), path.join(temp, 'test'), { recursive: true });
  const result = spawnSync(process.execPath, ['--test', path.join(temp, 'test/agent.test.mjs')], { cwd: temp, stdio: 'inherit', timeout: 120000 });
  if (result.error || result.status !== 0) throw result.error || new Error(`Embedded Pi tests failed: ${result.status}`);
} finally { await fs.rm(temp, { recursive: true, force: true }); }
