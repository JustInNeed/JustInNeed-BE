package com.justinneed.analytics.service;

/** AI JSON 값 읽기 */
final class AnalyticsJson {

    private AnalyticsJson() {
    }

    // Integer · Long · Double 어느 쪽으로든 올 수 있고, 숫자가 아니거나 음수면 0
    static long nonNegative(Object value) {
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }
}
