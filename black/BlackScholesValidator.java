package com.example.volsystem.model.black;

public final class BlackScholesValidator {

    private BlackScholesValidator() {
    }

    public static void validateInputs(
            double spot,
            double strike,
            double riskFreeRate,
            double dividendYield,
            double expiry,
            double volatility) {
        validateFinite(spot, "spot");
        validateFinite(strike, "strike");
        validateFinite(riskFreeRate, "riskFreeRate");
        validateFinite(dividendYield, "dividendYield");
        validateFinite(expiry, "expiry");
        validateFinite(volatility, "volatility");

        if (spot <= 0.0) {
            throw new IllegalArgumentException("spot must be positive: " + spot + " not allowed");
        }
        if (strike <= 0.0) {
            throw new IllegalArgumentException("strike must be positive: " + strike + " not allowed");
        }
        if (expiry < 0.0) {
            throw new IllegalArgumentException("expiry must be non-negative: " + expiry + " not allowed");
        }
        if (volatility < 0.0) {
            throw new IllegalArgumentException("volatility must be non-negative: " + volatility + " not allowed");
        }
    }

    public static void validateFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value + " not allowed");
        }
    }
}
