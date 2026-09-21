import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:image_picker/image_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/local/app_database.dart' show MetaKeys;
import '../../data/zhengfang/schedule_parser.dart' show Course;
import '../../domain/core_logic.dart';
import '../providers.dart';
import '../theme.dart';

/// ============================================================
/// 弹窗集合（一比一对应原版 dialog_*.xml）
/// ============================================================
///
/// 包含：
///   showDayOverrideDialog    当日调课（dialog_day_override.xml）
///   showFirstWeekSetupDialog 第一周设置（dialog_first_week_setup.xml）
///   showProfileDialog        资料编辑（dialog_profile_edit.xml）
///   showMessageDialog        通用提示（dialog_message.xml）
///
/// ⚠️ 原版踩坑：弹窗必须用 Activity context，不能用 application context
///    （否则主题丢失 / 崩溃）。Flutter 侧 showDialog 传 context 即可。

// ---------------------------------------------------------- ① 当日调课

/// 当日调课（dialog_day_override.xml）。
///
/// 语义（说明书 §5.7）：
///   把某一天临时换成另一天的课表，或标记为"无课"。
///   **只影响那一天**；原课表不受影响。
Future<void> showDayOverrideDialog(
  BuildContext context,
  WidgetRef ref,
  DateTime date,
) async {
  final colors = AppColors.of(context);
  final iso = TimeFormats.iso(date);
  final current = ref.read(dayOverridesProvider).valueOrNull?[iso];

  final weekdayCn = const ['一', '二', '三', '四', '五', '六', '日'][date.weekday - 1];
  final desc = '把 ${date.month}月${date.day}日 周$weekdayCn 临时换成哪天的课表？\n'
      '原课表不受影响。';

  final picked = await showDialog<int>(
    context: context,
    builder: (ctx) => SimpleDialog(
      title: const Text('当日调课'),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 12),
          child: Text(
            desc,
            style: TextStyle(
              fontSize: 13,
              height: 1.35,
              color: colors.onSurfaceVariant,
            ),
          ),
        ),
        // 「无课」选项
        RadioListTile<int>(
          value: -1,
          groupValue: current,
          onChanged: (v) => Navigator.of(ctx).pop(v),
          title: const Text('这天没课'),
        ),
        const Divider(height: 1),
        // 换成其他星期几
        for (var d = 1; d <= 7; d++)
          RadioListTile<int>(
            value: d,
            groupValue: current,
            onChanged: (v) => Navigator.of(ctx).pop(v),
            title: Text(
              '换成 周${{1: '一', 2: '二', 3: '三', 4: '四', 5: '五', 6: '六', 7: '日'}[d]} 的课表',
            ),
          ),
        const Divider(height: 1),
        Center(
          child: TextButton(
            onPressed: () => Navigator.of(ctx).pop(0),
            child: const Text('恢复原样'),
          ),
        ),
      ],
    ),
  );

  if (picked == null) return;

  final repo = ref.read(dayOverrideRepoProvider);
  if (picked == 0) {
    await repo.remove(iso);
  } else {
    await repo.put(iso, picked);
  }
  ref.invalidate(dayOverridesProvider);
}

// ---------------------------------------------------------- ② 第一周设置

/// 第一周星期一设置（dialog_first_week_setup.xml，ADR-011）。
///
/// ⚠️ 为什么需要：教务只给"第几周"（相对），
///    没有基准就算不出"今天第几周"和每天日期。
Future<bool?> showFirstWeekSetupDialog(
  BuildContext context,
  WidgetRef ref, {
  bool allowSkip = true,
}) async {
  return showDialog<bool>(
    context: context,
    builder: (_) => _FirstWeekDialog(allowSkip: allowSkip),
  );
}

class _FirstWeekDialog extends ConsumerStatefulWidget {
  const _FirstWeekDialog({required this.allowSkip});

  final bool allowSkip;

  @override
  ConsumerState<_FirstWeekDialog> createState() => _FirstWeekDialogState();
}

