package com.bistu.hkgrag.dao.utils;

import java.util.List;
import java.util.function.Function;

public class ScoreNormalizeUtil {
    /**
     * 通用版：对数压缩 + 改良Min-Max归一
     *
     * @param list        任意实体列表
     * @param scoreGetter 函数：从实体中取出原始分数
     * @param scoreSetter 函数：把归一后分数写回实体
     * @param <T>         实体泛型
     */
    public static <T> void logAndImprovedMinMax(List<T> list,
                                                Function<T, Float> scoreGetter,
                                                java.util.function.BiConsumer<T, Float> scoreSetter) {
        if (list == null || list.isEmpty()) {
            return;
        }
        int size = list.size();
        if (size == 1) {
            scoreSetter.accept(list.get(0), 0.5f);
            return;
        }

        float minLog = Float.MAX_VALUE;
        float maxLog = Float.MIN_VALUE;
        double[] logScores = new double[size];

        // 第一步：对数压缩 + 统计最值
        for (int i = 0; i < size; i++) {
            T item = list.get(i);
            float raw = scoreGetter.apply(item);
            double logVal = Math.log(1 + raw);
            logScores[i] = logVal;
            minLog = (float) Math.min(minLog, logVal);
            maxLog = (float) Math.max(maxLog, logVal);
        }

        // 分母防0
        if (Math.abs(maxLog - minLog) < 1e-6) {
            list.forEach(item -> scoreSetter.accept(item, 0.5f));
            return;
        }

        // 第二步：映射到 [0.05, 0.95]
        float range = maxLog - minLog;
        for (int i = 0; i < size; i++) {
            T item = list.get(i);
            double scale = (logScores[i] - minLog) / range;
            float norm = 0.05f + 0.9f * (float) scale;
            scoreSetter.accept(item, norm);
        }
    }
}
