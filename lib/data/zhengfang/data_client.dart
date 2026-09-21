import 'dart:convert';

import 'package:dio/dio.dart';

import 'schedule_parser.dart';
import 'session_client.dart';

/// ============================================================
/// 正方数据接口客户端（对应 ZhengfangDataClient.kt）
/// ============================================================
///
/// ⚠️ 三个硬约束（原版踩坑，说明书 §9.4）：
///   1. **必须带 Referer** —— 服务端校验来源
///   2. **必须用真实浏览器 UA** —— 避免风控误判
///   3. `xqm=3` 表示**第一学期**（不是第 3 学期）
class ZhengfangDataClient {
  ZhengfangDataClient(this._dio, {void Function(String)? log})
      : _log = log ?? ((_) {});

  /// 诊断日志回调（由 SyncUseCase 注入，最终写到登录页的诊断面板/日志文件）。
  final void Function(String) _log;

  final Dio _dio;

  static const _base = ZhengfangConfig.jwxtBase;

  /// 拉课表并解析。
  ///
  /// 失败返回 null（会话可能已失效）。
  Future<ZfParseResult?> fetchSchedule(Term term) async {
    try {
      final resp = await _dio.post<dynamic>(
        '$_base/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151',
        data: {
          'kclbdm': '',
          'kclxdm': '',
          'kzlx': 'ck',
          'xnm': term.year,
          'xqm': term.term,
          'xsdm': '',
        },
        options: Options(
          contentType: Headers.formUrlEncodedContentType,
          headers: {
            'User-Agent': ZhengfangConfig.ua,
            'Referer': ZhengfangConfig.scheduleReferer,
          },
          // ⚠️ 服务端可能返回 HTML 登录页（会话失效），不抛异常
          validateStatus: (s) => s != null && s < 500,
        ),
      );
      final body = _bodyOf(resp);
      if (body == null) {
        _log('课表响应为空');
        return null;
      }
      _log('课表 HTTP ' + (resp.statusCode?.toString() ?? '?') + ' 长度=' + body.length.toString());
      _log('课表响应[0..300]=' + body.substring(0, body.length > 300 ? 300 : body.length));
      final parsed = ZfScheduleParser.parse(body);
      _log('课表解析: ' + parsed.courses.length.toString() + ' 门课');
      return parsed;
    } catch (_) {
      return null;
    }
  }

  /// 拉学期日期区间原始文本（cxRsd）。
  ///
  /// ⚠️ 课表数据接口(cxXsgrkb)未必含学期起始日；
  ///    课表页面会另调 cxRsd 拿日期 —— 这里复现该调用。
  Future<String?> fetchScheduleDateRangeRaw(Term term) async {
    try {
      final resp = await _dio.post<dynamic>(
        '$_base/jwglxt/kbcx/xskbcx_cxRsd.html?gnmkdm=N2151',
        data: {
          'xnm': term.year,
          'xqm': term.term,
        },
        options: Options(
          contentType: Headers.formUrlEncodedContentType,
          headers: {
            'User-Agent': ZhengfangConfig.ua,
            'Referer': ZhengfangConfig.scheduleReferer,
          },
          validateStatus: (s) => s != null && s < 500,
        ),
      );
      return _bodyOf(resp);
    } catch (_) {
      return null;
    }
  }

  /// 拉校历/假日原始文本（index_initMenu.html）。
  Future<String?> fetchCalendarRaw() async {
    try {
      final resp = await _dio.get<dynamic>(
        '$_base${ZhengfangConfig.jwxtHome}',
        options: Options(
          headers: {
            'User-Agent': ZhengfangConfig.ua,
            'Referer': '$_base/jwglxt/xtgl/index_initMenu.html',
          },
          validateStatus: (s) => s != null && s < 500,
        ),
      );
      return _bodyOf(resp);
    } catch (_) {
      return null;
    }
  }

  /// 拉成绩原始文本（可能为空，不视为失败）。
  Future<String?> fetchGradesRaw(Term term) async {
    try {
      final resp = await _dio.post<dynamic>(
        '$_base/jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005',
        data: {
          'xnm': term.year,
          'xqm': term.term,
        },
        options: Options(
          contentType: Headers.formUrlEncodedContentType,
          headers: {
            'User-Agent': ZhengfangConfig.ua,
            'Referer': '$_base/jwglxt/cjcx/cjcx_cxXsgrcj.html?gnmkdm=N305005',
          },
          validateStatus: (s) => s != null && s < 500,
        ),
      );
      return _bodyOf(resp);
    } catch (_) {
      return null;
    }
  }

  /// 拉考试原始文本（可能为空）。
  Future<String?> fetchExamsRaw(Term term) async {
    try {
      final resp = await _dio.post<dynamic>(
        '$_base/jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105',
        data: {
          'xnm': term.year,
          'xqm': term.term,
        },
        options: Options(
          contentType: Headers.formUrlEncodedContentType,
          headers: {
            'User-Agent': ZhengfangConfig.ua,
            'Referer': '$_base/jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105',
          },
          validateStatus: (s) => s != null && s < 500,
        ),
      );
      return _bodyOf(resp);
    } catch (_) {
      return null;
    }
  }

  /// 从 Dio 响应取字符串体（兼容服务端返回 JSON 的情况）。
  static String? _bodyOf(Response<dynamic> resp) {
    final d = resp.data;
    if (d == null) return null;
    if (d is String) return d;
    // Dio 在某些 content-type 下会解析成 Map/List，重新序列化
    try {
      return jsonEncode(d);
    } catch (_) {
      return null;
    }
  }
}
