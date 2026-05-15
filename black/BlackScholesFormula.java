package com.example.volsystem.model.black;

// spot是标的当前现价，riskFreeRate 是无风险利率，dividendYield 是 连续分红收益率 / 持有收益率

public final class BlackScholesFormula {

    private static final double VOLATILITY_LOWER_BOUND = 1.0e-8;
    private static final double VOLATILITY_UPPER_BOUND = 5.0;
    private static final double IMPLIED_VOL_TOLERANCE = 1.0e-10;
    private static final int IMPLIED_VOL_MAX_ITERATIONS = 200;
    private static final double SQRT_ONE_HALF = 0.70710678118654752440;
    private static final double MAX_LOG = 7.09782712893383996843E2;
    private static final double[] ERF_T = {
            9.60497373987051638749E0,
            9.00260197203842689217E1,
            2.23200534594684319226E3,
            7.00332514112805075473E3,
            5.55923013010394962768E4
    };
    private static final double[] ERF_U = {
            3.35617141647503099647E1,
            5.21357949780152679795E2,
            4.59432382970980127987E3,
            2.26290000613890934246E4,
            4.92673942608635921086E4
    };
    private static final double[] ERFC_P = {
            2.46196981473530512524E-10,
            5.64189564831068821977E-1,
            7.46321056442269912687E0,
            4.86371970985681366614E1,
            1.96520832956077098242E2,
            5.26445194995477358631E2,
            9.34528527171957607540E2,
            1.02755188689515710272E3,
            5.57535335369399327526E2
    };
    private static final double[] ERFC_Q = {
            1.32281951154744992508E1,
            8.67072140885989742329E1,
            3.54937778887819891062E2,
            9.75708501743205489753E2,
            1.82390916687909736289E3,
            2.24633760818710981792E3,
            1.65666309194161350182E3,
            5.57535340817727675546E2
    };
    private static final double[] ERFC_R = {
            5.64189583547755073984E-1,
            1.27536670759978104416E0,
            5.01905042251180477414E0,
            6.16021097993053585195E0,
            7.40974269950448939160E0,
            2.97886665372100240670E0
    };
    private static final double[] ERFC_S = {
            2.26052863220117276590E0,
            9.39603524938001434673E0,
            1.20489539808096656605E1,
            1.70814450747565897222E1,
            9.60896809063285878198E0,
            3.36907645100081516050E0
    };

    private BlackScholesFormula() {
    }

    /**
     * 方法名：price
     * 作用：使用 Black-Scholes 输入方式（spot、r、q、T、sigma）计算贴现后的欧式期权价格。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向，CALL 或 PUT
     * - spot (double)：标的当前现价
     * - strike (double)：执行价
     * - riskFreeRate (double)：无风险利率
     * - dividendYield (double)：连续分红收益率/持有收益率
     * - expiry (double)：到期时间（年）
     * - volatility (double)：波动率
     * 输出：
     * - double：贴现后的欧式期权价格
     */
    public static double price(
            BlackScholesOptionType optionType,
            double spot,
            double strike,
            double riskFreeRate,
            double dividendYield,
            double expiry,
            double volatility) {
        BlackScholesValidator.validateInputs(spot, strike, riskFreeRate, dividendYield, expiry, volatility);

        if (expiry == 0.0) {
            return intrinsicValue(optionType, spot, strike);
        }

        double discountFactor = Math.exp(-riskFreeRate * expiry);
        double forward = spot * Math.exp((riskFreeRate - dividendYield) * expiry);
        return priceFromForward(optionType, strike, forward, expiry, volatility, discountFactor);
    }

