package com.example.volsystem.model.svi.calibration;

import com.example.volsystem.model.optimization.LmLeastSquaresOptimizer;

/**
 * SVI 校准器的配置参数。
 *
 * @param maxIterations       LM 最大迭代次数
 * @param maxEvaluations      最大模型函数求值次数，通常设为 maxIterations 的 2-3 倍
 * @param parameterTolerance  参数收敛阈值：相邻迭代参数最大变化 ≤ 此值时停止
 * @param costTolerance       误差收敛阈值：相邻迭代 RMS 变化 ≤ 此值时停止
 * @param finiteDifferenceStep 数值雅可比的有限差分步长，实际步长 = step * max(1, |param|)
 */
public record SviCalibrationConfig(
        int maxIterations,
        int maxEvaluations,
        double parameterTolerance,
        double costTolerance,
        double finiteDifferenceStep) {

    public SviCalibrationConfig {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive: " + maxIterations + " not allowed");
        }
        if (maxEvaluations <= 0) {
            throw new IllegalArgumentException("maxEvaluations must be positive: " + maxEvaluations + " not allowed");
        }
        if (!(parameterTolerance > 0.0) || !Double.isFinite(parameterTolerance)) {
            throw new IllegalArgumentException("parameterTolerance must be positive and finite: " + parameterTolerance + " not allowed");
        }
        if (!(costTolerance > 0.0) || !Double.isFinite(costTolerance)) {
            throw new IllegalArgumentException("costTolerance must be positive and finite: " + costTolerance + " not allowed");
        }
        if (!(finiteDifferenceStep > 0.0) || !Double.isFinite(finiteDifferenceStep)) {
            throw new IllegalArgumentException("finiteDifferenceStep must be positive and finite: " + finiteDifferenceStep + " not allowed");
        }
    }

    public static SviCalibrationConfig defaults() {
        LmLeastSquaresOptimizer.Config base = LmLeastSquaresOptimizer.Config.defaults();
        return new SviCalibrationConfig(
                base.maxIterations(),
                base.maxEvaluations(),
                base.parameterTolerance(),
                base.costTolerance(),
                1.0e-6);
    }
}
