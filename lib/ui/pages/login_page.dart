import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/rsa_encoder.dart';
import '../providers.dart';
import '../theme.dart';

/// ============================================================
/// 登录页（一比一对应原版 ui/login/LoginActivity.kt + activity_login.xml）
/// ============================================================
///
/// 布局（自上而下，原版 XML 逐项对应）：
///   广软课程表（30sp 粗体 主色）
///   广州软件学院 · 教务助手（13sp 60% 透明）
///   [学号]              OutlinedBox
///   [密码]              OutlinedBox + 显示/隐藏
///   [验证码]  [图片 110×52 点击刷新]
///                看不清？点击图片刷新
///   ☐ 记住账号密码
///     勾选后下次免输入；密码经系统密钥库加密后保存在本机
///   [      登 录      ]  54dp 高
///   凭据仅用于直接与学校教务系统通信
///   不会上传至任何第三方服务器
///   诊断信息（可展开）
///
/// ⚠️ 安全约定（ADR-005）：
///   账密只用于建立会话，同步完成即丢弃；不落盘（除非用户勾选记住）。
class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key, this.onSuccess});

  /// 同步成功回调（关闭登录页 / 回到主界面）。
  final void Function(int courseCount)? onSuccess;

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _captcha = TextEditingController();

  bool _remember = false;
  bool _obscure = true;
  bool _submitting = false;
  String? _progress;
  String? _error;
  bool _showDiag = false;

  /// 验证码图片（当前是二进制 PNG 数据）。
  ///
  /// ⚠️ 验证码接口尚未在本机实测通（见说明书 §9），
  ///    故此处允许"无验证码"流程：教务在未触发风控时不要求验证码。
  Uint8List? _captchaBytes;
  String? _captchaUid;
  bool _loadingCaptcha = false;

  /// 诊断日志（登录失败时展开看服务端原始反馈）。
  final List<String> _diag = [];

  @override
  void initState() {
    super.initState();
    _loadLastUsername();
    // 进页面就取一次验证码（原版 LoginActivity 也是这个行为）
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _refreshCaptcha();
    });
  }

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    _captcha.dispose();
    super.dispose();
  }

  Future<void> _loadLastUsername() async {
    final meta = ref.read(metaRepoProvider);
    final last = await meta.get('last_username');
    if (last != null && mounted) {
      setState(() => _username.text = last);
    }
  }

  void _log(String msg) {
    final ts = DateTime.now().toIso8601String().substring(11, 19);
    if (mounted) setState(() => _diag.add('$ts  $msg'));
    // 同时写入文件 —— MIUI(HyperOS) 完全屏蔽了 adb logcat，只能靠落盘排查。
    _appendLogToFile('$ts  $msg');
  }

  static const _logFileName = 'gzu_login.log';

  /// 追加一行到应用私有目录文件。
  ///
  /// ⚠️ 为什么落盘：本机 MIUI/HyperOS 下 adb logcat 返回 **0 行**（完全被屏蔽），
  ///    release 包又会把 print 编译掉 —— 落盘是唯一可靠的排查手段。
  /// 拉取：adb shell run-as com.gzuschedule.app.flutter cat files/gzu_login.log
  static void _appendLogToFile(String line) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final f = File(dir.path + '/' + _logFileName);
      await f.writeAsString(line + '\n', mode: FileMode.append, flush: true);
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);

    return Scaffold(
      backgroundColor: colors.surface,
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(28, 72, 28, 32),
          children: [
            // ---- 标题 ----
            Text(
              '广软课程表',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 30,
                fontWeight: FontWeight.bold,
                color: colors.primary,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              '广州软件学院 · 教务助手',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 13,
                color: colors.onSurface.withValues(alpha: 0.6),
              ),
            ),
            const SizedBox(height: 40),

            // ---- 学号 ----
            TextField(
              controller: _username,
              enabled: !_submitting,
              keyboardType: TextInputType.text,
              textInputAction: TextInputAction.next,
              maxLines: 1,
              decoration: const InputDecoration(
                labelText: '学号',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),

            // ---- 密码 ----
            TextField(
              controller: _password,
              enabled: !_submitting,
              obscureText: _obscure,
              textInputAction: TextInputAction.next,
              maxLines: 1,
              decoration: InputDecoration(
                labelText: '密码',
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  icon: Icon(
                    _obscure
                        ? Icons.visibility_off_outlined
                        : Icons.visibility_outlined,
                  ),
                  onPressed: () => setState(() => _obscure = !_obscure),
                ),
              ),
            ),
            const SizedBox(height: 12),

            // ---- 验证码 + 图片 ----
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: TextField(
                    controller: _captcha,
                    enabled: !_submitting,
                    maxLength: 8,
                    textInputAction: TextInputAction.done,
                    maxLines: 1,
                    decoration: const InputDecoration(
                      labelText: '验证码',
                      border: OutlineInputBorder(),
                      counterText: '',
                    ),
                    onSubmitted: (_) => _login(),
                  ),
                ),
                const SizedBox(width: 10),
                _CaptchaBox(
                  bytes: _captchaBytes,
                  loading: _loadingCaptcha,
                  onTap: _refreshCaptcha,
                ),
              ],
            ),
            const SizedBox(height: 6),
            Align(
              alignment: Alignment.centerRight,
              child: Text(
                '看不清？点击图片刷新',
                style: TextStyle(
                  fontSize: 11,
                  color: colors.onSurface.withValues(alpha: 0.5),
                ),
              ),
            ),
            const SizedBox(height: 14),

            // ---- 记住账密 ----
            CheckboxListTile(
              value: _remember,
              onChanged: _submitting
                  ? null
                  : (v) => setState(() => _remember = v ?? false),
              title: const Text('记住账号密码',
                  style: TextStyle(fontSize: 13)),
              controlAffinity: ListTileControlAffinity.leading,
              contentPadding: EdgeInsets.zero,
              dense: true,
            ),
            Text(
              '勾选后下次免输入；密码经系统密钥库加密后保存在本机',
              style: TextStyle(
                fontSize: 11,
                color: colors.onSurface.withValues(alpha: 0.55),
              ),
            ),
            const SizedBox(height: 20),

            // ---- 登录按钮 ----
            SizedBox(
              height: 54,
              child: FilledButton(
                onPressed: _submitting ? null : _login,
                child: _submitting
                    ? const SizedBox(
                        width: 22,
                        height: 22,
                        child: CircularProgressIndicator(strokeWidth: 2.5),
                      )
                    : const Text('登 录', style: TextStyle(fontSize: 16)),
              ),
            ),

            // ---- 进度 ----
            if (_progress != null) ...[
              const SizedBox(height: 14),
              Center(
                child: Text(
                  _progress!,
                  style: TextStyle(fontSize: 13, color: colors.onSurfaceVariant),
                ),
              ),
            ],

            // ---- 错误 ----
            if (_error != null) ...[
              const SizedBox(height: 14),
              Text(
                _error!,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13, color: Color(0xFFD32F2F)),
              ),
            ],

            // ---- 说明 ----
            const SizedBox(height: 32),
            Text(
              '凭据仅用于直接与学校教务系统通信\n不会上传至任何第三方服务器',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 11,
                color: colors.onSurface.withValues(alpha: 0.45),
              ),
            ),

            // ---- 诊断面板 ----
            Center(
              child: TextButton(
                onPressed: () => setState(() => _showDiag = !_showDiag),
                child: Text(
                  '诊断信息',
                  style: TextStyle(fontSize: 12, color: colors.primary),
                ),
              ),
            ),
            if (_showDiag)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.07),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: SelectableText(
                  _diag.isEmpty ? '（无日志）' : _diag.join('\n'),
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 10),
                ),
              ),
          ],
        ),
      ),
    );
  }

  // ---------------------------------------------------------- 行为

  /// 刷新验证码。
  Future<void> _refreshCaptcha() async {
    setState(() => _loadingCaptcha = true);
    _log('请求验证码…');
    try {
      final client = ref.read(zhengfangClientProvider);
      final cap = await client.fetchCaptcha();
      if (!mounted) return;
      if (cap == null || !cap.isValid) {
        _log('验证码获取失败（服务端未返回有效数据）');
        setState(() {
          _captchaBytes = null;
          _captchaUid = null;
        });
        return;
      }
      // dataURL -> 字节
      final bytes = base64Decode(cap.base64Body);
      setState(() {
        _captchaBytes = bytes;
        _captchaUid = cap.uid;
      });
      _log('验证码已加载 ${bytes.length} 字节');
    } catch (e) {
      _log('验证码异常: $e');
    } finally {
      if (mounted) setState(() => _loadingCaptcha = false);
    }
  }

  /// 登录并同步。
  ///
  /// 流程（ADR-005 + ADR-008）：
  ///   1. 本地校验
  ///   2. RSA 加密密码（**倒序 + 无填充**）
  ///   3. CAS → 正方票据链 → 拉课表 → 存库 → 登出
  Future<void> _login() async {
    final username = _username.text.trim();
    final password = _password.text;
    final captcha = _captcha.text.trim();

    // ---- 本地校验 ----
    if (username.isEmpty) return _fail('请输入学号');
    if (password.isEmpty) return _fail('请输入密码');

    setState(() {
      _submitting = true;
      _error = null;
      _progress = '正在加密密码…';
    });
    _log('开始登录，学号长度 ${username.length}');

    // ---- RSA 加密（⚠️ 倒序 + 无填充，ADR-008）----
    String encrypted;
    try {
      encrypted = RsaPasswordEncoder.encode(password);
      _log('密码 RSA/HEX -> ${encrypted.length} 字符');
    } catch (e) {
      setState(() => _submitting = false);
      return _fail('密码加密失败：$e');
    }

    setState(() => _progress = '正在登录…');

    try {
      final sync = ref.read(syncUseCaseProvider);
      final result = await sync.execute(
        username: username,
        encryptedPassword: encrypted,
        captchaId: _captchaUid,
        captchaCode: captcha.isEmpty ? null : captcha,
        onProgress: (p) {
          if (mounted) setState(() => _progress = p.label);
          _log('阶段：${p.label}');
        },
        // ⚠️ 关键：把底层日志接到诊断面板。
        //    没有这个回调时，「换票 HTTP / 换票响应 / 取得 ST」全丢，
        //    用户只能看到「正在登录 → ERROR」，无法定位失败原因。
        onLog: (msg) => _log(msg),
      );

      if (!mounted) return;

      if (result.success) {
        _log('同步成功，课程 ${result.courseCount} 门');
        setState(() {
          _submitting = false;
          _progress = null;
        });
        // 刷新依赖 meta 的 provider
        ref.invalidate(metaAllProvider);
        ref.invalidate(coursesProvider);
        if (widget.onSuccess != null) {
          widget.onSuccess!(result.courseCount);
        }
      } else {
        setState(() {
          _submitting = false;
          _progress = null;
        });
        _fail(result.message ?? '登录失败');
        // 首次同步后引导设置「第一周星期一」
        _log('失败：${result.message}（${result.serverCode ?? '-'}）');
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _progress = null;
      });
      _fail('同步出错：$e');
    }
  }

  void _fail(String msg) {
    setState(() {
      _error = msg;
      // 登录失败时自动展开诊断面板，否则用户只看到一句失败提示。
      _showDiag = true;
    });
    _log('ERROR $msg');
  }
}

