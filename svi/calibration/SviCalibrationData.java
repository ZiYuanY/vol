package com.example.volsystem.model.svi.calibration;

/**
 * SVI 校准所需的市场数据。
 *
 * @param forward      远期价格，必须为正
 * @param expiryTime   到期时间（年化），必须严格为正
 * @param strikes      行权价序列，长度须与 marketVols 一致，且每个 strike 必须为正
 * @param marketVols   市场隐含波动率序列，与 strikes 一一对应
 * @param weights      可选优化权重，null 表示等权；长度须与 marketVols 一致
 */
public record SviCalibrationData(
        double forward,
        double expiryTime,
        double[] strikes,
        double[] marketVols,
        double[] weights) {
}
