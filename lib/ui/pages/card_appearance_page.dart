import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/local/settings_stores.dart' show CardStyleMode;
import '../../data/zhengfang/schedule_parser.dart' show Course;
import '../../domain/colors.dart';
import '../providers.dart';
import '../theme.dart';

/// ============================================================
/// 课程外观二级页（一比一对应 fragment_card_appearance.xml，ADR-069）
/// ============================================================
///
/// 合并两个相关设置到一页：
///   ① 卡片样式 —— 三种方案，带真实卡片预览
///   ② 课程配色 —— 逐门课自定义颜色
///
/// 为什么合并（原版 ADR-069）：两项都在回答"课表长什么样"，
/// 放一起用户能连续调整 + 立即对照，不用在两个入口之间来回跳。
class CardAppearancePage extends ConsumerWidget {
  const CardAppearancePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final mode =
        ref.watch(cardStyleModeProvider).valueOrNull ?? CardStyleMode.accentBar;
    final courses = ref.watch(coursesProvider).valueOrNull ?? const <Course>[];

    // 去重课程名（同一门课可能有多个时段）
    final names = <String>[];
    for (final c in courses) {
      if (!names.contains(c.name)) names.add(c.name);
    }
    names.sort();

    final custom = ref.watch(customColorsProvider).valueOrNull ?? const {};
    final hasCustom = custom.values.any((v) => v != 0);

    return Scaffold(
      backgroundColor: colors.surface,
      appBar: AppBar(title: const Text('课程外观')),
      body: ListView(
        padding: const EdgeInsets.only(bottom: 40),
        children: [
          const _SectionLabel('卡片样式'),
          for (final m in CardStyleMode.values)
            _CardStyleRow(
              mode: m,
              selected: m == mode,
              onTap: () async {
                await ref.read(cardStyleStoreProvider).saveMode(m);
                ref.invalidate(cardStyleModeProvider);
              },
            ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 16),
            child: Divider(color: colors.scheduleGridLine, height: 1),
          ),
          const _SectionLabel('课程配色'),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 10),
            child: Text(
              '默认按课程自动分配。点任意一门课可单独指定它的颜色。',
              style: TextStyle(
                fontSize: 13,
                height: 1.3,
                color: colors.onSurfaceVariant,
              ),
            ),
          ),
          if (names.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
              child: Text(
                '暂无课程（先同步一次）',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 13, color: colors.onSurfaceVariant),
              ),
            )
          else
            for (final name in names)
              _CourseColorRow(
                name: name,
                onTap: () => _pickColor(context, ref, name),
              ),
          if (hasCustom)
            Padding(
              padding: const EdgeInsets.only(top: 20),
              child: Center(
                child: FilledButton.tonal(
                  onPressed: () async {
                    await ref.read(cardStyleStoreProvider).clearCustomColors();
                    ref.invalidate(customColorsProvider);
                  },
                  child: const Text('恢复默认配色'),
                ),
              ),
            ),
        ],
      ),
    );
  }

  /// 弹颜色选择器（对应 dialog_color_picker.xml，ADR-065）。
  ///
  /// ⚠️ 与原版一致的两段式流程：
  ///   ① 预设色板（24 色）→ 点色即生效；
  ///   ② 「自定义颜色…」→ 再弹 HSV 取色（色相/饱和度/明度三滑块）。
  ///   之前 Flutter 版只有 10 个预设色、**没有自定义入口** ——
  ///   用户对照 1.0.3 反馈「没有色盘可选」，本次补齐。
  Future<void> _pickColor(
      BuildContext context, WidgetRef ref, String courseName) async {
    final current = ref.read(courseColorProvider)(courseName);

    final picked = await showDialog<int>(
      context: context,
      builder: (_) =>
          _ColorPickerDialog(courseName: courseName, current: current),
    );
    if (picked == null) return; // 取消

    if (picked == _ColorPickerDialog.openCustom) {
      // ⚠️ await 之后再用 context → 先确认还挂在树上（lint: use_build_context_synchronously）
      if (!context.mounted) return;
      // 「自定义颜色…」→ 进 HSV 取色
      final custom = await showDialog<int>(
        context: context,
        builder: (_) =>
            _HsvPickerDialog(courseName: courseName, initial: current),
      );
      if (custom == null) return;
      await ref
          .read(cardStyleStoreProvider)
          .setCustomColor(courseName, custom);
      ref.invalidate(customColorsProvider);
      return;
    }

    // 0 = 恢复自动配色
    await ref
        .read(cardStyleStoreProvider)
        .setCustomColor(courseName, picked == 0 ? null : picked);
    ref.invalidate(customColorsProvider);
  }
}

