package com.example.volsystem.model.svi.calibration;

import java.util.Arrays;

import com.example.volsystem.model.optimization.CalibrationResult;
import com.example.volsystem.model.optimization.LeastSquaresProblemDefinition;
import com.example.volsystem.model.optimization.LmLeastSquaresOptimizer;
import com.example.volsystem.model.optimization.ModelCalibrator;
import com.example.volsystem.model.svi.SviParameters;
import com.example.volsystem.model.svi.SviSmile;
import com.example.volsystem.model.svi.SviValidator;

/**
 * SVI 模型校准器，将市场隐含波动率拟合为 SVI 参数。
 *
 * 优化流程：
 *   1. 将有约束的 SVI 参数映射到无约束空间（SviParameterTransform）
 *   2. 在无约束空间中用 LM 最小化 ∑(模型vol - 市场vol)²
 *   3. 将最优无约束解逆变换回合法的 SVI 参数
 *
 * 支持通过 SviFixedParameters 固定任意参数子集，优化器只迭代自由参数。
 */
public final class SviCalibrator implements ModelCalibrator<SviParameters, SviCalibrationData> {

    // 当模型 vol 计算失败时返回的惩罚值，引导优化器远离无效参数区域
    private static final double PENALTY = 1.0e8;

    private final LmLeastSquaresOptimizer optimizer;
    private final SviParameterTransform transform;
    private final SviCalibrationConfig config;

    public SviCalibrator() {
        this(SviCalibrationConfig.defaults());
    }

    public SviCalibrator(SviCalibrationConfig config) {
        this.optimizer = new LmLeastSquaresOptimizer();
        this.transform = new SviParameterTransform();
        this.config = config;
    }

    @Override
    public CalibrationResult<SviParameters> calibrate(SviCalibrationData data, SviParameters initialGuess) {
        return calibrate(data, initialGuess, SviFixedParameters.none());
    }

    public CalibrationResult<SviParameters> calibrate(
            SviCalibrationData data,
            SviParameters initialGuess,
            SviFixedParameters fixedParameters) {
        SviFixedParameters fixed = fixedParameters == null ? SviFixedParameters.none() : fixedParameters;
        validateData(data, fixed);
        SviValidator.validateParameters(
                initialGuess.a(),
                initialGuess.b(),
                initialGuess.sigma(),
                initialGuess.rho(),
                initialGuess.m());

        double[] target = Arrays.copyOf(data.marketVols(), data.marketVols().length);
        double[] initialPoint = transform.toUnconstrained(initialGuess, fixed);

        LeastSquaresProblemDefinition problem = new LeastSquaresProblemDefinition(
                target,
                point -> modelVols(point, data, fixed),
                point -> jacobian(point, data, fixed),
                initialPoint,
                data.weights() == null ? null : Arrays.copyOf(data.weights(), data.weights().length));

        LmLeastSquaresOptimizer.Result result = optimizer.optimize(problem,
                new LmLeastSquaresOptimizer.Config(
                        config.maxIterations(),
                        config.maxEvaluations(),
                        config.parameterTolerance(),
                        config.costTolerance()));

        SviParameters parameters = transform.fromUnconstrained(result.solution(), fixed);
        SviValidator.validateParameters(
                parameters.a(),
                parameters.b(),
                parameters.sigma(),
                parameters.rho(),
                parameters.m());

        return new CalibrationResult<>(
                parameters,
                result.rms(),
                result.iterations(),
                result.evaluations(),
                result.converged());
    }

    private double[] modelVols(double[] unconstrained, SviCalibrationData data, SviFixedParameters fixedParameters) {
        SviParameters parameters = transform.fromUnconstrained(unconstrained, fixedParameters);
        return modelVols(parameters, data);
    }

    private double[] modelVols(SviParameters parameters, SviCalibrationData data) {
        SviSmile smile = new SviSmile(data.expiryTime(), data.forward(), parameters);
        double[] vols = new double[data.strikes().length];
        for (int i = 0; i < data.strikes().length; i++) {
            vols[i] = safeVolatility(smile, data.strikes()[i]);
        }
        return vols;
    }