class _FirstWeekDialogState extends ConsumerState<_FirstWeekDialog> {
  DateTime? _picked;

  @override
  void initState() {
    super.initState();
    _picked = ref.read(firstMondayProvider) ?? WeekCalculator.mondayOf(DateTime.now());
  }

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    final m = _picked;

    return AlertDialog(
      title: const Text('设置学期的第一周'),
      content: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              '课表数据只记录了「第几周」，需要你告诉 App 这一学期的第一周从哪天开始，'
              '才能正确显示当前第几周和每天的日期。',
              style: TextStyle(
                fontSize: 13,
                height: 1.35,
                color: colors.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 16),
            const Text('第一周星期一', style: TextStyle(fontSize: 13)),
            const SizedBox(height: 6),
            // 可点击行 → 日期选择
            InkWell(
              onTap: () async {
                final picked = await showDatePicker(
                  context: context,
                  initialDate: m ?? DateTime.now(),
                  firstDate: DateTime(2020),
                  lastDate: DateTime(2035),
                );
                if (picked == null) return;
                // ⚠️ 强制归到该周周一 —— 教务只给"第几周"，基准必须是周一
                setState(() => _picked = WeekCalculator.mondayOf(picked));
              },
              borderRadius: BorderRadius.circular(12),
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
                decoration: BoxDecoration(
                  color: colors.surfaceContainerHigh,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: colors.scheduleGridLine),
                ),
                child: Text(
                  m == null
                      ? '点击选择日期'
                      : '${TimeFormats.iso(m)}（星期一）',
                  style: TextStyle(fontSize: 14, color: colors.onSurface),
                ),
              ),
            ),
            const SizedBox(height: 10),
            Text(
              '💡 请选择星期一（学期第一天所在周的周一）。'
              '不确定的话，一般就是开学那天所在周的周一。',
              style: TextStyle(
                fontSize: 11,
                height: 1.35,
                color: colors.onSurface.withValues(alpha: 0.55),
              ),
            ),
          ],
        ),
      ),
      actions: [
        if (widget.allowSkip)
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('以后再说'),
          ),
        FilledButton(
          onPressed: m == null
              ? null
              : () async {
                  await ref.read(metaRepoProvider).put(
                        MetaKeys.firstMonday,
                        TimeFormats.iso(m),
                      );
                  ref.invalidate(metaAllProvider);
                  if (context.mounted) Navigator.of(context).pop(true);
                },
          child: const Text('设置好了'),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------- ③ 资料编辑

/// 资料编辑（dialog_profile_edit.xml）。
///
/// ⚠️ 只改**显示名**（app 内昵称）；学号/专业来自教务，不可改。
Future<void> showProfileDialog(BuildContext context, WidgetRef ref) async {
  await showDialog<void>(
    context: context,
    builder: (_) => const _ProfileDialog(),
  );
}

class _ProfileDialog extends ConsumerStatefulWidget {
  const _ProfileDialog();

  @override
  ConsumerState<_ProfileDialog> createState() => _ProfileDialogState();
}

/// 个人资料编辑弹窗 —— 一比一对应 dialog_profile_edit.xml（ADR-046）。
///
/// 原版布局：
///   ┌──────────────────────────────┐
///   │         [大头像 72dp]         │  dlgAvatar
///   │           张同学              │  dlgName 18sp bold
///   │  2026000001 · 智能科学与技术    │  dlgMeta 12sp
///   │                              │
///   │  [修改昵称] [选择头像]         │  dlgEditName / dlgPickAvatar
///   │  [重置头像]                   │  dlgResetAvatar
///   │                     [完成]    │  btnProfileClose
///   └──────────────────────────────┘
///
/// 原版设计意图（XML 注释）：
///   用户反馈「修改用户名/头像等功能没有图标，还是三行，浪费空间」——
///   所以这三项从首屏**收纳进本弹窗**，点头像卡片才弹出，首屏省约 150dp。
class _ProfileDialogState extends ConsumerState<_ProfileDialog> {
  late final TextEditingController _name;

  /// 递增信号：选/重置头像后 bump 一下强制刷新。
  int _avatarTick = 0;

  @override
  void initState() {
    super.initState();
    final info = ref.read(studentInfoProvider);
    _name = TextEditingController(text: info.displayName ?? '');
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  /// 头像文件（对应原版 UserProfileStore.avatarFile）。
  ///
  /// ⚠️ 2026-09 清理：`_avatarFile` / `_docsPath` 已无引用方，
  ///    头像读取统一走 today_page `_Avatar`，此处不再缓存目录。


  @override
  Widget build(BuildContext context) {
    final c = AppColors.of(context);
    final info = ref.watch(studentInfoProvider);

    // 名字优先用自定义显示名
    final shownName = _name.text.trim().isNotEmpty
        ? _name.text.trim()
        : (info.displayName ?? info.name ?? info.shownName);
    final meta = [
      if ((info.studentNo ?? '').isNotEmpty) info.studentNo!,
      if ((info.major ?? '').isNotEmpty) info.major!,
      if ((info.className ?? '').isNotEmpty) info.className!,
    ].join(' · ');

    return AlertDialog(
      title: const Text('我的资料'),
      content: SizedBox(
        width: 320,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // ---------- 大头像 72dp（原版 dlgAvatar）----------
              Center(
                child: _BigAvatar(name: shownName, tick: _avatarTick),
              ),
              const SizedBox(height: 12), // 原版 marginTop 12dp
              // ---------- 名字 18sp bold（原版 dlgName）----------
              Center(
                child: Text(
                  shownName.isEmpty ? '未设置' : shownName,
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                    color: c.onSurface,
                  ),
                ),
              ),
              // ---------- 学号 · 专业（原版 dlgMeta 12sp）----------
              if (meta.isNotEmpty) ...[
                const SizedBox(height: 4),
                Center(
                  child: Text(
                    meta,
                    style: TextStyle(fontSize: 12, color: c.onSurfaceVariant),
                  ),
                ),
              ],
              const SizedBox(height: 16),
              // ---------- 修改昵称（原版 dlgEditName）----------
              TextField(
                controller: _name,
                decoration: const InputDecoration(
                  labelText: '显示名（留空用真实姓名）',
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
                maxLength: 12,
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: 4),
              // ---------- 选择头像 / 重置头像 ----------
              Row(
                children: [
                  // 选择头像（原版 dlgPickAvatar）
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: _pickAvatar,
                      icon: const Icon(Icons.image_outlined, size: 18),
                      label: const Text('选择头像', style: TextStyle(fontSize: 13)),
                    ),
                  ),
                  const SizedBox(width: 8),
                  // 重置头像（原版 dlgResetAvatar）
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: _resetAvatar,
                      icon: const Icon(Icons.restart_alt_rounded, size: 18),
                      label: const Text('重置头像', style: TextStyle(fontSize: 13)),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
      actions: [
        // 原版 btnProfileClose「完成」
        FilledButton(
          onPressed: () async {
            final v = _name.text.trim();
            await ref
                .read(userProfileStoreProvider)
                .saveDisplayName(v.isEmpty ? null : v);
            final meta = ref.read(metaRepoProvider);
            if (v.isEmpty) {
              await meta.remove(MetaKeys.displayName);
            } else {
              await meta.put(MetaKeys.displayName, v);
            }
            ref.invalidate(metaAllProvider);
            if (context.mounted) Navigator.of(context).pop();
          },
          child: const Text('完成'),
        ),
      ],
    );
  }

  /// 选择头像 —— 相册选图 → **居中裁剪正方形 + 缩放到 256×256** → 存盘。
  ///
  /// ⚠️ 必须裁剪成正方形并按 256 限制尺寸（原版 UserProfileStore.saveAvatar）：
  ///    · 不裁剪 → 非正方形图会被拉伸变形（AvatarView 用 centerCrop 也只治标）
  ///    · 不缩放 → 原图可能 4000×3000，每次渲染都要解码大图 → 卡
  Future<void> _pickAvatar() async {
    try {
      final picker = ImagePicker();
      final x = await picker.pickImage(
        source: ImageSource.gallery,
        // ⚠️ 不要在这里同时设 maxWidth/maxHeight + imageQuality ——
        //    有些 ROM 的 picker 在缩放+压缩组合下会返回一个「已裁剪过」的
        //    临时文件，我们再解码裁剪就会二次处理，成功率下降。
        //    这里只限制尺寸，裁剪与压缩都自己做（可控、可验证）。
        maxWidth: 1024,
        maxHeight: 1024,
      );
      if (x == null) return;

      final src = await x.readAsBytes();
      if (src.isEmpty) {
        _toast('选到的文件为空，请换一张图试试');
        return;
      }

      // ---- 解码 ----
      final codec = await ui.instantiateImageCodec(src);
      final frame = await codec.getNextFrame();
      final img = frame.image;

      // ---- 居中裁剪成正方形（原版 Bitmap.createBitmap(x, y, side, side)）----
      final side = img.width < img.height ? img.width : img.height;
      final dx = ((img.width - side) / 2).round();
      final dy = ((img.height - side) / 2).round();

      final recorder = ui.PictureRecorder();
      final canvas = Canvas(recorder);
      canvas.drawImageRect(
        img,
        Rect.fromLTWH(
            dx.toDouble(), dy.toDouble(), side.toDouble(), side.toDouble()),
        const Rect.fromLTWH(0, 0, 256, 256), // ⚠️ 原版 OUT_SIZE = 256
        Paint()..filterQuality = FilterQuality.high,
      );
      final out = await recorder.endRecording().toImage(256, 256);
      final png = await out.toByteData(format: ui.ImageByteFormat.png);
      if (png == null) {
        _toast('图片编码失败，请换一张试试');
        return;
      }

      // ---- 写盘 ----
      final dir = await getApplicationDocumentsDirectory();
      final target = File('${dir.path}/custom_avatar');
      await target.writeAsBytes(png.buffer.asUint8List(), flush: true);

      // 清掉旧图缓存（否则 Image.file 可能命中缓存显示旧图）
      final fi = FileImage(target);
      await fi.evict();

      if (!mounted) return;
      // ⚠️ 用 setState 强制 _BigAvatar 重新 load
      setState(() => _avatarTick++);
      ref.invalidate(userProfileProvider);
      _toast('头像已更新');
    } catch (e, st) {
      // ⚠️ 把异常**显示给用户** —— release 包没有 logcat，
      //    用户看到的红字/提示就是唯一线索。
      _toast('选择头像失败：$e');
      debugPrint('pickAvatar failed: $e\n$st');
    }
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(msg, style: const TextStyle(fontSize: 13))));
  }

  /// 重置头像（原版 UserProfileStore.clearAvatar）—— 删文件，回到姓氏首字。
  Future<void> _resetAvatar() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final f = File('${dir.path}/custom_avatar');
      if (f.existsSync()) await f.delete();
      if (!mounted) return;
      setState(() => _avatarTick++);
      ref.invalidate(userProfileProvider);
    } catch (_) {}
  }
}

