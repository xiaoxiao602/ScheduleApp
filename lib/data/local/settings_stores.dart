import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// ============================================================
/// 设置项 Store（对应原版 5 个 SharedPreferences Store）
/// ============================================================
///
/// ⚠️ 为什么用 SharedPreferences 而不是数据库：
///    这些是**纯 UI 偏好**，不是业务数据；不需要查询/事务，
///    SharedPreferences 更轻。与原版选择一致。

/// Dock 外观参数（对应 DockTuningStore.kt，ADR-033/041/044/093）。
///
/// ⚠️ 取值范围有硬性上下限，避免拖出不可用的界面
///    （高度 0 → Dock 消失；圆角 > 高度/2 → 渲染异常）。
@immutable
class DockTuning {
  const DockTuning({
    this.dockHeight = DEF_HEIGHT,
    this.dockCorner = DEF_CORNER,
    this.dockElevation = DEF_ELEVATION,
    this.sliderHeight = DEF_SLIDER_H,
    this.sliderCorner = DEF_SLIDER_CORNER,
    this.sliderExtraWidth = DEF_SLIDER_W,
    this.textSizeTenths = DEF_TEXT,
    this.dockBottomOffset = DEF_BOTTOM_OFFSET,
    this.dockWidth = DEF_DOCK_WIDTH,
    this.animDurationMs = DEF_ANIM_MS,
    this.dampingOvershoot = DEF_DAMPING,
    this.glassOpacityPct = DEF_GLASS_OPACITY,
    this.blurSigma = DEF_BLUR_SIGMA,
    this.sliderGap = DEF_SLIDER_GAP,
    this.glassWhitePct = DEF_GLASS_WHITE,
    this.glassThickness = DEF_GLASS_THICKNESS,
    this.glassChromaTenths = DEF_GLASS_CHROMA,
  });

  /// Dock 厚度（dp）
  final int dockHeight;

  /// Dock 圆角（dp）
  final int dockCorner;

  /// 投影高度（dp）
  final int dockElevation;

  /// 滑块高度（dp）
  final int sliderHeight;

  /// 滑块圆角（dp）
  final int sliderCorner;

  /// 滑块比文字多出的宽度（dp，左右合计）
  final int sliderExtraWidth;

  /// 标签文字大小（sp × 10，存整数避免浮点精度问题）
  final int textSizeTenths;

  /// Dock 距屏幕底部（dp）
  final int dockBottomOffset;

  /// Dock 左右宽度（dp，即距屏幕左右边缘的距离）
  final int dockWidth;

  /// 滑动时长（ms）：越长越"沉"，越短越干脆
  final int animDurationMs;

  /// 阻尼强度（过冲量 ×100）：100 = 无回弹，135 = 轻微过冲，200 = 强烈弹跳
  final int dampingOvershoot;

  /// 玻璃不透明度（百分比 0-100）。
  ///
  /// ⚠️ 用户要求「透明度和模糊程度给调节选项」——
  ///    之前是写死的常量，无法调。
  final int glassOpacityPct;

  /// 模糊强度（sigma）。
  final int blurSigma;

  /// 滑块与槽位左右边缘的**间隙**（dp，左右各留一半）。
  ///
  /// ⚠️ 用户要求「滑块左右两边留一点间隙好看」——
  ///    滑块宽度 = 槽宽 - 间隙，位置仍以**槽位中心**对齐，
  ///    所以滑块与它下面的文字**始终保持同一中心**，不会错位。
  final int sliderGap;

  // ---------- 液态玻璃参数（2026-09 新增：用户要求把玻璃做成可调面板）----------

  /// 玻璃白度（%，0-40）—— 白色色调强度（「苹果奶白」感）。
  /// 0 = 通透无色；越大越乳白。
  final int glassWhitePct;

