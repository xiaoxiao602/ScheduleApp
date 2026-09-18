# ADR-001: 目标 Android 版本选择（minSdk / targetSdk / compileSdk）

## 状态

已接受

## 背景

学生自用课表 App，从广州软件学院教务系统拉取课表/考试/成绩。
用户实测手机为 **Android 15–17**。需要确定三个 SDK 版本号。

约束：
- 已装工具链：JDK 21.0.2 / Gradle 8.13 / AGP 8.7.3 / build-tools 35.0.0 / platforms 34+35
- **不上架应用商店**，纯自用 + 课设
- 教务系统接口形态尚未摸底（会影响是否用 WebView 等方案）

## 决策

| 项 | 取值 | 含义 |
|---|---|---|
| `minSdk` | **26** | Android 8.0 |
| `targetSdk` | **35** | Android 15 |
| `compileSdk` | **35** | 与 targetSdk 对齐 |

## 备选方案

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| minSdk 26 / target 35 | 工具链现成；覆盖用户机型；回避 16/17 的行为变更 | 用不到 Android 16/17 新特性 | ✅ **采纳** |
| minSdk 26 / target 36 | 跟进 Android 16 | AGP 8.7.3 编不了；需升 AGP + 装 platform-36；16 收紧了精确闹钟与大屏适配（课表 App 恰好在意的两点） | ❌ 现阶段收益为负 |
| minSdk 26 / target 37 | 跟进 Android 17 | Android 17 仍在预览期；**build-tools 35 + AGP 8.7.3 无法产出 targetSdk 37 的构建** | ❌ 技术上不可行 |
| minSdk 33+ | 少些兼容分支 | 用户机型本就 ≥15，无额外收益；反而砍掉潜在分享场景 | ❌ 无必要 |

**关键论证：手机能跑 Android 17 ≠ App 必须 target 37。**
Android 向下兼容，targetSdk 35 的 App 在 Android 17 上运行完全正常。
targetSdk 是"我声明我适配了哪一版的强制行为变更"，不是"我能装的最高版本"。

## 影响

**变容易：**
- 工具链零改动，立即可构建（已验证 `BUILD SUCCESSFUL`）
- 不需要处理 Android 16 的大屏自适应强制要求、精确闹钟拒绝策略
- API 26 起 `java.time` 可用（课程时间计算直接用 `LocalDate`/`LocalTime`，不需要 desugaring 的额外配置）

**变难 / 需注意：**
- **Android 15 强制 edge-to-edge**：目标机型全在 15+，**必须处理 `WindowInsets`**，否则课表顶部/底部会被系统栏遮挡。这是当前版本选择下最实际的适配点，不能跳过
- 放弃 Android 16 的预测式返回等新特性
- 若将来要上架商店，需重新评估 targetSdk（商店要求 target 最近一年内版本）

**后续演进路径（可逆）：**
1. 先以 target 35 打通 课表/成绩/考试 三项数据拉取
2. 如需要 Android 16/17 特性 → 升级 AGP 到 8.9+，安装 `platforms;android-36`，单独提交一次变更
3. 该决策可逆：改 `build.gradle` 数字即可，不涉及架构