/// 资料弹窗里的大头像（72dp，对应原版 dlgAvatar）。
///
/// 有自定义图 → 显示图；否则 → 姓氏首字（白字 bold）。
class _BigAvatar extends StatefulWidget {
  const _BigAvatar({required this.name, required this.tick});

  final String name;
  final int tick;

  @override
  State<_BigAvatar> createState() => _BigAvatarState();
}

class _BigAvatarState extends State<_BigAvatar> {
  File? _file;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant _BigAvatar old) {
    super.didUpdateWidget(old);
    if (old.tick != widget.tick) _load();
  }

  Future<void> _load() async {
    final dir = await getApplicationDocumentsDirectory();
    final f = File('${dir.path}/custom_avatar');
    final exists = f.existsSync();
    if (!mounted) return;
    setState(() => _file = exists ? f : null);
  }

  @override
  Widget build(BuildContext context) {
    final c = AppColors.of(context);
    final f = _file;

    if (f != null) {
      // ⚠️ 用 ValueKey(tick) —— 强制换一个新的 Image widget，
      //    否则 Image.file 会命中 Flutter 的图片缓存，显示换之前的旧图。
      return ClipOval(
        child: Image.file(
          f,
          key: ValueKey<int>(widget.tick),
          width: 72,
          height: 72,
          fit: BoxFit.cover,
          gaplessPlayback: true,
          errorBuilder: (_, _, _) => _initial(c),
        ),
      );
    }
    return _initial(c);
  }

  Widget _initial(AppColors c) {
    return Container(
      width: 72, // 原版 dlgAvatar 72dp
      height: 72,
      decoration: BoxDecoration(color: c.primary, shape: BoxShape.circle),
      alignment: Alignment.center,
      child: Text(
        widget.name.isEmpty ? '?' : widget.name.characters.first,
        style: const TextStyle(
          fontSize: 28,
          fontWeight: FontWeight.bold,
          color: Colors.white,
        ),
      ),
    );
  }
}

