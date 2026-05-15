package svi;

public record SviParameters(double a, double b, double sigma, double rho, double m) {

    public SviParameters {
        SviValidator.validateParameters(a, b, sigma, rho, m);
    }
}
