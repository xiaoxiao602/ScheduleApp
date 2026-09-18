# ADR-023：Dock 栏重设计 + 按钮回退 + 模糊背景

**状态**：已实施
**日期**：2026-09-18
**取代**：ADR-019（Dock 与 SplitButton 部分）

---

## 背景

用户提出四项要求：

1. **字和图标都看不见**（深色模式下）
2. **上面的按钮改回原来那个登录样式**
3. **Dock 栏改成参考图样式**：绿→蓝、**删图标只用文字**、文字外有**圆角矩形滑块**、
   滑块**可拖动**、点文字时滑块**滑动过来**
4. **增加高斯模糊**

参考：iOS 菜单设计 + 酷安 App 的 dock 栏。

---

## 决策

### 1. 撤回 MaterialSplitButton

**原因**：`javap` 实测该类**只有构造函数 + addView** ——
没有 `.text`、没有 `getLeadingButton()`。要用它就得手工造子按钮，
且深色模式下配色极易出错（这正是上一轮「灰字看不见」的成因）。

**结论**：**复杂度不划算**。改回 XML 中定义的单个 `MaterialButton`：

```xml
app:backgroundTint="@color/hero_button_bg"   <!-- 固定半透明白 -->
android:textColor="@color/hero_on_color"      <!-- 固定白 -->
app:cornerRadius="22dp"
```

### 2. Dock 栏：纯文字 + 可拖动滑块

| 维度 | 旧（ADR-019） | 新（ADR-023） |
|---|---|---|
| 内容 | 图标 + 文字 | **仅文字** |
| 指示器 | 药丸包住图标 | **圆角矩形滑块在文字下方** |
| 交互 | 仅点击 | **可拖动**，松手吸附最近项 |
| 切换 | 无 | **滑块滑动动画**（220ms 轻微过冲） |
| 配色 | 绿（参考图）→ 改**品牌蓝** | 浅蓝滑块 + 深蓝文字 |

**触摸模型**（参考 iOS 分段控件）：
```
按下 → 记录起点
移动 > 6dp → 进入拖动，滑块跟手，触发触觉反馈
抬起 → 拖动过则吸附最近项；否则视为点击直接选中
```

**关键实现点**：
- 滑块是 `FrameLayout` 中**先添加**的子视图 → 压在文字**下方**
- `slotBounds` 在 `onLayout` 中记录每个文字项边界，供命中测试与吸附使用
- `PathInterpolator(0.2f, 1.1f, 0.3f, 1f)` 产生轻微过冲，手感接近 iOS

### 3. 高斯模糊

⚠️ **踩坑记录**：
```
❌ android:backgroundBlurRadius        ← 不存在，AAPT 报 attribute not found
✅ android:windowBackgroundBlurRadius  ← 真实属性，但是 **Window 级**
```

**平台实证**（`platforms/android-35/data/res/values/attrs.xml`）：
```xml
<attr name="windowBackgroundBlurRadius" format="dimension" />
<!-- 对应 Window#setBackgroundBlurRadius -->
```

**因此必须在代码里调用，不能写在布局**：

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    window.setBackgroundBlurRadius(dp(24))   // API 31+
}
```

⚠️ 低版本（API 26–30）自动忽略 → Dock 退化为半透明背景，观感仍可接受。

### 4. 深色模式配色（对比度是硬要求）

| 元素 | 浅色主题 | 深色主题 |
|---|---|---|
| 滑块底 | `#D6E3FF`（浅蓝） | `#1E3A5F`（深蓝） |
| 选中文字 | `#08306B`（深蓝） | `#D6E3FF`（浅蓝） |
| 未选中文字 | `#5F6368` | `#9AA0A6` |

**规则**：滑块底与文字**明度必须拉开** —— 浅色主题=浅底深字，
深色主题=深底浅字。上一版两个都是浅蓝，所以「看不见」。

---

## 影响

**变容易**：
- Dock 视觉更简洁（纯文字），符合参考图与 iOS/酷安风格
- 拖动交互给了更直接的操作反馈
- 模糊让 Dock 有「浮在内容上」的层次

**变难**：
- 自绘 Dock 需自己处理触摸、命中测试、动画 —— 比 `BottomNavigationView` 复杂
- 模糊是 Window 级、API 31+ only，需版本分支
- 滑块位置依赖 `onLayout` 结果，首帧需 `post {}` 校正

---

## 未验证项

⚠️ **本机无设备、无模拟器**，以下**只能真机确认**：
- 拖动阻尼与吸附手感
- 滑块滑动动画的过冲量是否自然
- 模糊强度（24dp）是否合适
- 深色模式下的实际对比度观感

⚠️ 部分系统在**省电模式**下会禁用模糊 —— 属系统行为，非缺陷。