  /// 玻璃折射厚度（逻辑像素）—— **3D 折射位移的关键**。
  /// 库默认 20、README 推荐 30；越小折射越弱（0 = 几乎无折射）。
  final int glassThickness;

  /// 玻璃色散（×0.1，0-20）—— 边缘彩虹色散强度。
  /// 库默认 0.01 几乎不可见；6（=0.6）是温和值。
  final int glassChromaTenths;

  double get textSizeSp => textSizeTenths / 10;
  double get dampingFactor => dampingOvershoot / 100;

  /// 玻璃不透明度（0.0 ~ 1.0）。
  double get glassOpacity => glassOpacityPct / 100;

  /// 玻璃白度（0.0 ~ 1.0）。
  double get glassWhite => glassWhitePct / 100;

  /// 玻璃色散（0.0 ~ 2.0）。
  double get glassChroma => glassChromaTenths / 10;

  DockTuning copyWith({
    int? dockHeight,
    int? dockCorner,
    int? dockElevation,
    int? sliderHeight,
    int? sliderCorner,
    int? sliderExtraWidth,
    int? textSizeTenths,
    int? dockBottomOffset,
    int? dockWidth,
    int? animDurationMs,
    int? dampingOvershoot,
    int? glassOpacityPct,
    int? blurSigma,
    int? sliderGap,
    int? glassWhitePct,
    int? glassThickness,
    int? glassChromaTenths,
  }) {
    return DockTuning(
      dockHeight: _clamp(dockHeight ?? this.dockHeight, MIN_HEIGHT, MAX_HEIGHT),
      dockCorner: _clamp(dockCorner ?? this.dockCorner, MIN_CORNER, MAX_CORNER),
      dockElevation:
          _clamp(dockElevation ?? this.dockElevation, MIN_ELEVATION, MAX_ELEVATION),
      sliderHeight: _clamp(sliderHeight ?? this.sliderHeight, MIN_SLIDER_H, MAX_SLIDER_H),
      sliderCorner:
          _clamp(sliderCorner ?? this.sliderCorner, MIN_SLIDER_CORNER, MAX_SLIDER_CORNER),
      sliderExtraWidth:
          _clamp(sliderExtraWidth ?? this.sliderExtraWidth, MIN_SLIDER_W, MAX_SLIDER_W),
      textSizeTenths: _clamp(textSizeTenths ?? this.textSizeTenths, MIN_TEXT, MAX_TEXT),
      dockBottomOffset: _clamp(
          dockBottomOffset ?? this.dockBottomOffset, MIN_BOTTOM_OFFSET, MAX_BOTTOM_OFFSET),
      dockWidth: _clamp(dockWidth ?? this.dockWidth, MIN_DOCK_WIDTH, MAX_DOCK_WIDTH),
      animDurationMs: _clamp(animDurationMs ?? this.animDurationMs, MIN_ANIM_MS, MAX_ANIM_MS),
      dampingOvershoot: _clamp(
          dampingOvershoot ?? this.dampingOvershoot, MIN_DAMPING, MAX_DAMPING),
      // ⚠️⚠️ 这三行曾经漏掉 —— 导致「滑块间隙 / 不透明度 / 模糊强度」调不动：
      //    copyWith 接收了这三个参数，但**没传进构造调用**，
      //    于是返回的对象这三个字段永远是 this.xxx（旧值）。
      //    （上一轮我只补了 Store 的 load/save，漏了 copyWith，
      //      数据在内存里就没变过，根本到不了存储层。）
      glassOpacityPct: _clamp(
          glassOpacityPct ?? this.glassOpacityPct,
          MIN_GLASS_OPACITY,
          MAX_GLASS_OPACITY),
      blurSigma: _clamp(blurSigma ?? this.blurSigma, MIN_BLUR_SIGMA, MAX_BLUR_SIGMA),
      sliderGap: _clamp(sliderGap ?? this.sliderGap, MIN_SLIDER_GAP, MAX_SLIDER_GAP),
      // ⚠️⚠️ 新增玻璃参数（2026-09）—— 同样**必须传进构造调用**，
      //    否则 slider 拖了、状态也 setState 了，但值永远停在 this.xxx。
      glassWhitePct: _clamp(
          glassWhitePct ?? this.glassWhitePct, MIN_GLASS_WHITE, MAX_GLASS_WHITE),
      glassThickness: _clamp(
          glassThickness ?? this.glassThickness,
          MIN_GLASS_THICKNESS,
          MAX_GLASS_THICKNESS),
      glassChromaTenths: _clamp(
          glassChromaTenths ?? this.glassChromaTenths,
          MIN_GLASS_CHROMA,
          MAX_GLASS_CHROMA),
    );
  }

