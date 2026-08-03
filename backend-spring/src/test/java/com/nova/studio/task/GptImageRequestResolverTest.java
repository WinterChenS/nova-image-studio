package com.nova.studio.task;

import com.nova.studio.imagegen.GptImageRequestResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1.3 — GPT image size / advanced-params resolution (ports of the Node
 * {@code getGptImageSize} / {@code normalizeCustomImageSize} /
 * {@code resolveGptImageRequestSize}).
 */
class GptImageRequestResolverTest {

    @Test
    void squareRatio1K2K4K() {
        assertThat(GptImageRequestResolver.getGptImageSize("1K", "1:1")).isEqualTo("1024x1024");
        assertThat(GptImageRequestResolver.getGptImageSize("2K", "1:1")).isEqualTo("2048x2048");
        assertThat(GptImageRequestResolver.getGptImageSize("4K", "1:1")).isEqualTo("3840x3840");
    }

    @Test
    void landscapeAndPortrait1K() {
        assertThat(GptImageRequestResolver.getGptImageSize("1K", "16:9")).isEqualTo("1824x1024");
        assertThat(GptImageRequestResolver.getGptImageSize("1K", "9:16")).isEqualTo("1024x1824");
        assertThat(GptImageRequestResolver.getGptImageSize("1K", "3:2")).isEqualTo("1536x1024");
    }

    @Test
    void autoOr512YieldsNoSize() {
        assertThat(GptImageRequestResolver.getGptImageSize("auto", "1:1")).isNull();
        assertThat(GptImageRequestResolver.getGptImageSize("512", "1:1")).isNull();
        assertThat(GptImageRequestResolver.getGptImageSize("1K", "auto")).isNull();
    }

    @Test
    void customSizeNormalizedToMultipleOf16Within4096() {
        assertThat(GptImageRequestResolver.normalizeCustomImageSize("1050x700", 4096)).isEqualTo("1056x704");
        assertThat(GptImageRequestResolver.normalizeCustomImageSize("1024x1024", 4096)).isEqualTo("1024x1024");
        // aspect ratio > 3 rejected
        assertThat(GptImageRequestResolver.normalizeCustomImageSize("4096x1024", 4096)).isNull();
        // below min pixels rejected
        assertThat(GptImageRequestResolver.normalizeCustomImageSize("512x512", 4096)).isNull();
        // invalid format rejected
        assertThat(GptImageRequestResolver.normalizeCustomImageSize("wide", 4096)).isNull();
    }

    @Test
    void resolvePreferCustomOverPreset() {
        assertThat(GptImageRequestResolver.resolveGptImageRequestSize("1050x700", "gpt-image-1", "1K", "1:1"))
                .isEqualTo("1056x704");
        assertThat(GptImageRequestResolver.resolveGptImageRequestSize(null, "gpt-image-1", "1K", "1:1"))
                .isEqualTo("1024x1024");
    }

    @Test
    void advancedParamsValidateEnumsWithAutoDefaults() {
        GptImageRequestResolver.AdvancedParams p = GptImageRequestResolver.normalizeGptImageAdvancedParams(
                "high", "natural", "transparent");
        assertThat(p.quality()).isEqualTo("high");
        assertThat(p.style()).isEqualTo("natural");
        assertThat(p.background()).isEqualTo("transparent");

        GptImageRequestResolver.AdvancedParams defaults = GptImageRequestResolver.normalizeGptImageAdvancedParams(
                null, null, null);
        assertThat(defaults.quality()).isEqualTo("auto");
        assertThat(defaults.style()).isEqualTo("auto");
        assertThat(defaults.background()).isEqualTo("auto");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> GptImageRequestResolver.normalizeGptImageAdvancedParams("bogus", null, null));
    }

    @Test
    void imageStreamUnsupportedPatternMatches() {
        assertThat(ImageGenStreamSupport.isUnsupported("The parameter stream is not supported by this model"))
                .isTrue();
        assertThat(ImageGenStreamSupport.isUnsupported("partial_images 参数不支持")).isTrue();
        assertThat(ImageGenStreamSupport.isUnsupported("all good")).isFalse();
    }

    static final class ImageGenStreamSupport {
        static boolean isUnsupported(String message) {
            return com.nova.studio.imagegen.ImageGenService.isImageStreamUnsupportedError(
                    new IllegalStateException(message));
        }
    }
}