    private double[][] jacobian(double[] unconstrained, SviCalibrationData data, SviFixedParameters fixedParameters) {
        int m = data.strikes().length;
        int n = unconstrained.length;
        double[][] jacobian = new double[m][n];

        double[] base = modelVols(unconstrained, data, fixedParameters);
        for (int j = 0; j < n; j++) {
            double[] bumpedUp = Arrays.copyOf(unconstrained, n);
            double[] bumpedDown = Arrays.copyOf(unconstrained, n);
            // 步长随参数量级缩放，避免参数较大时步长相对过小
            double step = config.finiteDifferenceStep() * Math.max(1.0, Math.abs(unconstrained[j]));

            bumpedUp[j] += step;
            bumpedDown[j] -= step;

            double[] upVols = modelVols(bumpedUp, data, fixedParameters);
            double[] downVols = modelVols(bumpedDown, data, fixedParameters);
            double denominator = 2.0 * step;

            for (int i = 0; i < m; i++) {
                double derivative = (upVols[i] - downVols[i]) / denominator;
                if (!Double.isFinite(derivative)) {
                    derivative = (upVols[i] - base[i]) / step;
                }
                jacobian[i][j] = Double.isFinite(derivative) ? derivative : 0.0;
            }
        }

        return jacobian;
    }

    // 捕获 SVI 公式在极端参数下的数值异常，返回惩罚值而非抛出异常，保证优化器能继续迭代
    private static double safeVolatility(SviSmile smile, double strike) {
        try {
            double value = smile.volatility(strike);
            return Double.isFinite(value) ? value : PENALTY;
        } catch (IllegalArgumentException exception) {
            return PENALTY;
        }
    }

    private static void validateData(SviCalibrationData data, SviFixedParameters fixedParameters) {
        if (data == null) {
            throw new IllegalArgumentException("calibration data must not be null");
        }
        if (data.strikes() == null || data.marketVols() == null) {
            throw new IllegalArgumentException("strikes and marketVols must not be null");
        }
        if (data.strikes().length != data.marketVols().length) {
            throw new IllegalArgumentException("strikes and marketVols must have the same size");
        }
        if (data.strikes().length == 0) {
            throw new IllegalArgumentException("at least one data point is required");
        }

        SviValidator.validateFinite(data.forward(), "forward");
        SviValidator.validateFinite(data.expiryTime(), "expiryTime");
        if (data.forward() <= 0.0) {
            throw new IllegalArgumentException("forward must be positive: " + data.forward() + " not allowed");
        }
        if (data.expiryTime() <= 0.0) {
            throw new IllegalArgumentException("expiry time must be strictly positive: " + data.expiryTime() + " not allowed");
        }

        for (int i = 0; i < data.strikes().length; i++) {
            double strike = data.strikes()[i];
            double marketVol = data.marketVols()[i];
            SviValidator.validateFinite(strike, "strike");
            SviValidator.validateFinite(marketVol, "marketVol");
            if (strike <= 0.0) {
                throw new IllegalArgumentException("strike must be positive: " + strike + " not allowed");
            }
            if (marketVol <= 0.0) {
                throw new IllegalArgumentException("marketVol must be positive: " + marketVol + " not allowed");
            }
        }

        if (data.weights() != null) {
            if (data.weights().length != data.marketVols().length) {
                throw new IllegalArgumentException("weights size must match marketVols size");
            }
            for (double weight : data.weights()) {
                if (!Double.isFinite(weight) || weight <= 0.0) {
                    throw new IllegalArgumentException("weights must be positive and finite");
                }
            }
        }

        int freeParameters = freeParameterCount(fixedParameters);
        if (data.strikes().length < Math.max(2, freeParameters)) {
            throw new IllegalArgumentException("insufficient data points for free parameters: " + data.strikes().length + " < " + freeParameters);
        }
    }

    private static int freeParameterCount(SviFixedParameters fixedParameters) {
        int count = 0;
        if (fixedParameters.a() == null) {
            count++;
        }
        if (fixedParameters.b() == null) {
            count++;
        }
        if (fixedParameters.sigma() == null) {
            count++;
        }
        if (fixedParameters.rho() == null) {
            count++;
        }
        if (fixedParameters.m() == null) {
            count++;
        }
        return count;
    }
}