// ---------------------------------------------------------- ④ 通用提示

/// 通用提示弹窗（dialog_message.xml）。
Future<void> showMessageDialog(
  BuildContext context, {
  required String title,
  required String message,
  String okText = '知道了',
  String? secondaryText,
  VoidCallback? onSecondary,
  // ⚠️ 可选的自定义内容（如"检查更新"里的更新说明长文本）。
  //    给了就用它替代纯文本 message，**外层的标题/按钮/圆角等规范保持一致**。
  Widget? contentOverride,
  // ⚠️ 主按钮回调。默认是"关闭弹窗"；给了就交给调用方
  //    （如"去下载"要先关窗再跳浏览器）。
  VoidCallback? onOk,
  // 主按钮是否禁用（如检查中）。
  bool okEnabled = true,
}) async {
  final colors = AppColors.of(context);
  await showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      // ⚠️ 统一规范：标题 18sp bold / 内容 13sp 行高 1.35 /
      //    按钮 = [次要 TextButton, 主要 FilledButton]，
      //    顺序与 showMessageDialog 原有调用方保持一致。
      title: Text(
        title,
        style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
      ),
      content: contentOverride ??
          Text(
            message,
            style: TextStyle(fontSize: 13, height: 1.35, color: colors.onSurface),
          ),
      actions: [
        if (secondaryText != null)
          TextButton(
            onPressed: () {
              Navigator.of(ctx).pop();
              onSecondary?.call();
            },
            child: Text(secondaryText),
          ),
        FilledButton(
          onPressed: okEnabled
              ? () {
                  Navigator.of(ctx).pop();
                  onOk?.call();
                }
              : null,
          child: Text(okText),
        ),
      ],
    ),
  );
}
// ---------------------------------------------------------- ⑤ 课程详情

