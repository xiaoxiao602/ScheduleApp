package com.gzuschedule.app.ui.widget

import android.view.animation.Interpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 弹簧插值器（ADR-056）——「液体变形 / Q弹」的物理核心。
 *
 * ⚠️ 为什么不用 `PathInterpolator`：
 *   `PathInterpolator(0.2f, 1.6f, 0.35f, 1f)` 靠控制点做出"过冲"，
 *   但它**只能过冲一次**，然后单调收敛 —— 观感是"弹出去再慢慢拉回"，
 *   像橡皮筋绷紧后松开，**不像液体**。
 *
 *   真实弹簧/液体是**阻尼振荡**：过冲 → 回摆 → 再小幅过冲 → 收敛。
 *   数学上是
 *
 *       x(t) = 1 - e^(-ζωt) · [cos(ω_d t) + (ζω/ω_d)·sin(ω_d t)]
 *
 *   其中 ζ 是阻尼比、ω_d 是有阻尼角频率。本类直接算这个公式。
 *
 * ⚠️ 参数映射：
 *   设置页的「阻尼回弹」滑块范围 100~250（即 1.00~2.50）。
 *   这里把它映射成**阻尼比 ζ**：
 *     ζ = 1.0 → 临界阻尼附近（几乎不过冲，干脆利落）
 *     ζ ≈ 0.35 → 明显回摆（最"Q弹"）
 *   注意方向：**用户调大数值 = 回弹更强 = ζ 更小**。
 */
class SpringInterpolator(
    /** 用户设定的阻尼系数（100~250，即 1.00~2.50）。 */
    damping: Float = 160f,
) : Interpolator {

    /** 阻尼比 ζ：数值越大 → ζ 越小 → 振荡越明显。 */
    private val zeta: Float = run {
        val d = (damping / 100f).coerceIn(1.0f, 2.5f)
        // 1.0 → 0.95（几乎无过冲）；2.5 → 0.22（明显回摆）
        (1.15f - d * 0.37f).coerceIn(0.18f, 0.98f)
    }

    /** 无阻尼角频率（越大越快收敛）。 */
    private val omega: Float = 9.5f

    override fun getInterpolation(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f

        // 有阻尼角频率
        val omegaD = omega * kotlin.math.sqrt(1f - zeta * zeta)

        return 1f -
            exp(-zeta * omega * t) *
            (cos(omegaD * t) + (zeta * omega / omegaD) * sin(omegaD * t))
    }

    companion object {
    }
}