  static int _clamp(int v, int lo, int hi) => v < lo ? lo : (v > hi ? hi : v);

  // ---------- 默认值（2026-09-21：以用户截图定档的最终观感为准） ----------
  static const int DEF_HEIGHT = 63;
  static const int DEF_CORNER = 32;
  static const int DEF_ELEVATION = 8;
  static const int DEF_SLIDER_H = 50;
  static const int DEF_SLIDER_CORNER = 40;
  static const int DEF_SLIDER_W = 1;
  static const int DEF_TEXT = 173; // 17.3sp
  static const int DEF_BOTTOM_OFFSET = 30; // ADR-093：用户明确要 30
  /// Dock 距屏幕左右边缘（dp，越大 Dock 越窄）。
  ///
  /// ⚠️ 历史：原版 45 → 本项目一度改成 16（「贴紧一点」）→
  ///    2026-09-21 用户截图定档**回到 45**（悬浮感）。
  static const int DEF_DOCK_WIDTH = 45;
  /// 默认 38ms —— 用户 2026-09-21 截图定档（很快、脆）。
  ///
  /// ⚠️ 历史：200~800 → 50~250 → 10~100 → 用户拍板 38。
  static const int DEF_ANIM_MS = 38;
  static const int DEF_DAMPING = 130; // 1.30

  /// 玻璃不透明度（%）。
  /// ⚠️ 主界面/预览实际传 0（不压膜）；此字段仅自绘层兜底。
  static const int DEF_GLASS_OPACITY = 50;
  static const int MIN_GLASS_OPACITY = 20;
  static const int MAX_GLASS_OPACITY = 100;

  /// 模糊强度（sigma）—— 默认 4（用户 2026-09-21 截图定档）。
  ///
  /// ⚠️ 直接驱动 GlassCard 的 `blur`；上限 20（再高糊成一团，没有可看性）。
  static const int DEF_BLUR_SIGMA = 4;
  static const int MIN_BLUR_SIGMA = 0;
  static const int MAX_BLUR_SIGMA = 20;

  // ---------- 液态玻璃参数（2026-09-21 用户截图定档）默认值/上下限 ----------

  /// 玻璃白度（%）—— 15 = 「一点点白」。
  static const int DEF_GLASS_WHITE = 15;
  static const int MIN_GLASS_WHITE = 0;
  static const int MAX_GLASS_WHITE = 40;

  /// 折射厚度（逻辑像素）—— 35（用户定档；1.0 曾导致肉眼无折射）。
  static const int DEF_GLASS_THICKNESS = 35;
  static const int MIN_GLASS_THICKNESS = 0;
  static const int MAX_GLASS_THICKNESS = 60;

  /// 色散（×0.1）—— 2 = 0.2（用户 2026-09-22 定档）。
  static const int DEF_GLASS_CHROMA = 2;
  static const int MIN_GLASS_CHROMA = 0;
  static const int MAX_GLASS_CHROMA = 20;

  /// 滑块间隙（dp）—— 16（用户 2026-09-21 截图定档）
  static const int DEF_SLIDER_GAP = 16;
  static const int MIN_SLIDER_GAP = 0;
  static const int MAX_SLIDER_GAP = 24;