/// 课程详情弹窗（dialog_course_detail.xml）。
///
/// 布局：课程名 + 副标题（周三 10:40-12:00 · 第 1-9 周）
///       然后逐行「标签 / 值」：时间 / 节次 / 地点 / 教师 / 周次 / 学分
Future<void> showCourseDetailDialog(
  BuildContext context,
  WidgetRef ref,
  Course course,
) async {
  final colors = AppColors.of(context);
  final colorOf = ref.read(courseColorProvider);
  final accent = colorOf(course.name);

  final weekdays = const ['一', '二', '三', '四', '五', '六', '日'];
  final dayCn = weekdays[(course.dayOfWeek - 1).clamp(0, 6)];
  final timeText =
      '${PeriodTime.startOf(course.startPeriod)}-${PeriodTime.endOf(course.endPeriod)}';
  // 周次文案：第 1-17 周 / 第 1-17 周(单) / 第 2-18 周(双)
  final parity = course.weekParity == 1
      ? '(单)'
      : (course.weekParity == 2 ? '(双)' : '' );
  final weekText = '第 ${course.startWeek}-${course.endWeek} 周$parity';
  final subtitle = '周$dayCn $timeText · $weekText';

  await showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Row(
        children: [
          Container(
            width: 6,
            height: 22,
            decoration: BoxDecoration(
              color: accent,
              borderRadius: BorderRadius.circular(3),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              course.name,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 17),
            ),
          ),
        ],
      ),
      content: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              subtitle,
              style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
            ),
            const SizedBox(height: 14),
            _DetailRow(label: '时间', value: timeText),
            _DetailRow(
              label: '节次',
              value: '${course.startPeriod}-${course.endPeriod} 节',
            ),
            if (course.location.isNotEmpty)
              _DetailRow(label: '地点', value: course.location),
            if (course.teacher.isNotEmpty)
              _DetailRow(label: '教师', value: course.teacher),
            _DetailRow(label: '周次', value: weekText),
            if (course.credit.isNotEmpty && course.credit != '0')
              _DetailRow(label: '学分', value: course.credit),
          ],
        ),
      ),
      actions: [
        FilledButton(
          onPressed: () => Navigator.of(ctx).pop(),
          child: const Text('知道了'),
        ),
      ],
    ),
  );
}

