package com.example.volsystem.model.svi;

public final class SviSmile {

    private static final double STRIKE_FLOOR = 1.0e-6;

    private final double expiryTime;
    private final double forward;
    private final SviParameters parameters;

    public SviSmile(double expiryTime, double forward, SviParameters parameters) {
        SviValidator.validateFinite(expiryTime, "expiryTime");
        SviValidator.validateFinite(forward, "forward");
        if (expiryTime <= 0.0) {
            throw new IllegalArgumentException("expiry time must be strictly positive: " + expiryTime + " not allowed");
        }
        if (forward <= 0.0) {
            throw new IllegalArgumentException("forward must be positive: " + forward + " not allowed");
        }
        this.expiryTime = expiryTime;
        this.forward = forward;
        this.parameters = parameters;
    }

    public double volatility(double strike) {
        double adjustedStrike = Math.max(STRIKE_FLOOR, strike);
        SviValidator.validateInputs(adjustedStrike, forward, expiryTime);
        double k = Math.log(adjustedStrike / forward);
        double totalVariance = totalVariance(k);
        return Math.sqrt(Math.max(0.0, totalVariance / expiryTime));
    }

    public double variance(double strike) {
        double volatility = volatility(strike);
        return volatility * volatility * expiryTime;
    }

    public double totalVariance(double logMoneyness) {
        SviValidator.validateFinite(logMoneyness, "logMoneyness");

        double x = logMoneyness - parameters.m();
        return parameters.a()
                + parameters.b() * (parameters.rho() * x + Math.sqrt(x * x + parameters.sigma() * parameters.sigma()));
    }
}
