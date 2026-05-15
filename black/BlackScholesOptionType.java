package com.example.volsystem.model.black;

public enum BlackScholesOptionType {
    CALL(1),
    PUT(-1);

    private final int sign;

    BlackScholesOptionType(int sign) {
        this.sign = sign;
    }

    public int sign() {
        return sign;
    }
}
