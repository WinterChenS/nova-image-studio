package com.nova.studio.infra.logging;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;

/**
 * T3.2 (WIN-13) — encoder pairing the standard console pattern with
 * {@link MaskingPatternLayout}, wired in {@code logback-spring.xml}.
 *
 * <p>{@code PatternLayoutEncoder.start()} would rebuild a plain
 * {@code PatternLayout} over ours, so the base is deliberately not invoked —
 * we construct the masking layout and set the same state flags
 * ({@code layout} + {@code started}) the base would.
 */
public class MaskingPatternLayoutEncoder extends PatternLayoutEncoder {

    @Override
    public void start() {
        MaskingPatternLayout layout = new MaskingPatternLayout();
        layout.setContext(context);
        layout.setPattern(getPattern());
        layout.setOutputPatternAsHeader(isOutputPatternAsHeader());
        layout.start();
        this.layout = layout;
        this.started = true;
    }
}