  // ---------- 上下限（硬约束） ----------
  static const int MIN_HEIGHT = 40, MAX_HEIGHT = 96;
  static const int MIN_CORNER = 0, MAX_CORNER = 32;
  static const int MIN_ELEVATION = 0, MAX_ELEVATION = 24;
  static const int MIN_SLIDER_H = 24, MAX_SLIDER_H = 60;
  static const int MIN_SLIDER_CORNER = 0, MAX_SLIDER_CORNER = 40;
  static const int MIN_SLIDER_W = 0, MAX_SLIDER_W = 40;
  static const int MIN_TEXT = 90, MAX_TEXT = 200;
  static const int MIN_BOTTOM_OFFSET = 0, MAX_BOTTOM_OFFSET = 120;
  /// Dock 距屏幕左右边缘（dp）。
  ///
  /// ⚠️ 用户反馈「Dock 宽度调节无效」——
  ///    根因是这个值**语义是"距屏幕边缘的距离"**（越大 Dock 越窄），
  ///    但范围开到 120 时，中间区域的变化在手机上很不明显。
  ///    收紧到 0~60，让每一格的变化都看得见。
  static const int MIN_DOCK_WIDTH = 0, MAX_DOCK_WIDTH = 60;
  /// 滑动时长范围（用户要求 10~100ms）。
  ///
  /// ⚠️ 历史：200~800 → 50~250 → **10~100**（用户逐步收紧，要更快更脆）。
  static const int MIN_ANIM_MS = 10, MAX_ANIM_MS = 100;
  static const int MIN_DAMPING = 100, MAX_DAMPING = 250;
}

/// Dock 参数持久化。
class DockTuningStore {
  static const _kHeight = 'dock_height';
  static const _kCorner = 'dock_corner';
  static const _kElevation = 'dock_elevation';
  static const _kSliderH = 'slider_height';
  static const _kSliderCorner = 'slider_corner';
  static const _kSliderW = 'slider_extra_width';
  static const _kText = 'text_size_tenths';
  static const _kBottom = 'dock_bottom_offset';
  static const _kWidth = 'dock_width';
  static const _kAnim = 'anim_duration_ms';
  static const _kDamping = 'damping_overshoot';

  // ⚠️⚠️ 新增字段必须同时补进 load / save / resetAll 三处！
  //    之前 glassOpacityPct / blurSigma / sliderGap 只加了字段和 UI，
  //    **忘了落库** —— 结果 load() 每次返回构造函数默认值，
  //    任何 rebuild 都把它们"弹回" 75/18/6，用户看到的就是「数值改不了」。
  static const _kGlassOpacity = 'glass_opacity_pct';
  static const _kBlurSigma = 'blur_sigma';
  static const _kSliderGap = 'slider_gap';

  // ⚠️ 液态玻璃参数（2026-09）——同样 load/save/resetAll 三处成对
  static const _kGlassWhite = 'glass_white_pct';
  static const _kGlassThickness = 'glass_thickness';
  static const _kGlassChroma = 'glass_chroma_tenths';

