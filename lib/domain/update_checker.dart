import 'dart:convert';

import 'package:http/http.dart' as http;

import 'core_logic.dart' show VersionCompare;

/// ============================================================
/// 检查更新（对应原版 data/update/UpdateChecker.kt）
/// ============================================================
///
/// 数据源：GitHub Releases API
///   GET https://api.github.com/repos/{owner}/{repo}/releases/latest
///   Header: Accept: application/vnd.github+json
///
/// 比对本机 versionName 与 tag_name，得出是否有新版。
///
/// ⚠️ 本仓库是 public —— 无需 token。GitHub 对匿名请求限流 60 次/小时，
///    对"用户手动点一次"完全够用。
/// ⚠️ 不要在这里做"自动下载安装" —— 应用商店/系统权限都不允许静默安装；
///    原版也只是打开浏览器让用户自己下。

/// 一次检查的结果。
class UpdateInfo {
  const UpdateInfo({
    required this.hasUpdate,
    required this.currentVersion,
    required this.latestVersion,
    this.releaseUrl,
    this.downloadUrl,
    this.notes,
    this.publishedAt,
  });

  final bool hasUpdate;
  final String currentVersion;
  final String latestVersion;

  /// Release 页面（用户点「去下载」时打开这个）。
  final String? releaseUrl;

  /// 直接的 APK 下载地址（Release 里的 asset）。
  final String? downloadUrl;

  final String? notes;
  final String? publishedAt;
}

abstract final class UpdateChecker {
  /// ⚠️ 改成你的仓库坐标。
  static const owner = 'xiaoxiao602';
  static const repo = 'ScheduleApp';

  static Uri get _api =>
      Uri.parse('https://api.github.com/repos/$owner/$repo/releases/latest');

  /// 检查更新。[currentVersion] 形如 "1.2.0"。
  static Future<UpdateInfo> check(String currentVersion) async {
    final resp = await http.get(
      _api,
      headers: const {
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'GzuSchedule/Flutter',
      },
    ).timeout(const Duration(seconds: 15));

    if (resp.statusCode != 200) {
      throw Exception('GitHub API ${resp.statusCode}');
    }

    final json = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    final tag = (json['tag_name'] as String? ?? '').replaceAll('v', '').trim();
    final htmlUrl = json['html_url'] as String?;
    final body = json['body'] as String?;
    final published = json['published_at'] as String?;

    // 从 assets 里找 .apk
    String? apk;
    final assets = json['assets'];
    if (assets is List) {
      for (final a in assets) {
        if (a is Map && (a['name'] as String? ?? '').toLowerCase().endsWith('.apk')) {
          apk = a['browser_download_url'] as String?;
          break;
        }
      }
    }

    return UpdateInfo(
      hasUpdate: tag.isNotEmpty && VersionCompare.isNewer(tag, currentVersion),
      currentVersion: currentVersion,
      latestVersion: tag,
      releaseUrl: htmlUrl,
      downloadUrl: apk,
      notes: body,
      publishedAt: published,
    );
  }
}
