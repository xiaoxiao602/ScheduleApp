import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../data/notify/notify_service.dart';
import '../theme.dart';

/// ============================================================
/// 推送测试模式页（1.2.1）—— 讨论文档 §5.3
/// ============================================================
///
/// 逐类手动触发通知，当场看效果。测试用独立 id（9001..9005），
/// 不与真实课表通知（id 100）相撞。
///
/// ⚠️ v2（+11）自检版：每颗按钮都有 SnackBar 回执（成功/失败原因），
///    顶部实时显示通知权限与精确闹钟状态 —— 排查「点了没反应」。
class NotifyTestPage extends StatefulWidget {
  const NotifyTestPage({super.key});

  @override
  State<NotifyTestPage> createState() => _NotifyTestPageState();
}

class _NotifyTestPageState extends State<NotifyTestPage> {
  bool? _notifOk;
  bool? _exactOk;

  /// 本次会话发送过的常驻类测试（2/3/5）—— 划掉 2 秒内看门狗拉回。
  final Set<int> _watched = {};
  Timer? _watchdog;

  @override
  void initState() {
    super.initState();
    _refreshStatus();
    _watchdog = Timer.periodic(const Duration(seconds: 2), (_) => _patrol());
  }

  @override
  void dispose() {
    _watchdog?.cancel();
    super.dispose();
  }

  /// 划掉重推看门狗：巡查常驻类测试通知，缺失即重发。
  bool _patrolling = false;
  Future<void> _patrol() async {
    if (_patrolling) return; // 防异步重入（+21 全量审计）
    _patrolling = true;
    try {
      for (final n in _watched.toList()) {
        // 测试 3 由闹钟链自驱（+22），巡查跳过避免互相打架。
        if (n == 3) continue;
        if (!(await NotifyService.instance.isTestAlive(n))) {
          await NotifyService.instance.showTest(n);
        }
      }
    } finally {
      _patrolling = false;
    }
  }

  /// 测试 3：启动闹钟驱动的 5 分钟倒计时（+22：离开页面/退后台都不影响）。
  void _startCountdown60() {
    NotifyService.instance.startTestCountdown();
  }

  Future<void> _refreshStatus() async {
    final n = await NotifyService.instance.areNotificationsEnabled();
    final e = await NotifyService.instance.canScheduleExact();
    if (mounted) setState(() { _notifOk = n; _exactOk = e; });
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _send(int n) async {
    if (n == 3) {
      // 5 分钟倒计时（15 秒刷新）—— 用户设计的「是否能一直显示」实验。
      _startCountdown60();
      _toast('已发送：5 分钟倒计时（60 秒刷新）');
      _watched.add(n);
      await _refreshStatus();
      return;
    }
    try {
      await NotifyService.instance.showTest(n);
      _toast('已发送：${NotifyService.testNames[n - 1]}（下拉通知栏查看）');
    } catch (e) {
      _toast('❌ 发送失败：$e');
    }
    // 常驻类（2/3/5）纳入划掉巡查；1（铃声）/4（已下课）划掉即消失。
    if (n == 2 || n == 3 || n == 5) _watched.add(n);
    await _refreshStatus();
  }

  Future<void> _clear(int n) async {
    _watched.remove(n);
    if (n == 3) {
      await NotifyService.instance.stopTestCountdown();
      _toast('已清除：${NotifyService.testNames[n - 1]}');
      return;
    }
    await NotifyService.instance.clearTest(n);
    _toast('已清除：${NotifyService.testNames[n - 1]}');
  }

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);

