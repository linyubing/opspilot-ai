package com.opspilot.ai.macrodata;

/** 研究用VIX四特征计算；只接受已选好的完整历史窗口。 */
final class VixFeatures {
    static double[] compute(double[] values) {
        if (values == null || values.length != 21)
            throw new IllegalArgumentException("VIX需要完整21观测窗口");
        for (double value : values)
            if (!Double.isFinite(value) || value <= 0)
                throw new IllegalArgumentException("VIX观测必须是有限正值");
        double[] result = {values[0], (values[0] / values[1] - 1) * 100,
                (values[0] / values[5] - 1) * 100, (values[0] / values[20] - 1) * 100};
        for (double value : result)
            if (!Double.isFinite(value)) throw new IllegalArgumentException("VIX变化计算溢出");
        return result;
    }
}