  Future<DockTuning> load() async {
    final sp = await SharedPreferences.getInstance();
    return DockTuning(
      dockHeight: sp.getInt(_kHeight) ?? DockTuning.DEF_HEIGHT,
      dockCorner: sp.getInt(_kCorner) ?? DockTuning.DEF_CORNER,
      dockElevation: sp.getInt(_kElevation) ?? DockTuning.DEF_ELEVATION,
      sliderHeight: sp.getInt(_kSliderH) ?? DockTuning.DEF_SLIDER_H,
      sliderCorner: sp.getInt(_kSliderCorner) ?? DockTuning.DEF_SLIDER_CORNER,
      sliderExtraWidth: sp.getInt(_kSliderW) ?? DockTuning.DEF_SLIDER_W,
      textSizeTenths: sp.getInt(_kText) ?? DockTuning.DEF_TEXT,
      dockBottomOffset: sp.getInt(_kBottom) ?? DockTuning.DEF_BOTTOM_OFFSET,
      dockWidth: sp.getInt(_kWidth) ?? DockTuning.DEF_DOCK_WIDTH,
      animDurationMs: sp.getInt(_kAnim) ?? DockTuning.DEF_ANIM_MS,
      dampingOvershoot: sp.getInt(_kDamping) ?? DockTuning.DEF_DAMPING,
      // ⚠️ 这三行曾经漏掉 → 导致「数值改不了（弹回默认）」
      glassOpacityPct:
          sp.getInt(_kGlassOpacity) ?? DockTuning.DEF_GLASS_OPACITY,
      blurSigma: sp.getInt(_kBlurSigma) ?? DockTuning.DEF_BLUR_SIGMA,
      sliderGap: sp.getInt(_kSliderGap) ?? DockTuning.DEF_SLIDER_GAP,
      // ⚠️ 液态玻璃参数（2026-09）——缺了这三行 = 改完一重启就弹回默认
      glassWhitePct: sp.getInt(_kGlassWhite) ?? DockTuning.DEF_GLASS_WHITE,
      glassThickness:
          sp.getInt(_kGlassThickness) ?? DockTuning.DEF_GLASS_THICKNESS,
      glassChromaTenths:
          sp.getInt(_kGlassChroma) ?? DockTuning.DEF_GLASS_CHROMA,
    ).copyWith(); // 走一遍 clamp，防止脏数据越界
  }

  Future<void> save(DockTuning t) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setInt(_kHeight, t.dockHeight);
    await sp.setInt(_kCorner, t.dockCorner);
    await sp.setInt(_kElevation, t.dockElevation);
    await sp.setInt(_kSliderH, t.sliderHeight);
    await sp.setInt(_kSliderCorner, t.sliderCorner);
    await sp.setInt(_kSliderW, t.sliderExtraWidth);
    await sp.setInt(_kText, t.textSizeTenths);
    await sp.setInt(_kBottom, t.dockBottomOffset);
    await sp.setInt(_kWidth, t.dockWidth);
    await sp.setInt(_kAnim, t.animDurationMs);
    await sp.setInt(_kDamping, t.dampingOvershoot);
    // ⚠️ 必须写这三项（与 load 成对，漏一个就"改不了"）
    await sp.setInt(_kGlassOpacity, t.glassOpacityPct);
    await sp.setInt(_kBlurSigma, t.blurSigma);
    await sp.setInt(_kSliderGap, t.sliderGap);
    // ⚠️ 液态玻璃参数（2026-09）——与 load 成对
    await sp.setInt(_kGlassWhite, t.glassWhitePct);
    await sp.setInt(_kGlassThickness, t.glassThickness);
    await sp.setInt(_kGlassChroma, t.glassChromaTenths);
  }

  /// 恢复默认。
  Future<void> resetAll() async {
    final sp = await SharedPreferences.getInstance();
    for (final k in [
      _kHeight, _kCorner, _kElevation, _kSliderH, _kSliderCorner,
      _kSliderW, _kText, _kBottom, _kWidth, _kAnim, _kDamping,
      _kGlassOpacity, _kBlurSigma, _kSliderGap,
      _kGlassWhite, _kGlassThickness, _kGlassChroma,
    ]) {
      await sp.remove(k);
    }
  }

  Future<bool> isCustomized() async {
    final sp = await SharedPreferences.getInstance();
    return sp.getKeys().any((k) => k.startsWith('dock_') || k.startsWith('slider_'));
  }
}

/// 课程卡片样式（对应 CardStyleStore.kt）。
enum CardStyleMode {
  /// 彩色填充：整块课程色 + 白字
  solid,

  /// 白底 + 左侧粗色条（默认）
  accentBar,

