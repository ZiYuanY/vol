package com.example.volsystem.model.svi;

public final class SviValidator {

    private SviValidator() {
    }

    public static void validateParameters(double a, double b, double sigma, double rho, double m) {
        validateFinite(a, "a");
        validateFinite(b, "b");
        validateFinite(sigma, "sigma");
        validateFinite(rho, "rho");
        validateFinite(m, "m");

        if (b < 0.0) {
            throw new IllegalArgumentException("b must be non negative: " + b + " not allowed");
        }
        if (Math.abs(rho) >= 1.0) {
            throw new IllegalArgumentException("rho must be in (-1.0, 1.0): " + rho + " not allowed");
        }
        if (sigma <= 0.0) {
            throw new IllegalArgumentException("sigma must be positive: " + sigma + " not allowed");
        }
        double lhs = a + b * sigma * Math.sqrt(1.0 - rho * rho);
        if (lhs < 0.0) {
            throw new IllegalArgumentException(
                    "a + b * sigma * sqrt(1-rho^2) must be non negative: " + lhs + " not allowed");
        }
        if (b * (1.0 + Math.abs(rho)) > 4.0) {
            throw new IllegalArgumentException("b * (1 + |rho|) must be <= 4.0: " + b + ", " + rho + " not allowed");
        }
    }

    public static void validateInputs(double strike, double forward, double expiryTime) {
        validateFinite(strike, "strike");
        validateFinite(forward, "forward");
        validateFinite(expiryTime, "expiryTime");

        if (strike <= 0.0) {
            throw new IllegalArgumentException("strike must be positive: " + strike + " not allowed");
        }
        if (forward <= 0.0) {
            throw new IllegalArgumentException("forward must be positive: " + forward + " not allowed");
        }
        if (expiryTime <= 0.0) {
            throw new IllegalArgumentException("expiry time must be strictly positive: " + expiryTime + " not allowed");
        }
    }

    public static void validateFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value + " not allowed");
        }
    }
}
