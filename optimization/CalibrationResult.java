package com.example.volsystem.model.optimization;

public record CalibrationResult<TParameters>(
        TParameters parameters,
        double rmsError,
        int iterations,
        int evaluations,
        boolean converged) {
}