  /// 纯白底无装饰
  plain;

  static CardStyleMode fromName(String? name) {
    switch (name) {
      case 'SOLID':
        return CardStyleMode.solid;
      case 'PLAIN':
        return CardStyleMode.plain;
      case 'ACCENT_BAR':
      default:
        return CardStyleMode.accentBar;
    }
  }

  String get storageName => switch (this) {
        CardStyleMode.solid => 'SOLID',
        CardStyleMode.accentBar => 'ACCENT_BAR',
        CardStyleMode.plain => 'PLAIN',
      };

  String get label => switch (this) {
        CardStyleMode.solid => '彩色填充',
        CardStyleMode.accentBar => '白底 + 色条',
        CardStyleMode.plain => '纯白底',
      };
}

/// 卡片样式 + 每门课自定义颜色（对应 CardStyleStore.kt）。
class CardStyleStore {
  static const _kMode = 'card_style_mode';
  static const _kColorPrefix = 'course_color__';

  Future<CardStyleMode> loadMode() async {
    final sp = await SharedPreferences.getInstance();
    return CardStyleMode.fromName(sp.getString(_kMode));
  }

  Future<void> saveMode(CardStyleMode mode) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setString(_kMode, mode.storageName);
  }

  /// 读某门课的自定义颜色（ARGB int）；无自定义返回 null。
  Future<int?> customColorOf(String courseName) async {
    final sp = await SharedPreferences.getInstance();
    final v = sp.getInt('$_kColorPrefix$courseName');
    return (v == null || v == 0) ? null : v;
  }

  Future<void> setCustomColor(String courseName, int? argb) async {
    final sp = await SharedPreferences.getInstance();
    if (argb == null) {
      await sp.remove('$_kColorPrefix$courseName');
    } else {
      await sp.setInt('$_kColorPrefix$courseName', argb);
    }
  }

  /// 一次性读全部自定义色（渲染课表时用）。
  Future<Map<String, int>> loadAllCustomColors() async {
    final sp = await SharedPreferences.getInstance();
    final out = <String, int>{};
    for (final k in sp.getKeys()) {
      if (k.startsWith(_kColorPrefix)) {
        final v = sp.getInt(k);
        if (v != null && v != 0) {
          out[k.substring(_kColorPrefix.length)] = v;
        }
      }
    }
    return out;
  }

  Future<void> clearCustomColors() async {
    final sp = await SharedPreferences.getInstance();
    for (final k in sp.getKeys().where((k) => k.startsWith(_kColorPrefix)).toList()) {
      await sp.remove(k);
    }
  }
}

/// 触感风格（对应 HapticStore.kt 的 6 种，映射 Android HapticFeedbackConstants）。
enum HapticStyle {
  /// 短促清脆，像秒针
  clockTick('滴答', 'CLOCK_TICK'),

  /// 标准按键反馈，机型差异最小
  virtualKey('按键', 'VIRTUAL_KEY'),

  /// 比按键更轻，接近打字
  keyboardTap('轻敲', 'KEYBOARD_TAP'),

  /// 细腻滑动感，类似 iOS（API 27+）
  textHandleMove('划动', 'TEXT_HANDLE_MOVE'),

  /// 稍重，适合确认类
  contextClick('上下文点击', 'CONTEXT_CLICK'),

  /// 最重
  longPress('长按', 'LONG_PRESS');

  const HapticStyle(this.label, this.androidConstant);

  final String label;
  final String androidConstant;

  static HapticStyle fromName(String? name) {
    for (final s in HapticStyle.values) {
      if (s.name == name || s.androidConstant == name) return s;
    }
    return HapticStyle.clockTick;
  }
}

/// 触感设置（对应 HapticStore.kt）。
@immutable
class HapticConfig {
  const HapticConfig({
    this.enabled = true,
    this.style = HapticStyle.clockTick,
    this.strength = 100,
    this.onDock = true,
    this.onTab = true,
    this.onButton = true,
  });

