# ADR-008: 正方会话建立与数据拉取（已由抓包确认）

## 状态

已接受

## 背景

课表数据在正方系统（`jwxt.gzus.edu.cn/jwglxt`），需要 CAS 票据跳转建立会话。
用户通过抓包提供了完整的 302 跳转链、请求参数与 Cookie 信息。

## 决策：会话建立流程

```
① POST https://cas.gzus.edu.cn/lyuapServer/v1/tickets
   Content-Type: application/x-www-form-urlencoded
   body: username=<学号>
         password=<倒序 + 无填充RSA + hex>
         service=https://ehall.gzus.edu.cn
         loginType=            (空字符串)
         id=<验证码 uid>
         code=<验证码答案>
   → 200 JSON，Set-Cookie: CASTGC=<...>（会话票据 Cookie）

② POST https://cas.gzus.edu.cn/lyuapServer/v1/tickets/{TGT}
   body: loginToken=loginToken
         service=https://jwxt.gzus.edu.cn/sso/lyiotlogin
   → 200 JSON，返回 ST（Service Ticket）

③ GET https://jwxt.gzus.edu.cn/sso/lyiotlogin?ticket={ST}
   → 302 → /jwglxt/ticketlogin?uid=..&timestamp=..&verify=..
   → 302 → /jwglxt/xtgl/login_slogin.html
   → 302 → /jwglxt/xtgl/index_initMenu.html
   会话 Cookie 在此建立（JSESSIONID, route）

④ POST https://jwxt.gzus.edu.cn/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151
   body: kclbdm=  kclxdm=  kzlx=ck  xnm=2026  xqm=3  xsdm=
   → 200 JSON { xsxx, xqjmcMap, kbList[] }
```

## 关键点

| 项 | 结论 |
|---|---|
| **`verify` 参数** | 由服务端 302 生成，**客户端无需计算**（跟着重定向走即可） |
| **Cookie 传递** | 必须**保持 CookieJar**，且 **自动跟随 302** |
| **CAS 会话 Cookie** | `CASTGC`（GetTickets 用） |
| **正方会话 Cookie** | `JSESSIONID` + `route`（TGC 换完后建立） |
| **学期编码** | 2026-2027 第 1 学期 = `xnm=2026, xqm=3` |
| **课表接口** | `xskbcx_cxXsgrkb.html`（注意是 grkb，非 Kb） |

## 凭据策略（ADR-005 不变）

- 账密只存内存，登录后**立即登出**
- **不持久化 CookieJar**（每次同步重新走完整流程）
- 会话用完即弃

## 影响

**变容易：**
- 完整链路已确认，可直接实现
- 课表接口参数明确（`kzlx=ck, xnm, xqm`）
- 会话机制清楚（标准 CAS + 正方自有 ticketlogin）

**风险与未验证项：**
| 风险 | 说明 | 缓解 |
|---|---|---|
| **IP 绑定** | 若 `verify` 绑 IP，手机换网会失败 | 实测验证 |
| **成绩/考试接口未验响应** | 参数已知，但响应格式未见（学生无数据） | 写好解析器+容错，有数据时再校准 |
| **未在他机验证** | 本流程基于抓包分析，**尚未在真机跑通** | 首次运行时看诊断面板 |

## 后续

- 待补：成绩解析器（`cjcx_cxXsgrcj.html`）
- 待补：考试解析器（`kscx_cxXsksxxIndex.html`）
- 待验证：换网络环境后票据链是否仍可用