    /**
     * 方法名：priceFromForward
     * 作用：直接使用远期价格 forward 和折现因子 discountFactor 计算贴现后的欧式期权价格。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向，CALL 或 PUT
     * - strike (double)：执行价
     * - forward (double)：远期价格
     * - expiry (double)：到期时间（年）
     * - volatility (double)：波动率
     * - discountFactor (double)：折现因子，例如 exp(-rT)
     * 输出：
     * - double：贴现后的欧式期权价格
     */
    public static double priceFromForward(
            BlackScholesOptionType optionType,
            double strike,
            double forward,
            double expiry,
            double volatility,
            double discountFactor) {
        BlackScholesValidator.validateFinite(expiry, "expiry");
        BlackScholesValidator.validateFinite(volatility, "volatility");

        if (expiry < 0.0) {
            throw new IllegalArgumentException("expiry must be non-negative: " + expiry + " not allowed");
        }
        if (volatility < 0.0) {
            throw new IllegalArgumentException("volatility must be non-negative: " + volatility + " not allowed");
        }
        if (expiry == 0.0) {
            return discountFactor * intrinsicValue(optionType, forward, strike);
        }

        double stdDev = volatility * Math.sqrt(expiry);
        return blackPrice(optionType, strike, forward, stdDev, discountFactor);
    }

    /**
     * 方法名：priceFromForward
     * 作用：直接使用远期价格 forward 计算未贴现的欧式期权价格，行为与 QuantLib 默认 discount=1.0 类似。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向，CALL 或 PUT
     * - strike (double)：执行价
     * - forward (double)：远期价格
     * - expiry (double)：到期时间（年）
     * - volatility (double)：波动率
     * 输出：
     * - double：未贴现的欧式期权价格
     */
    public static double priceFromForward(
            BlackScholesOptionType optionType,
            double strike,
            double forward,
            double expiry,
            double volatility) {
        return priceFromForward(optionType, strike, forward, expiry, volatility, 1.0);
    }

    /**
     * 方法名：blackPrice
     * 作用：使用 QuantLib 风格的 Black 通用接口，输入 forward、stdDev 和 discountFactor 直接计算价格。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向，CALL 或 PUT
     * - strike (double)：执行价
     * - forward (double)：远期价格
     * - stdDev (double)：标准差，等于 volatility * sqrt(expiry)
     * - discountFactor (double)：折现因子
     * 输出：
     * - double：期权价格
     */
    public static double blackPrice(
            BlackScholesOptionType optionType,
            double strike,
            double forward,
            double stdDev,
            double discountFactor) {
        validateBlackInputs(strike, forward, stdDev, discountFactor);

        if (stdDev == 0.0) {
            return discountFactor * intrinsicValue(optionType, forward, strike);
        }
        if (strike == 0.0) {
            return optionType == BlackScholesOptionType.CALL ? forward * discountFactor : 0.0;
        }

        double d1 = Math.log(forward / strike) / stdDev + 0.5 * stdDev;
        double d2 = d1 - stdDev;

        if (optionType == BlackScholesOptionType.CALL) {
            return discountFactor * (forward * normalCdf(d1) - strike * normalCdf(d2));
        }
        return discountFactor * (strike * normalCdf(-d2) - forward * normalCdf(-d1));
    }

    /**
     * 方法名：blackPrice
     * 作用：使用 QuantLib 风格的未贴现 Black 通用接口，默认 discountFactor=1.0。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向，CALL 或 PUT
     * - strike (double)：执行价
     * - forward (double)：远期价格
     * - stdDev (double)：标准差，等于 volatility * sqrt(expiry)
     * 输出：
     * - double：未贴现的期权价格
     */
    public static double blackPrice(
            BlackScholesOptionType optionType,
            double strike,
            double forward,
            double stdDev) {
        return blackPrice(optionType, strike, forward, stdDev, 1.0);
    }

    /**
     * 方法名：impliedVolatility
     * 作用：使用 Black-Scholes 输入方式（spot、r、q、T）根据期权价格反推出隐含波动率。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向，CALL 或 PUT
     * - optionPrice (double)：期权价格
     * - spot (double)：标的当前现价
     * - strike (double)：执行价
     * - riskFreeRate (double)：无风险利率
     * - dividendYield (double)：连续分红收益率/持有收益率
     * - expiry (double)：到期时间（年）
     * 输出：
     * - double：隐含波动率
     */
    public static double impliedVolatility(
            BlackScholesOptionType optionType,
            double optionPrice,
            double spot,
            double strike,
            double riskFreeRate,
            double dividendYield,
            double expiry) {
        BlackScholesValidator.validateFinite(optionPrice, "optionPrice");
        BlackScholesValidator.validateInputs(spot, strike, riskFreeRate, dividendYield, expiry, 0.0);

        if (optionPrice < 0.0) {
            throw new IllegalArgumentException("optionPrice must be non-negative: " + optionPrice + " not allowed");
        }
        if (expiry == 0.0) {
            throw new IllegalArgumentException("implied volatility is undefined for zero expiry");
        }

        double discountFactor = Math.exp(-riskFreeRate * expiry);
        double forward = spot * Math.exp((riskFreeRate - dividendYield) * expiry);
        return impliedVolatilityFromForward(optionType, optionPrice, strike, forward, expiry, discountFactor);
    }

