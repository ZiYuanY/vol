package com.example.volsystem.model.svi.calibration;

/**
 * 校准时需要固定的 SVI 参数子集。null 表示该参数自由优化，非 null 表示固定为给定值。
 *
 * 支持任意组合固定，优化器只对自由参数（非 null 字段）进行迭代。
 * 使用 {@link #none()} 表示全部参数自由，即标准 5 参数校准。
 *
 * @param a     SVI 参数 a（总方差水平偏移），null 表示自由
 * @param b     SVI 参数 b（翼展斜率），null 表示自由，须满足无套利约束 b ≤ 4/(1+|rho|)
 * @param sigma SVI 参数 sigma（ATM 曲率），null 表示自由，须为正
 * @param rho   SVI 参数 rho（偏斜），null 表示自由，须在 (-1, 1)
 * @param m     SVI 参数 m（对数行权价偏移），null 表示自由
 */
public record SviFixedParameters(
        Double a,
        Double b,
        Double sigma,
        Double rho,
        Double m) {

    public static SviFixedParameters none() {
        return new SviFixedParameters(null, null, null, null, null);
    }
}