    return Scaffold(
      backgroundColor: colors.surface,
      appBar: AppBar(title: const Text('推送测试')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 40),
        children: [
          // ---- 自检卡 ----
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: colors.surfaceContainerHigh,
              borderRadius: BorderRadius.circular(AppTheme.settingsCardRadius),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('环境自检',
                    style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: colors.onSurface)),
                const SizedBox(height: 8),
                Text(_statusLine('通知权限', _notifOk),
                    style: const TextStyle(fontSize: 13)),
                Text(_statusLine('精确闹钟', _exactOk),
                    style: const TextStyle(fontSize: 13)),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: FilledButton.tonal(
                        onPressed: () async {
                          final ok = await NotifyService.instance
                              .requestNotifyPermission();
                          _toast(ok ? '通知权限已允许' : '❌ 通知权限未允许 —— 通知会被系统全部丢弃');
                          await _refreshStatus();
                        },
                        child: const Text('授权通知'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: FilledButton.tonal(
                        onPressed: () async {
                          final ok = await NotifyService.instance
                              .requestExactAlarmPermission();
                          _toast(ok ? '精确闹钟已允许' : '❌ 精确闹钟未允许 —— 定时提醒会不准时');
                          await _refreshStatus();
                        },
                        child: const Text('授权精确闹钟'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text('小米手机还需：设置→应用→广软课程表→自启动开、省电策略「无限制」、通知全开。',
                    style: TextStyle(
                        fontSize: 11,
                        color: colors.onSurface.withValues(alpha: 0.6))),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Text(
            '每项独立触发。发送成功必有回执；划掉重推类的通知点「结束显示」才消失。',
            style: TextStyle(
              fontSize: 12,
              color: colors.onSurface.withValues(alpha: 0.6),
            ),
          ),
          const SizedBox(height: 12),
          for (var n = 1; n <= 5; n++) ...[
            _TestCard(
              title: NotifyService.testNames[n - 1],
              desc: _desc(n),
              onSend: () => _send(n),
              onClear: () => _clear(n),
            ),
            const SizedBox(height: 12),
          ],
          const SizedBox(height: 8),
          FilledButton.tonal(
            onPressed: () async {
              await NotifyService.instance.clearTests();
              _toast('已清除全部测试通知');
            },
            child: const Text('全部清除（测试通知）'),
          ),
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: () async {
              await NotifyService.instance.cancelClass();
              _toast('已清除真实课表通知');
            },
            child: const Text('清除真实课表通知'),
          ),
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: () async {
              final log = await NotifyService.instance.readLog();
              if (!context.mounted) return;
              await showDialog<void>(
                context: context,
                builder: (context) => AlertDialog(
                  title: const Text('推送日志（notify_log.txt）'),
                  content: SizedBox(
                    width: double.maxFinite,
                    child: SingleChildScrollView(
                      child: SelectableText(
                        log.isEmpty ? '（空）' : log,
                        style: const TextStyle(fontSize: 11),
                      ),
                    ),
                  ),
                  actions: [
                    TextButton(
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: log));
                        Navigator.of(context).pop();
                      },
                      child: const Text('复制全部'),
                    ),
                    TextButton(
                      onPressed: () => Navigator.of(context).pop(),
                      child: const Text('关闭'),
                    ),
                  ],
                ),
              );
            },
            child: const Text('查看推送日志'),
          ),
        ],
      ),
    );
  }

  static String _statusLine(String label, bool? ok) {
    final v = ok == null ? '检测中…' : (ok ? '✅ 已开启' : '❌ 未开启');
    return '$label：$v';
  }

  static String _desc(int n) {
    switch (n) {
      case 1:
        return '课前铃声提醒（HIGH 渠道，响铃一次）+ 秒表倒数到上课';
      case 2:
        return '课前通知：课程名 + 地点 + 时间（无进度条）';
      case 3:
        return '5 分钟倒计时（60 秒刷新）—— 看岛/通知会不会中途自己关';
      case 4:
        return '下课收尾：显示「已下课」';
      case 5:
        return '把我划掉试试 —— 我会自己回来（点「结束显示」才消失）';
    }
    return '';
  }
}

class _TestCard extends StatelessWidget {
  const _TestCard({
    required this.title,
    required this.desc,
    required this.onSend,
    required this.onClear,
  });

  final String title;
  final String desc;
  final Future<void> Function() onSend;
  final Future<void> Function() onClear;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: colors.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(AppTheme.settingsCardRadius),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title,
              style: TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w600,
                  color: colors.onSurface)),
          const SizedBox(height: 4),
          Text(desc,
              style: TextStyle(
                  fontSize: 12,
                  color: colors.onSurface.withValues(alpha: 0.6))),
          const SizedBox(height: 12),
          Row(
            children: [
              FilledButton.tonal(onPressed: onSend, child: const Text('发送')),
              const SizedBox(width: 12),
              OutlinedButton(onPressed: onClear, child: const Text('清除')),
            ],
          ),
        ],
      ),
    );
  }
}