    /**
     * 方法名：impliedVolatilityFromForward
     * 作用：直接使用远期价格 forward 和折现因子 discountFactor，根据期权价格反推出隐含波动率。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向，CALL 或 PUT
     * - optionPrice (double)：期权价格
     * - strike (double)：执行价
     * - forward (double)：远期价格
     * - expiry (double)：到期时间（年）
     * - discountFactor (double)：折现因子
     * 输出：
     * - double：隐含波动率
     */
    public static double impliedVolatilityFromForward(
            BlackScholesOptionType optionType,
            double optionPrice,
            double strike,
            double forward,
            double expiry,
            double discountFactor) {
        BlackScholesValidator.validateFinite(optionPrice, "optionPrice");
        BlackScholesValidator.validateFinite(expiry, "expiry");

        if (optionPrice < 0.0) {
            throw new IllegalArgumentException("optionPrice must be non-negative: " + optionPrice + " not allowed");
        }
        if (expiry <= 0.0) {
            throw new IllegalArgumentException("implied volatility is undefined for non-positive expiry");
        }

        double stdDev = blackImpliedStdDev(optionType, optionPrice, strike, forward, discountFactor);
        return stdDev / Math.sqrt(expiry);
    }

    /**
     * 方法名：impliedVolatilityFromForward
     * 作用：直接使用远期价格 forward，根据未贴现价格反推出隐含波动率，默认 discountFactor=1.0。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向，CALL 或 PUT
     * - optionPrice (double)：未贴现期权价格
     * - strike (double)：执行价
     * - forward (double)：远期价格
     * - expiry (double)：到期时间（年）
     * 输出：
     * - double：隐含波动率
     */
    public static double impliedVolatilityFromForward(
            BlackScholesOptionType optionType,
            double optionPrice,
            double strike,
            double forward,
            double expiry) {
        return impliedVolatilityFromForward(optionType, optionPrice, strike, forward, expiry, 1.0);
    }

    /**
     * 方法名：blackImpliedStdDev
     * 作用：使用 QuantLib 风格的 Black 通用接口，根据价格、forward 和 discountFactor 反推出隐含标准差。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向，CALL 或 PUT
     * - optionPrice (double)：期权价格
     * - strike (double)：执行价
     * - forward (double)：远期价格
     * - discountFactor (double)：折现因子
     * 输出：
     * - double：隐含标准差 stdDev
     */
    public static double blackImpliedStdDev(
            BlackScholesOptionType optionType,
            double optionPrice,
            double strike,
            double forward,
            double discountFactor) {
        BlackScholesValidator.validateFinite(optionPrice, "optionPrice");
        validateBlackInputs(strike, forward, 0.0, discountFactor);

        if (optionPrice < 0.0) {
            throw new IllegalArgumentException("optionPrice must be non-negative: " + optionPrice + " not allowed");
        }

        double intrinsicValue = discountFactor * intrinsicValue(optionType, forward, strike);
        double upperBound = optionType == BlackScholesOptionType.CALL
                ? forward * discountFactor
                : strike * discountFactor;

        if (optionPrice < intrinsicValue - 1.0e-12 || optionPrice > upperBound + 1.0e-12) {
            throw new IllegalArgumentException("optionPrice is outside arbitrage bounds");
        }
        if (Math.abs(optionPrice - intrinsicValue) <= 1.0e-12) {
            return 0.0;
        }

        double lowerStdDev = VOLATILITY_LOWER_BOUND;
        double upperStdDev = VOLATILITY_UPPER_BOUND;
        double upperPrice = blackPrice(optionType, strike, forward, upperStdDev, discountFactor);

        while (upperPrice < optionPrice) {
            upperStdDev *= 2.0;
            if (upperStdDev > 20.0) {
                throw new IllegalArgumentException("failed to bracket implied stdDev");
            }
            upperPrice = blackPrice(optionType, strike, forward, upperStdDev, discountFactor);
        }

        double midStdDev = 0.5 * (lowerStdDev + upperStdDev);
        for (int i = 0; i < IMPLIED_VOL_MAX_ITERATIONS; i++) {
            midStdDev = 0.5 * (lowerStdDev + upperStdDev);
            double midPrice = blackPrice(optionType, strike, forward, midStdDev, discountFactor);

            if (Math.abs(midPrice - optionPrice) < IMPLIED_VOL_TOLERANCE) {
                return midStdDev;
            }
            if (midPrice > optionPrice) {
                upperStdDev = midStdDev;
            } else {
                lowerStdDev = midStdDev;
            }

            if (Math.abs(upperStdDev - lowerStdDev) < IMPLIED_VOL_TOLERANCE) {
                return 0.5 * (lowerStdDev + upperStdDev);
            }
        }
        return midStdDev;
    }