class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 44,
            child: Text(
              label,
              style: TextStyle(
                fontSize: 13,
                color: colors.onSurface.withValues(alpha: 0.6),
              ),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: TextStyle(fontSize: 13, color: colors.onSurface),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------- ⑥ 周次选择

/// 周次选择底部弹层（sheet_week_picker.xml）。
///
/// 原版是 BottomSheet + 周次列表，直接跳转所选周。
Future<void> showWeekPickerSheet(
  BuildContext context,
  WidgetRef ref, {
  required int currentWeek,
}) async {
  final colors = AppColors.of(context);
  final shown = ref.read(shownWeekProvider);

  await showModalBottomSheet<void>(
    context: context,
    backgroundColor: colors.surfaceContainerHigh,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
    ),
    builder: (ctx) => SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 18, 20, 8),
            child: Row(
              children: [
                Text(
                  '选择周次',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: colors.onSurface,
                  ),
                ),
                const Spacer(),
                // 回到本周
                TextButton(
                  onPressed: () {
                    ref.read(selectedWeekProvider.notifier).state = null;
                    Navigator.of(ctx).pop();
                  },
                  child: const Text('回到本周'),
                ),
              ],
            ),
          ),
          Divider(color: colors.scheduleGridLine, height: 1),
          SizedBox(
            height: 320,
            child: GridView.builder(
              padding: const EdgeInsets.all(16),
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 5,
                mainAxisSpacing: 10,
                crossAxisSpacing: 10,
              ),
              itemCount: WeekCalculator.maxWeeks,
              itemBuilder: (context, i) {
                final w = i + 1;
                final isShown = w == shown;
                final isCurrent = w == currentWeek;
                return InkWell(
                  onTap: () {
                    ref.read(selectedWeekProvider.notifier).state = w;
                    Navigator.of(ctx).pop();
                  },
                  borderRadius: BorderRadius.circular(12),
                  child: Container(
                    decoration: BoxDecoration(
                      color: isShown
                          ? colors.primary
                          : (isCurrent
                              ? colors.primaryContainer.withValues(alpha: 0.5)
                              : colors.surface),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(
                        color: isCurrent
                            ? colors.primary
                            : colors.scheduleGridLine,
                      ),
                    ),
                    alignment: Alignment.center,
                    child: Text(
                      '$w',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight:
                            isShown ? FontWeight.w700 : FontWeight.w500,
                        color: isShown
                            ? colors.onPrimary
                            : colors.onSurface,
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    ),
  );
}