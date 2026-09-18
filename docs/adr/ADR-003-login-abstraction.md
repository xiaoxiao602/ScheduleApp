# ADR-003: 登录模块抽象 —— 端口/适配器隔离未确认的协议细节

## 状态

已接受

## 背景

对 LYUAP 认证系统的探测已完成大部分，但存在**无法用公开信息闭合的缺口**：

**已确认（可信）：**
| 项 | 值 | 证据 |
|---|---|---|
| 登录端点 | `POST https://cas.gzus.edu.cn/lyuapServer/v1/tickets` | 表单提交返回业务 JSON，其余候选 404 |
| 提交格式 | `application/x-www-form-urlencoded` | JSON 提交 → 500「请求操作失败」 |
| 验证码端点 | `GET /lyuapServer/kaptcha` | 200，返回 base64 PNG |
| 验证码格式 | `{kaptchaType, uid, content}` | 已实测解码为合法 PNG |
| 密码加密 | RSA，`public_exponent=010001`，1024bit modulus | 从 app.js 提取 |
| 加密调用 | `rsa.key(pubExp,"",modulus)` → `rsa.encrypt(k, pass)` | app.js 已定位 |
| 登录策略 | 无二次验证、无短信、图形验证码 | `GET /loginType` |
| 错误码 | `NOUSER`（用户不存在）/ `CODEFALSE`（验证码错误） | 探测确认 |

**未确认（探测极限）：**
| 项 | 为什么无法确认 |
|---|---|
| 密码字段名与编码格式 | 服务端**先查用户再校验密码**，假账号永远在 `NOUSER` 截断 |
| 验证码字段名 | 同上，被 `NOUSER` 掩盖 |
| 登录成功后的票据/Cookie 形态 | 必须真实成功登录一次 |
| CAS → ehall → 正方 的票据传递链 | 同上 |

**关键教训（实验设计缺陷）：**
最初用不存在的账号做「字段名差分探测」得到全部返回 `NOUSER`，
一度误判为「字段名正确」。实际是**假账号在用户查询阶段就被截断**，
根本没走到密码/验证码校验。**该实验对字段名探测无效。**

## 决策

将登录与数据获取抽象为**端口（interface）**，未确认部分用**可替换的适配器**实现。

```
domain/
  repository/
    AuthRepository.kt        ← 端口：定义登录契约（不含协议细节）
data/
  auth/
    LyuapAuthRepository.kt   ← 适配器：实现 LYUAP 协议（协议细节集中在此）
    RsaPasswordEncoder.kt    ← RSA 加密（公钥已确认）
    CaptchaClient.kt         ← 验证码获取（已确认）
    model/
      LoginRequest.kt        ← 参数封装（字段名待定，集中一处便于修正）
```

**约束：**
1. 协议细节（字段名、编码格式）**只允许出现在 `data/auth/` 内**，不得泄漏到 UI 或 domain
2. `LoginRequest` 的字段名定义为常量，修正时改一处
3. 登录失败错误码映射为领域异常（`UserNotFound` / `CaptchaError` / `BadCredentials`）
4. UI 层只依赖 `AuthRepository` 接口

## 备选方案

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **端口/适配器隔离** | 未确认细节集中一处；修正成本最低；可并行开发 | 多一层抽象（少量样板代码） | ✅ **采纳** |
| 等确认全部细节再动手 | 一次写对 | 阻塞整个项目；登录只占 10% 工作量 | ❌ 依赖排序错误 |
| 探测到底（暴力试字段名） | 可能试出 | 需真实账号；会触发风控；不可靠 | ❌ 明确否决 |
| 引入第三方教务 SDK | 省事 | 无覆盖此校的现成 SDK；安全性不可控 | ❌ 不存在 |

## 影响

**变容易：**
- UI / 课表 / 成绩 / 缓存 等模块**现在就能开发**，不受登录未确认影响
- 真实请求抓到后，**只改 `data/auth/model/LoginRequest.kt` 的字段名常量**
- 可写假实现（`FakeAuthRepository`）让 UI 先跑起来

**变难：**
- 多一层接口抽象（可接受，约 20 行样板）
- 需要维护「未确认」状态的显式记录（即本 ADR）

**风险处置：**
| 风险 | 处置 |
|---|---|
| 字段名假设错误 | 集中在 `LoginRequest`，改一处 |
| RSA 编码格式（base64/hex）不确定 | `RsaPasswordEncoder` 暴露两种编码，运行时可选 |
| 票据传递链不明 | `AuthRepository` 返回会话 Cookie，后续模块只依赖 Cookie 字符串 |

## 后续

- ADR-004：数据获取路径（ehall API vs 正方 HTML 解析）——待登录打通后决策
- 待补：真实抓包后填充 `LoginRequest` 字段名，并将本节「未确认」项标记为已确认
