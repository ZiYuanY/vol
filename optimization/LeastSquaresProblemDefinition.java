package com.example.volsystem.model.optimization;

import java.util.function.Function;

/**
 * 最小二乘问题的完整描述，由调用方（如 SABR/SVI Calibrator）填充后传入优化器。
 *
 * @param targetValues   目标值，即市场观测的 implied vol 序列
 * @param modelValues    模型函数 f(params) → 模型 vol 序列；残差 = modelValues - targetValues 由 Hipparchus 内部计算
 * @param jacobianValues 解析雅可比矩阵 ∂f/∂params，行对应观测点，列对应参数；解析导数比数值差分更稳定
 * @param initialPoint   参数初始猜测值，LM 对初值敏感，建议选取合理的业务先验值
 * @param weights        可选对角权重，null 表示等权；可用于对流动性好的期权赋予更高权重
 */
public record LeastSquaresProblemDefinition(
        double[] targetValues,
        Function<double[], double[]> modelValues,
        Function<double[], double[][]> jacobianValues,
        double[] initialPoint,
        double[] weights) {
}