class _SectionLabel extends StatelessWidget {
  const _SectionLabel(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 6),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.bold,
          color: colors.onSurface.withValues(alpha: 0.6),
        ),
      ),
    );
  }
}

/// 卡片样式选项行（对应 item_card_style.xml）。
///
/// 用真实卡片预览而非纯文字 —— 三种样式的区别是视觉效果。
///
/// ⚠️ ADR-071「点不了」教训：Android 上预览卡是 MaterialCardView，
/// 默认 clickable=true 会吃掉触摸事件。Flutter 侧等价修法 =
/// 用 IgnorePointer 包住预览，保证整行可点。
class _CardStyleRow extends StatelessWidget {
  const _CardStyleRow({
    required this.mode,
    required this.selected,
    required this.onTap,
  });

  final CardStyleMode mode;
  final bool selected;
  final VoidCallback onTap;

  static const _names = {
    CardStyleMode.accentBar: '白底 + 色条',
    CardStyleMode.solid: '整块课程色',
    CardStyleMode.plain: '纯白卡片',
  };
  static const _descs = {
    CardStyleMode.accentBar: '白底 + 左侧粗色条，干净且有辨识度',
    CardStyleMode.solid: '卡片整体填充课程色，最醒目',
    CardStyleMode.plain: '不加任何装饰，最简洁',
  };

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    const demo = Color(0xFF1E88E5);
    final solid = mode == CardStyleMode.solid;

    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
        child: Row(
          children: [
            IgnorePointer(
              child: Container(
                width: 72,
                height: 46,
                clipBehavior: Clip.antiAlias,
                decoration: BoxDecoration(
                  color: solid ? demo : Colors.white,
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: const Color(0x22000000)),
                ),
                child: Row(
                  children: [
                    if (mode == CardStyleMode.accentBar)
                      Container(width: 8, color: demo),
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 6),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Container(
                              height: 5,
                              decoration: BoxDecoration(
                                color: solid ? Colors.white : const Color(0xFF3A3F47),
                                borderRadius: BorderRadius.circular(2),
                              ),
                            ),
                            const SizedBox(height: 5),
                            Container(
                              width: 28,
                              height: 4,
                              decoration: BoxDecoration(
                                color: solid ? Colors.white70 : const Color(0xFFB0B6BE),
                                borderRadius: BorderRadius.circular(2),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    _names[mode]!,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.bold,
                      color: colors.onSurface,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    _descs[mode]!,
                    style: TextStyle(
                      fontSize: 12,
                      color: colors.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            if (selected)
              Icon(Icons.check_rounded, size: 22, color: colors.primary),
          ],
        ),
      ),
    );
  }
}

/// 课程配色行（对应 item_course_color.xml）。
class _CourseColorRow extends ConsumerWidget {
  const _CourseColorRow({required this.name, required this.onTap});

  final String name;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final dot = ref.watch(courseColorProvider)(name);

    return InkWell(
      onTap: onTap,
      child: Container(
        constraints: const BoxConstraints(minHeight: 60),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
        child: Row(
          children: [
            Expanded(
              child: Text(
                name,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 15, color: colors.onSurface),
              ),
            ),
            Container(
              width: 26,
              height: 26,
              decoration: BoxDecoration(
                color: dot,
                shape: BoxShape.circle,
                border: Border.all(color: colors.scheduleGridLine),
              ),
            ),
            const SizedBox(width: 10),
            Icon(Icons.chevron_right_rounded,
                size: 18, color: colors.onSurfaceVariant),
          ],
        ),
      ),
    );
  }
}

/// 颜色选择弹窗（对应 dialog_color_picker.xml，ADR-065）。
///
/// 结构（与原版一致）：
///   ① 24 色预设网格（4 列），当前色带勾选标记，点色即生效（pop 色值）；
///   ② 「自定义颜色…」→ pop [openCustom]，由调用方接力弹 HSV 取色；
///   ③ 「恢复该课自动配色」→ pop 0；
///   ④ 取消 → pop null。
class _ColorPickerDialog extends StatelessWidget {
  const _ColorPickerDialog({required this.courseName, required this.current});