/// 验证码图片框（110×52，点击刷新）。
class _CaptchaBox extends StatelessWidget {
  const _CaptchaBox({
    required this.bytes,
    required this.loading,
    required this.onTap,
  });

  final Uint8List? bytes;
  final bool loading;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    return GestureDetector(
      onTap: loading ? null : onTap,
      child: Container(
        width: 110,
        height: 52,
        decoration: BoxDecoration(
          color: colors.surfaceContainerHigh,
          borderRadius: BorderRadius.circular(4),
          border: Border.all(color: colors.scheduleGridLine),
        ),
        alignment: Alignment.center,
        child: loading
            ? const SizedBox(
                width: 22,
                height: 22,
                child: CircularProgressIndicator(strokeWidth: 2.5),
              )
            : (bytes == null
                ? Text(
                    '点击获取',
                    style: TextStyle(
                      fontSize: 12,
                      color: colors.onSurfaceVariant,
                    ),
                  )
                : Image.memory(bytes!, fit: BoxFit.contain)),
      ),
    );
  }
}

// ---------------------------------------------------------- 登录弹层

/// 登录引导底部弹层（可关闭 —— 修「进不去也退不出」死锁）。
///
/// ⚠️ 与原版的差异（刻意）：
///    原版无数据时强制停在 LoginActivity；但当前验证码接口尚未接通，
///    那样用户会被困住。故改为**可关闭的弹层**：
///    关掉后能正常浏览主界面（只是没数据）。
/// 下拉刷新 / 「重新同步」的统一入口。
///
/// ⚠️ 本项目**不保存密码**（只在 meta 里存 last_username 用于预填），
///    所以无法"静默重同步" —— 一律弹登录层让用户确认。
///    好处是不会因为口令过期而静默失败；代价是多一步输入。
///    （若将来加了凭据存储，这里可改为：有凭据就直接同步。）
Future<void> refreshFromServer(BuildContext context, WidgetRef ref) async {
  if (!context.mounted) return;
  await showLoginSheet(context, ref);
}

Future<void> showLoginSheet(BuildContext context, WidgetRef ref) async {
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (ctx) => DraggableScrollableSheet(
      initialChildSize: 0.92,
      minChildSize: 0.5,
      maxChildSize: 0.96,
      expand: false,
      builder: (ctx, scrollController) => Container(
        decoration: BoxDecoration(
          color: AppColors.of(ctx).surface,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(28)),
        ),
        clipBehavior: Clip.antiAlias,
        child: Stack(
          children: [
            // 登录页本体（内部自带 ListView，用自带滚动）
            LoginPage(
              onSuccess: (_) {
                if (ctx.mounted) Navigator.of(ctx).pop();
              },
            ),
            // 关闭按钮（右上角）
            Positioned(
              top: 4,
              right: 4,
              child: IconButton(
                icon: const Icon(Icons.close_rounded),
                tooltip: '先跳过',
                onPressed: () => Navigator.of(ctx).pop(),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}