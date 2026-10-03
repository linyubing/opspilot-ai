package com.opspilot.ai.forecast.learning;

/** 研究矩阵的训练期标准化参数，支持真实九维宏观输入。 */
final class FusionScale {
    final double[] mean, std;
    FusionScale(double[][] rows) {
        if (rows==null || rows.length==0 || rows[0]==null || rows[0].length==0)
            throw new IllegalArgumentException("标准化需要非空训练矩阵");
        mean = new double[rows[0].length]; std = new double[mean.length]; int count=0;
        for (double[] row:rows) {
            validate(row); count++;
            for (int j=0;j<mean.length;j++) {
                double delta=row[j]-mean[j]; mean[j]+=delta/count; std[j]+=delta*(row[j]-mean[j]);
            }
        }
        for (int j=0;j<mean.length;j++) {
            std[j]=Math.sqrt(Math.max(0,std[j]/count));
            if (!Double.isFinite(mean[j]) || !Double.isFinite(std[j])) throw new IllegalArgumentException("标准化数值过大");
        }
    }
    double[] values(double[] row) {
        validate(row); double[] result=new double[mean.length];
        // 训练期常量列不含可学信息；这是数值变换，不是补缺失数据。
        for (int j=0;j<mean.length;j++) {
            result[j]=std[j]==0?0:(row[j]-mean[j])/std[j];
            if (!Double.isFinite(result[j])) throw new IllegalArgumentException("标准化结果不是有限数值");
        }
        return result;
    }
    private void validate(double[] row) {
        if (row==null || row.length!=mean.length) throw new IllegalArgumentException("矩阵特征维数不一致");
        for (double value:row) if (!Double.isFinite(value)) throw new IllegalArgumentException("真实特征必须是有限数值");
    }
}