    /**
     * 方法名：blackImpliedStdDev
     * 作用：使用 QuantLib 风格的未贴现 Black 通用接口，默认 discountFactor=1.0，反推出隐含标准差。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向，CALL 或 PUT
     * - optionPrice (double)：未贴现期权价格
     * - strike (double)：执行价
     * - forward (double)：远期价格
     * 输出：
     * - double：隐含标准差 stdDev
     */
    public static double blackImpliedStdDev(
            BlackScholesOptionType optionType,
            double optionPrice,
            double strike,
            double forward) {
        return blackImpliedStdDev(optionType, optionPrice, strike, forward, 1.0);
    }

    /**
     * 方法名：vega
     * 作用：使用 Black-Scholes 输入方式计算 vega，即价格对波动率的一阶导数。
     * 输入：
     * - spot (double)：标的当前现价
     * - strike (double)：执行价
     * - riskFreeRate (double)：无风险利率
     * - dividendYield (double)：连续分红收益率/持有收益率
     * - expiry (double)：到期时间（年）
     * - volatility (double)：波动率
     * 输出：
     * - double：vega
     */
    public static double vega(
            double spot,
            double strike,
            double riskFreeRate,
            double dividendYield,
            double expiry,
            double volatility) {
        BlackScholesValidator.validateInputs(spot, strike, riskFreeRate, dividendYield, expiry, volatility);

        if (expiry == 0.0 || volatility == 0.0) {
            return 0.0;
        }

        double sqrtExpiry = Math.sqrt(expiry);
        double d1 = (Math.log(spot / strike) + (riskFreeRate - dividendYield + 0.5 * volatility * volatility) * expiry)
                / (volatility * sqrtExpiry);
        return spot * Math.exp(-dividendYield * expiry) * sqrtExpiry * normalPdf(d1);
    }

    /**
     * 方法名：validateBlackInputs
     * 作用：校验 Black 通用接口所需的输入是否合法。
     * 输入：
     * - strike (double)：执行价
     * - forward (double)：远期价格
     * - stdDev (double)：标准差
     * - discountFactor (double)：折现因子
     * 输出：
     * - 无（void），非法时抛出异常
     */
    private static void validateBlackInputs(double strike, double forward, double stdDev, double discountFactor) {
        BlackScholesValidator.validateFinite(strike, "strike");
        BlackScholesValidator.validateFinite(forward, "forward");
        BlackScholesValidator.validateFinite(stdDev, "stdDev");
        BlackScholesValidator.validateFinite(discountFactor, "discountFactor");

        if (strike < 0.0) {
            throw new IllegalArgumentException("strike must be non-negative: " + strike + " not allowed");
        }
        if (forward <= 0.0) {
            throw new IllegalArgumentException("forward must be positive: " + forward + " not allowed");
        }
        if (stdDev < 0.0) {
            throw new IllegalArgumentException("stdDev must be non-negative: " + stdDev + " not allowed");
        }
        if (discountFactor <= 0.0) {
            throw new IllegalArgumentException("discountFactor must be positive: " + discountFactor + " not allowed");
        }
    }

