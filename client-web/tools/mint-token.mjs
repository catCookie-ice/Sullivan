// 本地联调用：按 server-java 的 JwtService 规则签一个访问令牌。
//
// 为什么需要它：本机数据库里的账号密码未知（都是 BCrypt 散列），而改库里的
// password_hash 会污染开发数据。这里用 .env 里同一个 app.jwt.secret 直接签发，
// 走的是后端 JwtAuthenticationFilter 完全相同的校验路径，等价于真实登录态。
//
// 用法：node tools/mint-token.mjs <subject> <principalType> <roles,逗号分隔> <perms,逗号分隔>
// 仅供本地调试，勿用于任何真实环境。
import { createHmac } from 'node:crypto'

// 密钥只从环境变量读（与 server-java/.env 的 app.jwt.secret 同源），不入库。
// 不设兜底值：仓库里出现真实密钥比「少打一行环境变量」严重得多。
const SECRET = process.env.SULLIVAN_JWT_SECRET
if (!SECRET) {
  console.error('缺少环境变量 SULLIVAN_JWT_SECRET（取 server-java/server-java/.env 里的 app.jwt.secret）')
  process.exit(2)
}
const TTL_SECONDS = 7200

const [subject, principalType, rolesArg = '', permsArg = ''] = process.argv.slice(2)
if (!subject || !principalType) {
  console.error('用法: node tools/mint-token.mjs <subject> <principalType> [roles] [perms]')
  process.exit(2)
}

const b64url = (buf) =>
  Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

const now = Math.floor(Date.now() / 1000)
const header = { alg: 'HS256' }
const payload = {
  sub: subject,
  principal_type: principalType,
  roles: rolesArg ? rolesArg.split(',').filter(Boolean) : [],
  permissions: permsArg ? permsArg.split(',').filter(Boolean) : [],
  iat: now,
  exp: now + TTL_SECONDS,
}

const signingInput = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`
const signature = createHmac('sha256', SECRET).update(signingInput).digest()
process.stdout.write(`${signingInput}.${b64url(signature)}`)
