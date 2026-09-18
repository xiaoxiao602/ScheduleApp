# ADR-010: 允许校方域名的明文流量（网络安全配置）

## 状态

已接受

## 背景

真机诊断暴露了会话建立失败的确切原因：

```
换票响应: ST-035148-5588c0f0d6af4a9699a24a903a9034a7     ← 换票成功
取得 jwxt ST: ST-035148-...                                ← 拿到 jwxt 专属 ST
预热跳转 -> HTTP 200                                       ← 预热成功
会话跳转异常: CLEARTEXT communication to jwxt.gzus.edu.cn
             not permitted by network security policy
```

回溯抓包发现，正方登录跳转链中**服务端会将 302 降级为明文 http://**：

```
GET https://jwxt.gzus.edu.cn/sso/lyiotlogin?ticket=ST-..
  -> 302 http://jwxt.gzus.edu.cn/jwglxt/ticketlogin?uid=..&verify=..
  -> 302 http://jwxt.gzus.edu.cn/jwglxt/xtgl/index_initMenu.html
```

而 Android 9（API 28）起**默认禁止明文流量**，故 OkHttp 直接抛出
`CLEARTEXT communication not permitted`。

## 决策

**只对校方域名放行明文**，其余保持禁止：

```xml
<base-config cleartextTrafficPermitted="false">
    <trust-anchors><certificates src="system" /></trust-anchors>
</base-config>

<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="true">gzus.edu.cn</domain>
</domain-config>
```

⚠️ **明确边界**：这不是"全局关闭 HTTPS 校验"，也不是降低证书信任标准。
范围严格限定在 `*.gzus.edu.cn`，用于兼容**校方服务端自己做的 http 降级**。
其他任何域名仍禁止明文。

## 影响

**变容易：**
- 正方跳转链可以走通（服务端 http 降级不再被系统拦截）

**风险与缓解：**
| 风险 | 说明 | 缓解 |
|---|---|---|
| 明文传输可被嗅探 | http 段的内容可被同网络者读取 | 仅限校方域名；且该段只传票据与 Cookie，不含密码（密码走 https 的 CAS 登录） |
| 范围蔓延 | 后人可能误把更多域名加进去 | 此 ADR 明确记录边界与理由 |

## 后续

- 待验证：真机重试是否建立会话成功
- 若校方后续修正为全程 https，应删除此放行配置
