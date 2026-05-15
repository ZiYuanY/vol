package com.example.volsystem.model.optimization;

public interface ModelCalibrator<TParameters, TData> {

    CalibrationResult<TParameters> calibrate(TData data, TParameters initialGuess);
}
