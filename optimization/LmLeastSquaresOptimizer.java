package com.example.volsystem.model.optimization;

import java.util.Arrays;

import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.ArrayRealVector;
import org.hipparchus.linear.RealMatrix;
import org.hipparchus.optim.ConvergenceChecker;
import org.hipparchus.optim.nonlinear.vector.leastsquares.LeastSquaresBuilder;
import org.hipparchus.optim.nonlinear.vector.leastsquares.LeastSquaresOptimizer;
import org.hipparchus.optim.nonlinear.vector.leastsquares.LeastSquaresProblem;
import org.hipparchus.optim.nonlinear.vector.leastsquares.LevenbergMarquardtOptimizer;
import org.hipparchus.optim.nonlinear.vector.leastsquares.MultivariateJacobianFunction;
import org.hipparchus.util.Pair;

/**
 * 基于 Hipparchus LevenbergMarquardtOptimizer 的最小二乘优化器。
 * 调用方只需提供模型函数和解析雅可比，残差计算由 Hipparchus 内部完成。
 */
public final class LmLeastSquaresOptimizer {

    /**
     * 优化器配置。
     *
     * @param maxIterations      最大迭代次数，超出则视为未收敛
     * @param maxEvaluations     最大函数求值次数，通常设为 maxIterations 的 2-3 倍
     * @param parameterTolerance 参数变化收敛阈值：相邻两次迭代参数最大绝对变化 ≤ 此值时停止
     * @param costTolerance      误差收敛阈值：相邻两次迭代 RMS 变化 ≤ 此值时停止
     */
    public record Config(int maxIterations, int maxEvaluations, double parameterTolerance, double costTolerance) {

        public Config {
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
        }

        public static Config defaults() {
            return new Config(200, 500, 1.0e-10, 1.0e-10);
        }
    }

    /** 优化结果。rms 为最终残差均方根，converged 为 false 时建议检查初值或放宽容差。 */
    public record Result(double[] solution, double rms, int iterations, int evaluations, boolean converged) {
    }

    /**
     * 执行 LM 最小二乘优化。
     * 残差定义为 modelValues(params) - targetValues，Hipparchus 内部最小化残差平方和。
     */
    public Result optimize(LeastSquaresProblemDefinition definition, Config config) {
        validate(definition);

        double[] target = Arrays.copyOf(definition.targetValues(), definition.targetValues().length);
        double[] start = Arrays.copyOf(definition.initialPoint(), definition.initialPoint().length);

        // 每次 LM 迭代都会调用此函数：返回当前参数下的模型值和雅可比矩阵
        MultivariateJacobianFunction jacobianFunction = point -> {
            double[] p = point.toArray();
            double[] model = definition.modelValues().apply(p);
            double[][] jacobian = definition.jacobianValues().apply(p);
            if (model.length != target.length) {
                throw new IllegalArgumentException("model output size does not match target size: " + model.length + " vs " + target.length);
            }
            if (jacobian.length != target.length) {
                throw new IllegalArgumentException("jacobian row count does not match target size: " + jacobian.length + " vs " + target.length);
            }
            for (double[] row : jacobian) {
                if (row.length != start.length) {
                    throw new IllegalArgumentException("jacobian column count does not match parameter size: " + row.length + " vs " + start.length);
                }
            }
            return new Pair<>(new ArrayRealVector(model, false), new Array2DRowRealMatrix(jacobian, false));
        };

        // 满足任一条件即停止：RMS 变化足够小，或参数变化足够小
        ConvergenceChecker<LeastSquaresProblem.Evaluation> checker = (iteration, previous, current) -> {
            if (previous == null || current == null) {
                return false;
            }
            if (Math.abs(previous.getRMS() - current.getRMS()) <= config.costTolerance()) {
                return true;
            }
            double[] previousPoint = previous.getPoint().toArray();
            double[] currentPoint = current.getPoint().toArray();
            double maxDiff = 0.0;
            for (int i = 0; i < previousPoint.length; i++) {
                maxDiff = Math.max(maxDiff, Math.abs(previousPoint[i] - currentPoint[i]));
            }
            return maxDiff <= config.parameterTolerance();
        };

        LeastSquaresBuilder builder = new LeastSquaresBuilder()
                .start(start)
                .target(target)
                .model(jacobianFunction)
                .lazyEvaluation(false)
                .maxIterations(config.maxIterations())
                .maxEvaluations(config.maxEvaluations())
                .checker(checker);

        // 权重以对角矩阵形式传入，对流动性差或噪声大的期权可降低其权重
        if (definition.weights() != null) {
            double[] weights = definition.weights();
            if (weights.length != target.length) {
                throw new IllegalArgumentException("weights size does not match target size: " + weights.length + " vs " + target.length);
            }
            RealMatrix diagonal = org.hipparchus.linear.MatrixUtils.createRealDiagonalMatrix(weights);
            builder.weight(diagonal);
        }

        LeastSquaresProblem problem = builder.build();
        LeastSquaresOptimizer optimizer = new LevenbergMarquardtOptimizer();
        LeastSquaresOptimizer.Optimum optimum = optimizer.optimize(problem);

        // Hipparchus 不直接提供收敛标志，用未触及上限来判断
        boolean converged = optimum.getIterations() < config.maxIterations()
                && optimum.getEvaluations() < config.maxEvaluations();

        return new Result(
                optimum.getPoint().toArray(),
                optimum.getRMS(),
                optimum.getIterations(),
                optimum.getEvaluations(),
                converged);
    }

    private static void validate(LeastSquaresProblemDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("problem definition must not be null");
        }
        if (definition.targetValues() == null || definition.targetValues().length == 0) {
            throw new IllegalArgumentException("targetValues must not be empty");
        }
        if (definition.initialPoint() == null || definition.initialPoint().length == 0) {
            throw new IllegalArgumentException("initialPoint must not be empty");
        }
        if (definition.modelValues() == null) {
            throw new IllegalArgumentException("modelValues must not be null");
        }
        if (definition.jacobianValues() == null) {
            throw new IllegalArgumentException("jacobianValues must not be null");
        }
        for (double v : definition.targetValues()) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("targetValues must be finite");
            }
        }
        for (double v : definition.initialPoint()) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("initialPoint must be finite");
            }
        }
        if (definition.weights() != null) {
            for (double w : definition.weights()) {
                if (!Double.isFinite(w) || w <= 0.0) {
                    throw new IllegalArgumentException("weights must be positive and finite");
                }
            }
        }
    }
}
