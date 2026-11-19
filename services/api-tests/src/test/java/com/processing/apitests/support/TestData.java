package com.processing.apitests.support;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Генераторы уникальных идентификаторов. Каждый тест использует собственные
 * значения (PAN, STAN, RRN, merchantId), чтобы не зависеть от данных других
 * тестов и повторных прогонов.
 */
public final class TestData {

    private static final AtomicInteger SEQ = new AtomicInteger(100_000);
    private static final Random RND = new Random();

    private TestData() {
    }

    /** PAN из 16 цифр с заданным BIN (первые 6 цифр). */
    public static String pan(String bin) {
        StringBuilder builder = new StringBuilder(bin);
        for (int i = 0; i < 10; i++) {
            builder.append(RND.nextInt(10));
        }
        return builder.toString();
    }

    /** STAN — ровно 6 цифр. */
    public static String stan() {
        return String.format("%06d", Math.floorMod(SEQ.incrementAndGet(), 1_000_000));
    }

    /** RRN — ровно 12 цифр. */
    public static String rrn() {
        return String.format("%012d", Math.floorMod(SEQ.incrementAndGet(), 1_000_000_000_000L));
    }

    /** merchantId — ровно 15 символов. */
    public static String merchantId() {
        return String.format("MERCH%011d", Math.floorMod(SEQ.incrementAndGet(), 100_000_000_000L));
    }

    /** terminalId — ровно 8 символов. */
    public static String terminalId() {
        return String.format("TERM%04d", Math.floorMod(SEQ.incrementAndGet(), 10_000));
    }
}