  final bool enabled;
  final HapticStyle style;

  /// 30–100 百分比；100 = 系统默认（不缩放）。
  /// ⚠️ 低于 100 时用振幅缩放，**仅 Android 12+ 生效**。
  final int strength;
  final bool onDock;
  final bool onTab;
  final bool onButton;

  HapticConfig copyWith({
    bool? enabled,
    HapticStyle? style,
    int? strength,
    bool? onDock,
    bool? onTab,
    bool? onButton,
  }) {
    final s = strength ?? this.strength;
    return HapticConfig(
      enabled: enabled ?? this.enabled,
      style: style ?? this.style,
      strength: s < 30 ? 30 : (s > 100 ? 100 : s),
      onDock: onDock ?? this.onDock,
      onTab: onTab ?? this.onTab,
      onButton: onButton ?? this.onButton,
    );
  }

  static const int MIN_STRENGTH = 30;
  static const int MAX_STRENGTH = 100;
}

class HapticStore {
  static const _kEnabled = 'enabled';
  static const _kStyle = 'style';
  static const _kStrength = 'strength';
  static const _kOnDock = 'on_dock';
  static const _kOnTab = 'on_tab';
  static const _kOnButton = 'on_button';

  Future<HapticConfig> load() async {
    final sp = await SharedPreferences.getInstance();
    return HapticConfig(
      enabled: sp.getBool(_kEnabled) ?? true,
      style: HapticStyle.fromName(sp.getString(_kStyle)),
      strength: sp.getInt(_kStrength) ?? 100,
      onDock: sp.getBool(_kOnDock) ?? true,
      onTab: sp.getBool(_kOnTab) ?? true,
      onButton: sp.getBool(_kOnButton) ?? true,
    ).copyWith();
  }

  Future<void> save(HapticConfig c) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setBool(_kEnabled, c.enabled);
    await sp.setString(_kStyle, c.style.name);
    await sp.setInt(_kStrength, c.strength);
    await sp.setBool(_kOnDock, c.onDock);
    await sp.setBool(_kOnTab, c.onTab);
    await sp.setBool(_kOnButton, c.onButton);
  }
}

/// 用户资料（对应 UserProfileStore.kt）。
///
/// ⚠️ 头像固定文件名 `custom_avatar`，存 app 私有目录。
///    读取必须用文件字节手动解码，不能用同名缓存（原版踩过 setImageURI 的坑）。
class UserProfileStore {
  static const _kDisplayName = 'display_name';

  /// 自定义显示名；非空时优先于真名。
  Future<String?> loadDisplayName() async {
    final sp = await SharedPreferences.getInstance();
    final v = sp.getString(_kDisplayName);
    return (v == null || v.isEmpty) ? null : v;
  }

  Future<void> saveDisplayName(String? name) async {
    final sp = await SharedPreferences.getInstance();
    if (name == null || name.isEmpty) {
      await sp.remove(_kDisplayName);
    } else {
      await sp.setString(_kDisplayName, name);
    }
  }

  /// 清除自定义名 → 恢复真名。
  Future<void> clearDisplayName() async {
    final sp = await SharedPreferences.getInstance();
    await sp.remove(_kDisplayName);
  }
}

/// 更新设置（对应 UpdatePrefs.kt）。
class UpdatePrefs {
  static const _kAutoCheck = 'auto_check';

  /// 默认 true。
  Future<bool> loadAutoCheck() async {
    final sp = await SharedPreferences.getInstance();
    return sp.getBool(_kAutoCheck) ?? true;
  }

  Future<void> saveAutoCheck(bool v) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setBool(_kAutoCheck, v);
  }
}

// ============================================================
// 推送/提醒设置（1.2.1 方案 E：自研常驻倒计时通知）
// ============================================================

