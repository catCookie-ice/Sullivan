// 生成 E2E 运行配置：读基础配置 + 注入调试令牌，输出 *.run.json。
// 用 Node 而不是 PowerShell 是为了避免 PS 5.1 对无 BOM UTF-8 的编码破坏（会打乱中文断言文案）。
import { readFileSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'

const [basePath, outPath, subject, principalType, roles, perms] = process.argv.slice(2)
if (!basePath || !outPath || !subject) {
  console.error('用法: node tools/make-e2e-config.mjs <base.json> <out.json> <subject> <principalType> <roles> <perms>')
  process.exit(2)
}

const token = execFileSync(
  process.execPath,
  ['tools/mint-token.mjs', subject, principalType, roles || '', perms || ''],
  { encoding: 'utf8' },
)

const cfg = JSON.parse(readFileSync(basePath, 'utf8'))
cfg.token = token
cfg.principalId = subject
cfg.identity = {
  principalId: subject,
  principalType,
  roles: (roles || '').split(',').filter(Boolean),
  permissions: (perms || '').split(',').filter(Boolean),
}

writeFileSync(outPath, JSON.stringify(cfg, null, 2), 'utf8')
console.log(`wrote ${outPath} (token ${token.length} chars)`)