    /**
     * 方法名：discountedIntrinsicValue
     * 作用：根据 spot、r、q、T 计算贴现后的内在价值。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向
     * - spot (double)：标的当前现价
     * - strike (double)：执行价
     * - riskFreeRate (double)：无风险利率
     * - dividendYield (double)：连续分红收益率/持有收益率
     * - expiry (double)：到期时间（年）
     * 输出：
     * - double：贴现后的内在价值
     */
    private static double discountedIntrinsicValue(
            BlackScholesOptionType optionType,
            double spot,
            double strike,
            double riskFreeRate,
            double dividendYield,
            double expiry) {
        double forward = spot * Math.exp((riskFreeRate - dividendYield) * expiry);
        return Math.exp(-riskFreeRate * expiry) * intrinsicValue(optionType, forward, strike);
    }

    /**
     * 方法名：intrinsicValue
     * 作用：计算内在价值。
     * 输入：
     * - optionType (BlackScholesOptionType)：期权方向
     * - first (double)：第一个价格输入
     * - second (double)：第二个价格输入
     * 输出：
     * - double：内在价值
     */
    private static double intrinsicValue(BlackScholesOptionType optionType, double first, double second) {
        if (optionType == BlackScholesOptionType.CALL) {
            return Math.max(first - second, 0.0);
        }
        return Math.max(second - first, 0.0);
    }

    /**
     * 方法名：normalPdf
     * 作用：计算标准正态分布密度函数值。
     * 输入：
     * - x (double)：自变量
     * 输出：
     * - double：标准正态密度值
     */
    private static double normalPdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
    }

    /**
     * 方法名：normalCdf
     * 作用：计算标准正态分布累计分布函数值。
     * 输入：
     * - x (double)：自变量
     * 输出：
     * - double：标准正态累计概率
     */
    private static double normalCdf(double x) {
        return 0.5 * erfc(-x * SQRT_ONE_HALF);
    }

    /**
     * 方法名：erfc
     * 作用：计算互补误差函数，用于高精度计算标准正态累计分布。
     * 输入：
     * - x (double)：自变量
     * 输出：
     * - double：互补误差函数值
     */
    private static double erfc(double x) {
        double absoluteX = Math.abs(x);

        if (absoluteX < 1.0) {
            return 1.0 - erf(x);
        }

        double z = -x * x;
        if (z < -MAX_LOG) {
            return x < 0.0 ? 2.0 : 0.0;
        }

        double expZ = Math.exp(z);
        double result;
        if (absoluteX < 8.0) {
            result = expZ * polevl(absoluteX, ERFC_P) / p1evl(absoluteX, ERFC_Q);
        } else {
            result = expZ * polevl(absoluteX, ERFC_R) / p1evl(absoluteX, ERFC_S);
        }

        return x < 0.0 ? 2.0 - result : result;
    }

    /**
     * 方法名：erf
     * 作用：计算误差函数，用于高精度计算标准正态累计分布。
     * 输入：
     * - x (double)：自变量
     * 输出：
     * - double：误差函数值
     */
    private static double erf(double x) {
        if (Math.abs(x) > 1.0) {
            return 1.0 - erfc(x);
        }

        double z = x * x;
        return x * polevl(z, ERF_T) / p1evl(z, ERF_U);
    }

    /**
     * 方法名：polevl
     * 作用：按多项式系数计算多项式值。
     * 输入：
     * - x (double)：自变量
     * - coefficients (double[])：多项式系数数组
     * 输出：
     * - double：多项式结果
     */
    private static double polevl(double x, double[] coefficients) {
        double result = 0.0;
        for (double coefficient : coefficients) {
            result = result * x + coefficient;
        }
        return result;
    }

    /**
     * 方法名：p1evl
     * 作用：按最高次项系数为 1 的形式计算多项式值。
     * 输入：
     * - x (double)：自变量
     * - coefficients (double[])：多项式系数数组
     * 输出：
     * - double：多项式结果
     */
    private static double p1evl(double x, double[] coefficients) {
        double result = x + coefficients[0];
        for (int i = 1; i < coefficients.length; i++) {
            result = result * x + coefficients[i];
        }
        return result;
    }
}