/// 推送开关配置。
///
/// ⚠️ 持久化 8 处同步纪律（add-persisted-settings-field 技能）：
///    字段声明 + 构造参数 + copyWith 参数列表 + copyWith 构造调用
///    + store key + load + save + resetAll —— 漏任何一处都是静默失败。
@immutable
class NotifyPrefs {
  const NotifyPrefs({
    this.enabled = true,
    this.remindOn = true,
    this.countdownOn = true,
    this.afterOn = true,
    this.leadMinutes = 30,
  });

  /// 总开关。
  final bool enabled;

  /// 上课提醒（T-lead 铃声）。
  final bool remindOn;

  /// 常驻倒计时（课前/上课中软常驻通知）。
  final bool countdownOn;

  /// 已下课提示。
  final bool afterOn;

  /// 提前量（分钟，5..120；默认 30，用户拍板）。
  final int leadMinutes;

  NotifyPrefs copyWith({
    bool? enabled,
    bool? remindOn,
    bool? countdownOn,
    bool? afterOn,
    int? leadMinutes,
  }) {
    final lm = leadMinutes ?? this.leadMinutes;
    return NotifyPrefs(
      enabled: enabled ?? this.enabled,
      remindOn: remindOn ?? this.remindOn,
      countdownOn: countdownOn ?? this.countdownOn,
      afterOn: afterOn ?? this.afterOn,
      leadMinutes: lm < 5 ? 5 : (lm > 120 ? 120 : lm),
    );
  }
}

/// 推送设置 Store + 运行时状态（阶段跟踪 / 「结束显示」抑制）。
class NotifyStore {
  static const _kEnabled = 'notify_enabled';
  static const _kRemindOn = 'notify_remind_on';
  static const _kCountdownOn = 'notify_countdown_on';
  static const _kAfterOn = 'notify_after_on';
  static const _kLeadMinutes = 'notify_lead_minutes';
  static const _kStage = 'notify_stage_state';
  static const _kSuppressPrefix = 'notify_suppress_';

  Future<NotifyPrefs> load() async {
    final sp = await SharedPreferences.getInstance();
    return NotifyPrefs(
      enabled: sp.getBool(_kEnabled) ?? true,
      remindOn: sp.getBool(_kRemindOn) ?? true,
      countdownOn: sp.getBool(_kCountdownOn) ?? true,
      afterOn: sp.getBool(_kAfterOn) ?? true,
      leadMinutes: sp.getInt(_kLeadMinutes) ?? 30,
    ).copyWith();
  }

  Future<void> save(NotifyPrefs c) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setBool(_kEnabled, c.enabled);
    await sp.setBool(_kRemindOn, c.remindOn);
    await sp.setBool(_kCountdownOn, c.countdownOn);
    await sp.setBool(_kAfterOn, c.afterOn);
    await sp.setInt(_kLeadMinutes, c.leadMinutes);
  }

  Future<void> resetAll() async {
    final sp = await SharedPreferences.getInstance();
    await sp.remove(_kEnabled);
    await sp.remove(_kRemindOn);
    await sp.remove(_kCountdownOn);
    await sp.remove(_kAfterOn);
    await sp.remove(_kLeadMinutes);
  }

  // ---------- 运行时状态（非用户设置）----------

  /// 当前阶段标记（"stage|momentKey"），用于判断「首次进入」（响铃）vs 更新（静默）。
  Future<String?> loadStage() async {
    final sp = await SharedPreferences.getInstance();
    return sp.getString(_kStage);
  }

  Future<void> saveStage(String v) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setString(_kStage, v);
  }

  /// 「结束显示」抑制：本节课不再重推/显示。
  Future<bool> isSuppressed(String momentKey) async {
    final sp = await SharedPreferences.getInstance();
    return sp.getBool('$_kSuppressPrefix$momentKey') ?? false;
  }

  Future<void> suppress(String momentKey) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setBool('$_kSuppressPrefix$momentKey', true);
  }
}
