package sullivan.serverjava.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryContactCodecTests {

    @Test
    void splitsPhoneIntoLastFourPrefix() {
        RecoveryContactCodec.Segments segments = RecoveryContactCodec.split("13800138000");
        assertThat(segments.type()).isEqualTo("PHONE");
        assertThat(segments.prefix()).isEqualTo("8000");
        assertThat(segments.remainder()).isEqualTo("1380013");
    }

    @Test
    void splitsEmailIntoFirstQuarterOfLocalPart() {
        RecoveryContactCodec.Segments segments = RecoveryContactCodec.split("zhangsan@example.com");
        assertThat(segments.type()).isEqualTo("EMAIL");
        // local=zhangsan(8 字符) 前 1/4 = 2 字符
        assertThat(segments.prefix()).isEqualTo("zh");
        assertThat(segments.remainder()).isEqualTo("angsan@example.com");
    }

    @Test
    void emailPrefixCappedAtFourCharacters() {
        RecoveryContactCodec.Segments segments = RecoveryContactCodec.split("verylonglocalname@example.com");
        // local=verylonglocalname(16 字符) 前 1/4 = 4，达到上限
        assertThat(segments.prefix()).isEqualTo("very");
        assertThat(segments.remainder()).isEqualTo("longlocalname@example.com");
    }

    @Test
    void shortEmailKeepsAtLeastOnePrefixChar() {
        RecoveryContactCodec.Segments segments = RecoveryContactCodec.split("ab@x.com");
        // local=ab 前 1/4 向上取整 = 1 字符
        assertThat(segments.prefix()).isEqualTo("a");
        assertThat(segments.remainder()).isEqualTo("b@x.com");
    }

    @Test
    void phoneMatchesOnlyWhenFullValueAgrees() {
        RecoveryContactCodec.Segments segments = RecoveryContactCodec.split("13800138000");
        String storedCipher = RecoveryContactCodec.cipher(segments.prefix(), segments.remainder());

        assertThat(RecoveryContactCodec.matches("13800138000", segments.prefix(), storedCipher)).isTrue();
        // 同后缀但前段不同的号码
        assertThat(RecoveryContactCodec.matches("13900138000", segments.prefix(), storedCipher)).isFalse();
    }

    @Test
    void emailMatchIsCaseInsensitive() {
        RecoveryContactCodec.Segments segments = RecoveryContactCodec.split("zhangsan@example.com");
        String storedCipher = RecoveryContactCodec.cipher(segments.prefix(), segments.remainder());

        assertThat(RecoveryContactCodec.matches("ZhangSan@Example.com", segments.prefix(), storedCipher)).isTrue();
    }

    @Test
    void wrongPrefixDoesNotMatch() {
        RecoveryContactCodec.Segments segments = RecoveryContactCodec.split("zhangsan@example.com");
        String storedCipher = RecoveryContactCodec.cipher(segments.prefix(), segments.remainder());
        // 前缀不同，直接不匹配
        assertThat(RecoveryContactCodec.matches("lisi@example.com", "zh", storedCipher)).isFalse();
    }

    @Test
    void invalidContactIsRejectedBySplitAndFailsMatch() {
        assertThatThrownBy(() -> RecoveryContactCodec.split("not-a-contact"))
                .isInstanceOf(ApiException.class);
        assertThat(RecoveryContactCodec.matches("not-a-contact", "x", "y")).isFalse();
    }

    @Test
    void maskedFormatsMatchDisplayConvention() {
        assertThat(RecoveryContactCodec.masked("PHONE", "8000")).isEqualTo("****8000");
        assertThat(RecoveryContactCodec.masked("EMAIL", "zh")).isEqualTo("zh****");
    }
}