  final String courseName;

  /// 该课当前生效颜色（用于勾选标记）。
  final Color current;

  /// 哨兵值：「自定义颜色…」被点击。颜色都是 0xFF......
  /// 的正数，负数不可能与色值冲突。
  static const int openCustom = -1;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    final swatches = CoursePalette.presets; // 24 色（原版 PRESETS）
    final currentArgb = current.toARGB32();

    return AlertDialog(
      title: Text(courseName, maxLines: 2, overflow: TextOverflow.ellipsis),
      content: SizedBox(
        width: 300,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              GridView.builder(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: 4,
                  mainAxisSpacing: 10,
                  crossAxisSpacing: 10,
                  childAspectRatio: 1,
                ),
                itemCount: swatches.length,
                itemBuilder: (context, i) {
                  final selected = swatches[i] == currentArgb;
                  return InkWell(
                    onTap: () => Navigator.of(context).pop(swatches[i]),
                    borderRadius: BorderRadius.circular(10),
                    child: Container(
                      decoration: BoxDecoration(
                        color: Color(swatches[i]),
                        borderRadius: BorderRadius.circular(10),
                        // ⚠️ 当前色标记（原版 bg_color_swatch_selected）：
                        //    白描边 + 白勾，浅色/深色底上都可辨。
                        border: selected
                            ? Border.all(color: Colors.white, width: 3)
                            : null,
                      ),
                      child: selected
                          ? const Center(
                              child: Icon(Icons.check_rounded,
                                  color: Colors.white, size: 20),
                            )
                          : null,
                    ),
                  );
                },
              ),
              const SizedBox(height: 14),
              Divider(color: colors.scheduleGridLine, height: 1),
              const SizedBox(height: 6),
              // ---- 自定义颜色（→ HSV 三滑块取色）----
              TextButton(
                onPressed: () => Navigator.of(context).pop(openCustom),
                child: const Text('自定义颜色…'),
              ),
              TextButton(
                onPressed: () => Navigator.of(context).pop(0),
                child: const Text('恢复该课自动配色'),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
      ],
    );
  }
}

/// HSV 取色弹窗（对应原版 showHsvPicker，ADR-065）。
///
/// ⚠️ 原版是「三个滑块，无第三方依赖」：色相 0~360、饱和度 0~1、明度 0~1，
///    顶部一条实时预览色块。这里逐项复刻，不引入第三方取色库。
class _HsvPickerDialog extends StatefulWidget {
  const _HsvPickerDialog({required this.courseName, required this.initial});

  final String courseName;
  final Color initial;

  @override
  State<_HsvPickerDialog> createState() => _HsvPickerDialogState();
}

class _HsvPickerDialogState extends State<_HsvPickerDialog> {
  late HSVColor _hsv;

  @override
  void initState() {
    super.initState();
    _hsv = HSVColor.fromColor(widget.initial);
  }

  Widget _slider(
    String label,
    double value,
    double max,
    ValueChanged<double> onChanged,
  ) {
    final c = AppColors.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(label,
              style: TextStyle(fontSize: 12, color: c.onSurfaceVariant)),
        ),
        Slider(value: value, min: 0, max: max, onChanged: onChanged),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);

    return AlertDialog(
      title: const Text('自定义颜色'),
      content: SizedBox(
        width: 300,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // ---- 实时预览条（原版 56dp）----
              Container(
                height: 56,
                decoration: BoxDecoration(
                  color: _hsv.toColor(),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: colors.scheduleGridLine),
                ),
              ),
              const SizedBox(height: 4),
              _slider('色相', _hsv.hue, 360,
                  (v) => setState(() => _hsv = _hsv.withHue(v))),
              _slider('饱和度', _hsv.saturation, 1,
                  (v) => setState(() => _hsv = _hsv.withSaturation(v))),
              _slider('明度', _hsv.value, 1,
                  (v) => setState(() => _hsv = _hsv.withValue(v))),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: () =>
              Navigator.of(context).pop(_hsv.toColor().toARGB32()),
          child: const Text('应用'),
        ),
      ],
    );
  }
}