import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/local/settings_stores.dart' show NotifyPrefs;
import '../../data/notify/notify_service.dart';
import '../providers.dart';
import '../theme.dart';
import 'notify_test_page.dart';

/// ============================================================
/// 上课提醒设置页（1.2.1 方案 E）—— 讨论文档 §5.5/§5.6
/// ============================================================
///
/// 内容：总开关 + 三类通知开关 + 提前量 + 权限请求 + 推送测试入口。
/// ⚠️ 只做加法：不改任何现有页面布局。
class NotifyPage extends ConsumerWidget {
  const NotifyPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final async = ref.watch(notifyPrefsProvider);

    return Scaffold(
      backgroundColor: colors.surface,
      appBar: AppBar(title: const Text('上课提醒')),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('读取失败：$e')),
        data: (cfg) => _Body(cfg: cfg),
      ),
    );
  }
}

class _Body extends ConsumerWidget {
  const _Body({required this.cfg});

  final NotifyPrefs cfg;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);

    Future<void> save(NotifyPrefs c) async {
      await ref.read(notifyPrefsProvider.notifier).apply(c);
      // 设置变更立即生效（总开关关闭时 reschedule 内部会清通知）。
      await NotifyService.instance.reschedule();
    }

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 40),
      children: [
        // ============ 总开关 ============
        SwitchListTile(
          value: cfg.enabled,
          onChanged: (v) => save(cfg.copyWith(enabled: v)),
          title: const Text('启用上课提醒', style: TextStyle(fontSize: 15)),
          subtitle: const Text('关闭后不发送任何通知', style: TextStyle(fontSize: 12)),
          contentPadding: EdgeInsets.zero,
        ),

        // ============ 三类通知开关 ============
        _Label('通知类型'),
        SwitchListTile(
          value: cfg.remindOn,
          onChanged: (v) => save(cfg.copyWith(remindOn: v)),
          title: const Text('课前铃声提醒', style: TextStyle(fontSize: 15)),
          subtitle: const Text('到提醒点响铃一次', style: TextStyle(fontSize: 12)),
          contentPadding: EdgeInsets.zero,
        ),
        SwitchListTile(
          value: cfg.countdownOn,
          onChanged: (v) => save(cfg.copyWith(countdownOn: v)),
          title: const Text('常驻倒计时', style: TextStyle(fontSize: 15)),
          subtitle: const Text('课前/上课中：秒表倒计时 + 进度条（可划掉，划掉自动回来）',
              style: TextStyle(fontSize: 12)),
          contentPadding: EdgeInsets.zero,
        ),
        SwitchListTile(
          value: cfg.afterOn,
          onChanged: (v) => save(cfg.copyWith(afterOn: v)),
          title: const Text('已下课提示', style: TextStyle(fontSize: 15)),
          subtitle: const Text('下课后显示「已下课」', style: TextStyle(fontSize: 12)),
          contentPadding: EdgeInsets.zero,
        ),

        // ============ 提前量 ============
        _Label('提前量'),
        InkWell(
          onTap: () => _pickLead(context, ref, cfg),
          borderRadius: BorderRadius.circular(AppTheme.settingsCardRadius),
          child: Container(
            constraints: const BoxConstraints(minHeight: 52),
            padding: const EdgeInsets.symmetric(horizontal: 16),
            decoration: BoxDecoration(
              color: colors.surfaceContainerHigh,
              borderRadius: BorderRadius.circular(AppTheme.settingsCardRadius),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    '提前 ${cfg.leadMinutes} 分钟提醒',
                    style: TextStyle(fontSize: 15, color: colors.onSurface),
                  ),
                ),
                Icon(Icons.keyboard_arrow_down_rounded,
                    size: 20,
                    color: colors.onSurface.withValues(alpha: 0.45)),
              ],
            ),
          ),
        ),

        // ============ 权限 ============
        _Label('权限'),
        Text(
          '需要通知权限与精确闹钟权限才能准时提醒。\n小米手机还需在系统设置里允许「自启动」并把省电策略设为「无限制」。',
          style: TextStyle(
            fontSize: 11,
            color: colors.onSurface.withValues(alpha: 0.6),
          ),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: FilledButton.tonal(
                onPressed: () async {
                  final ok = await NotifyService.instance.requestNotifyPermission();
                  _toast(context, ok ? '通知权限已允许' : '通知权限未允许');
                },
                child: const Text('通知权限'),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: FilledButton.tonal(
                onPressed: () async {
                  final ok =
                      await NotifyService.instance.requestExactAlarmPermission();
                  _toast(context, ok ? '精确闹钟已允许' : '精确闹钟未允许');
                },
                child: const Text('精确闹钟'),
              ),
            ),
          ],
        ),

        // ============ 推送测试 ============
        const SizedBox(height: 16),
        _Label('调试'),
        InkWell(
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const NotifyTestPage()),
          ),
          borderRadius: BorderRadius.circular(AppTheme.settingsCardRadius),
          child: Container(
            constraints: const BoxConstraints(minHeight: 52),
            padding: const EdgeInsets.symmetric(horizontal: 16),
            decoration: BoxDecoration(
              color: colors.surfaceContainerHigh,
              borderRadius: BorderRadius.circular(AppTheme.settingsCardRadius),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    '推送测试',
                    style: TextStyle(fontSize: 15, color: colors.onSurface),
                  ),
                ),
                Icon(Icons.chevron_right_rounded,
                    size: 20,
                    color: colors.onSurface.withValues(alpha: 0.45)),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Future<void> _pickLead(
      BuildContext context, WidgetRef ref, NotifyPrefs cfg) async {
    const options = [5, 10, 15, 30, 60];
    final v = await showDialog<int>(
      context: context,
      builder: (context) => SimpleDialog(
        title: const Text('提前量'),
        children: [
          for (final o in options)
            RadioListTile<int>(
              value: o,
              groupValue: cfg.leadMinutes,
              title: Text('提前 $o 分钟'),
              onChanged: (x) => Navigator.of(context).pop(x),
            ),
        ],
      ),
    );
    if (v == null) return;
    await ref.read(notifyPrefsProvider.notifier).apply(cfg.copyWith(leadMinutes: v));
    await NotifyService.instance.reschedule();
  }

  void _toast(BuildContext context, String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }
}

class _Label extends StatelessWidget {
  const _Label(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 16, bottom: 8),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: colors.onSurface.withValues(alpha: 0.55),
        ),
      ),
    );
  }
}
