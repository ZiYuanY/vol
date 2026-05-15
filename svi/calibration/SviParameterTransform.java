package com.example.volsystem.model.svi.calibration;

import com.example.volsystem.model.svi.SviParameters;
import com.example.volsystem.model.svi.SviValidator;

/**
 * SVI 参数的约束空间与无约束空间之间的双向变换。
 *
 * 支持任意子集固定（通过 SviFixedParameters），无约束向量只包含自由参数，
 * 顺序固定为：rho → sigma → b → a → m（跳过已固定的参数）。
 *
 * 变换规则：
 *   rho   ∈ (-1,1)      → atanh(rho)              逆变换：tanh
 *   sigma > 0           → log(sigma)               逆变换：exp
 *   b     ∈ [0, bMax]   → logit(b/bMax)            逆变换：sigmoid × bMax
 *   a     a+base > 0    → log(a + base)            逆变换：exp - base
 *   m     无约束         → 直接使用                  逆变换：直接使用
 *
 * 其中 base = b·sigma·sqrt(1-rho²)，bMax = 4/(1+|rho|) 来自 SVI 无套利约束。
 * a 的变换引入 base 偏移是为了保证 a + base > 0（SVI 公式的必要条件）。
 */
public final class SviParameterTransform {

    private static final double EPSILON = 1.0e-12;

    public double[] toUnconstrained(SviParameters parameters) {
        return toUnconstrained(parameters, SviFixedParameters.none());
    }

    public double[] toUnconstrained(SviParameters parameters, SviFixedParameters fixedParameters) {
        SviValidator.validateParameters(
                parameters.a(),
                parameters.b(),
                parameters.sigma(),
                parameters.rho(),
                parameters.m());

        SviFixedParameters fixed = fixedParameters == null ? SviFixedParameters.none() : fixedParameters;
        validateFixedValues(fixed);

        int freeCount = freeCount(fixed);
        double[] unconstrained = new double[freeCount];
        int index = 0;

        double rho = fixed.rho() != null ? clampRho(fixed.rho()) : clampRho(parameters.rho());
        double sigma = fixed.sigma() != null ? fixed.sigma() : Math.max(parameters.sigma(), EPSILON);
        double bMax = maxB(rho);
        double b = fixed.b() != null ? clampB(fixed.b(), bMax) : clampB(parameters.b(), bMax);
        double base = b * sigma * Math.sqrt(Math.max(0.0, 1.0 - rho * rho));

        if (fixed.rho() == null) {
            unconstrained[index++] = atanh(rho);
        }
        if (fixed.sigma() == null) {
            unconstrained[index++] = Math.log(sigma);
        }
        if (fixed.b() == null) {
            unconstrained[index++] = logit(clamp01(b / bMax));
        }
        if (fixed.a() == null) {
            unconstrained[index++] = Math.log(Math.max(parameters.a() + base, EPSILON));
        }
        if (fixed.m() == null) {
            unconstrained[index] = parameters.m();
        }

        return unconstrained;
    }

    public SviParameters fromUnconstrained(double[] unconstrained) {
        return fromUnconstrained(unconstrained, SviFixedParameters.none());
    }

    public SviParameters fromUnconstrained(double[] unconstrained, SviFixedParameters fixedParameters) {
        SviFixedParameters fixed = fixedParameters == null ? SviFixedParameters.none() : fixedParameters;
        validateFixedValues(fixed);

        int freeCount = freeCount(fixed);
        if (unconstrained == null || unconstrained.length != freeCount) {
            throw new IllegalArgumentException("unconstrained must contain " + freeCount + " values");
        }
        for (double value : unconstrained) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("unconstrained values must be finite");
            }
        }

        int index = 0;
        double rho = fixed.rho() != null ? clampRho(fixed.rho()) : clampRho(Math.tanh(unconstrained[index++]));
        double sigma = fixed.sigma() != null ? fixed.sigma() : Math.exp(unconstrained[index++]);
        double bMax = maxB(rho);
        double b = fixed.b() != null ? clampB(fixed.b(), bMax) : clamp01(sigmoid(unconstrained[index++])) * bMax;
        double a = fixed.a() != null ? fixed.a() : Math.exp(unconstrained[index++]) - b * sigma * Math.sqrt(Math.max(0.0, 1.0 - rho * rho));
        double m = fixed.m() != null ? fixed.m() : unconstrained[index];

        return new SviParameters(a, b, sigma, rho, m);
    }

    private static int freeCount(SviFixedParameters fixed) {
        int count = 0;
        if (fixed.rho() == null) {
            count++;
        }
        if (fixed.sigma() == null) {
            count++;
        }
        if (fixed.b() == null) {
            count++;
        }
        if (fixed.a() == null) {
            count++;
        }
        if (fixed.m() == null) {
            count++;
        }
        return count;
    }

    private static void validateFixedValues(SviFixedParameters fixed) {
        if (fixed.a() != null) {
            SviValidator.validateFinite(fixed.a(), "fixed a");
        }
        if (fixed.b() != null) {
            SviValidator.validateFinite(fixed.b(), "fixed b");
            if (fixed.b() < 0.0) {
                throw new IllegalArgumentException("fixed b must be non negative");
            }
        }
        if (fixed.sigma() != null) {
            SviValidator.validateFinite(fixed.sigma(), "fixed sigma");
            if (fixed.sigma() <= 0.0) {
                throw new IllegalArgumentException("fixed sigma must be positive");
            }
        }
        if (fixed.rho() != null) {
            SviValidator.validateFinite(fixed.rho(), "fixed rho");
            if (Math.abs(fixed.rho()) >= 1.0) {
                throw new IllegalArgumentException("fixed rho must be in (-1.0, 1.0)");
            }
        }
        if (fixed.m() != null) {
            SviValidator.validateFinite(fixed.m(), "fixed m");
        }

        if (fixed.b() != null && fixed.rho() != null) {
            double limit = maxB(fixed.rho());
            if (fixed.b() > limit) {
                throw new IllegalArgumentException("fixed b is incompatible with fixed rho bound");
            }
        }
    }

    private static double maxB(double rho) {
        return (4.0 / (1.0 + Math.abs(rho))) * (1.0 - EPSILON);
    }

    private static double sigmoid(double value) {
        if (value >= 0.0) {
            double z = Math.exp(-value);
            return 1.0 / (1.0 + z);
        }
        double z = Math.exp(value);
        return z / (1.0 + z);
    }

    private static double logit(double value) {
        return Math.log(value / (1.0 - value));
    }

    private static double atanh(double value) {
        return 0.5 * Math.log((1.0 + value) / (1.0 - value));
    }

    private static double clamp01(double value) {
        return Math.max(EPSILON, Math.min(1.0 - EPSILON, value));
    }

    private static double clampRho(double value) {
        double limit = 1.0 - EPSILON;
        return Math.max(-limit, Math.min(limit, value));
    }

    private static double clampB(double value, double bMax) {
        return Math.max(0.0, Math.min(bMax, value));
    }
}
