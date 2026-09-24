import 'package:flutter/services.dart';

/// ============================================================
/// 超级岛通知桥（1.2.1+15，路线①探针版）
/// ============================================================
///
/// 与 MainActivity/IslandChannel 的 'gzuschedule/island' 通道对话：
/// 原生构造通知 + `miui.focus.param` 岛参数（flutter_local_notifications
/// 注入不了 extras）。无岛权限环境自动退化为普通通知，安全。
abstract final class IslandBridge {
  static const _channel = MethodChannel('gzuschedule/island');

  static Future<void> show({
    required int id,
    required String title,
    required String body,
    int whenMs = 0,
    bool chronoDown = true,
    bool hasProgress = false,
    int progress = 0,
    bool lowChannel = true,
    required bool island,
    String stage = '',
    String ticker = '',
    String aodTitle = '',
    String bigTitle = '',
    String bigContent = '',
    String bigFoot = '',
  }) {
    return _channel.invokeMethod('show', {
      'id': id,
      'title': title,
      'body': body,
      'whenMs': whenMs,
      'chronoDown': chronoDown,
      'hasProgress': hasProgress,
      'progress': progress,
      'lowChannel': lowChannel,
      'island': island,
      'stage': stage,
      'ticker': ticker,
      'aodTitle': aodTitle,
      'bigTitle': bigTitle,
      'bigContent': bigContent,
      'bigFoot': bigFoot,
    });
  }

  static Future<void> cancel(int id) {
    return _channel.invokeMethod('cancel', {'id': id});
  }
}
