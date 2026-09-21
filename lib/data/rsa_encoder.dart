import 'dart:convert';
import 'dart:typed_data';

/// LYUAP 密码加密器 —— Dart 移植版。
///
/// 算法（与 Kotlin 版 `RsaPasswordEncoder.kt` 逐字节等价）：
///   1. 密码倒序      "Example@Pass123" -> "321ssaP@elpmaxE"
///   2. UTF-8 字节转大整数
///   3. 无填充 RSA    c = m^e mod n
///   4. 定长（128 字节）输出，转小写 hex（256 字符）
///
/// ⚠️ 关键点：
///   - 填充方式: NoPadding（不是 PKCS1Padding）
///   - 明文必须先倒序
///   - Dart 的 BigInt 原生支持 modPow，无需第三方库
class RsaPasswordEncoder {
  /// LYUAP 硬编码公钥（RSA-1024）。
  static const String modulusHex =
      '00b5eeb166e069920e80bebd1fea4829d3d1f3216f2aabe79b6c47a3c18dcee5'
      'fd22c2e7ac519cab59198ece036dcf289ea8201e2a0b9ded307f8fb704136eae'
      'b670286f5ad44e691005ba9ea5af04ada5367cd724b5a26fdb5120cc95b64316'
      '04bd219c6b7d83a6f8f24b43918ea988a76f93c333aa5a20991493d4eb1117e7b1';

  static const String exponentHex = '010001';

  static final BigInt _modulus = BigInt.parse(modulusHex, radix: 16);
  static final BigInt _exponent = BigInt.parse(exponentHex, radix: 16);

  /// 密钥字节长度（1024 bit -> 128 字节）。
  static int get keyBytes => (_modulus.bitLength + 7) ~/ 8;

  /// 密文编码方式。
  static RsaEncoding encoding = RsaEncoding.hex;

  /// 是否对明文做倒序（抓包证实必须倒序）。
  static bool reversePlaintext = true;

  /// 按 LYUAP 算法加密密码。
  static String encode(String password) {
    final raw = reversePlaintext
        ? password.split('').reversed.join()
        : password;
    final bytes = utf8.encode(raw);

    if (bytes.length > keyBytes) {
      throw ArgumentError(
          '密码过长: ${bytes.length} 字节 > 密钥 $keyBytes 字节');
    }

    // 无填充 RSA: c = m^e mod n
    // 字节转大整数（等价于 BigInteger(1, bytes) —— 视为无符号）
    BigInt m = BigInt.zero;
    for (final b in bytes) {
      m = (m << 8) | BigInt.from(b);
    }

    final c = m.modPow(_exponent, _modulus);

    // 转成定长 byte 数组（左侧补零到 keyBytes）
    final out = _toFixedLengthBytes(c, keyBytes);

    switch (encoding) {
      case RsaEncoding.hex:
        return out.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
      case RsaEncoding.base64:
        return base64.encode(out);
    }
  }

  /// BigInt -> 定长字节数组（大端，左侧补零）。
  /// 等价于 Kotlin 的 `BigInteger.toByteArray()` + 去符号位 + 补零逻辑。
  static Uint8List _toFixedLengthBytes(BigInt value, int length) {
    final result = Uint8List(length);
    var v = value;
    final mask = BigInt.from(0xff);
    for (var i = length - 1; i >= 0; i--) {
      result[i] = (v & mask).toInt();
      v = v >> 8;
    }
    return result;
  }

  /// 供测试与排查：暴露模数位长。
  static int keyBits() => _modulus.bitLength;
}

enum RsaEncoding { hex, base64 }

/// 自检：与真机抓包密文逐字节比对。
void main() {
  const captured =
      '475c33b04048213a7eb378f4d1da9c67ca3fe0af2783c8aa61ac650062eb'
      'fa823535f8cd439f77f50d0d6c5e2edaf7793ffed7f68f25f506104a6edb'
      '05b4b751bc75ed8dc614ff6bc4c4bac63de51f88615c3fc4ecc9d0e8d188'
      '985e158d6efb80ce514259dfd0ab3f0320deabd274ab1e179e8f878c6bda'
      '2c921f67fc3464e3';

  const password = 'Example@Pass123';

  print('=== 模数位长: ${RsaPasswordEncoder.keyBits()} bit '
      '(应为 1024) ===');
  print('=== 密钥字节: ${RsaPasswordEncoder.keyBytes} (应为 128) ===');
  print('');

  final out = RsaPasswordEncoder.encode(password);
  print('输入密码 : $password');
  print('倒序明文 : ${password.split('').reversed.join()}');
  print('Dart 输出: $out');
  print('抓包密文 : $captured');
  print('长度     : ${out.length} (应为 256)');
  print('');
  print(out == captured
      ? '✅ 通过 —— Dart 实现与真机抓包逐字节一致'
      : '❌ 失败 —— 与抓包不一致');

  // 确定性
  final out2 = RsaPasswordEncoder.encode(password);
  print('确定性   : ${out == out2 ? "✅ 两次相同（无填充验证）" : "❌ 不确定"}');

  // 不倒序必须不同
  RsaPasswordEncoder.reversePlaintext = false;
  final noReverse = RsaPasswordEncoder.encode(password);
  RsaPasswordEncoder.reversePlaintext = true;
  print('倒序必需 : ${noReverse != captured ? "✅ 不倒序则不同" : "❌"}');
}
