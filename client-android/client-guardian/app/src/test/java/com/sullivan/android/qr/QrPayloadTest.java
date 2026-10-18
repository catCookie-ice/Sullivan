package com.sullivan.android.qr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link QrPayload} 的编解码往返与类型判定。
 *
 * <p>重点在两件事上：
 * <ol>
 *   <li>往返不丢字符 —— 票据是 Base64url，大小写有意义，归一化时多改一个字母就废票；</li>
 *   <li>绑定码的字母表与长度<b>被钉死</b> —— 它必须与服务端
 *       {@code common/RandomCode.java} 一致，改一边不改另一边会让「手动输入」
 *       在真实设备上静默失败（服务端 404，用户只看到「绑定码不存在」）。</li>
 * </ol>
 */
public class QrPayloadTest {

    /** 与 QrBindCodeServiceTests 里那条断言同样的字母表，写死，不做引用。 */
    private static final String EXPECTED_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

    @Test
    public void bindCodeSurvivesRoundTrip() {
        QrPayload original = QrPayload.bind("K7M2XQ9TB4ND");

        QrPayload parsed = QrPayload.parse(original.encode());

        assertNotNull(parsed);
        assertEquals(QrPayload.Kind.BIND, parsed.kind());
        assertEquals("K7M2XQ9TB4ND", parsed.value());
    }

    /**
     * 票据是大小写敏感的 Base64url，解码后必须**逐字节**等于原值。
     * 这条一旦挂掉，真实场景的表现是「监护人扫到了、也授权了，出示设备却换不出令牌」。
     */
    @Test
    public void loginTicketKeepsItsCaseExactly() {
        String ticket = "N7thzczK14lQ2Tixo9LCpw";
        QrPayload original = QrPayload.login(ticket);

        QrPayload parsed = QrPayload.parse(original.encode());

        assertNotNull(parsed);
        assertEquals(QrPayload.Kind.LOGIN, parsed.kind());
        assertEquals(ticket, parsed.value());
    }

    /** scheme 大小写被别的环节改过时仍要认出来，但取值从原文切。 */
    @Test
    public void schemeMatchIsCaseInsensitiveButValueIsNot() {
        QrPayload parsed = QrPayload.parse("Sullivan://LOGIN?ticket=AbC-123_xYz");

        assertNotNull(parsed);
        assertEquals(QrPayload.Kind.LOGIN, parsed.kind());
        assertEquals("AbC-123_xYz", parsed.value());
    }

    /** 手动输入兜底：12 位裸码，允许小写与分组空格。 */
    @Test
    public void manualEntryOfBareCodeIsNormalized() {
        QrPayload parsed = QrPayload.parse("  k7m2 xq9t b4nd ");

        assertNotNull(parsed);
        assertEquals(QrPayload.Kind.BIND, parsed.kind());
        assertEquals("K7M2XQ9TB4ND", parsed.value());
    }

    /** 形近字符（0/O、1/I）不在字母表里，输入时应被丢掉而不是当成合法位。 */
    @Test
    public void charactersOutsideTheAlphabetAreStripped() {
        QrPayload parsed = QrPayload.parse("K7M2-0XQ9TB4ND");

        assertNotNull(parsed);
        // 0 不在字母表内，去掉之后仍是 12 位，因此还认得出来；
        // 关键是不能让「带 0 的垃圾」悄悄变成另一张有效的码去发请求。
        assertEquals("K7M2XQ9TB4ND", parsed.value());
    }

    /**
     * 真实长度的票据（22 位 Base64url）绝不能被误判成绑定码。
     *
     * <p>裸码分类靠的是「过滤后恰好 12 位」这个形状判断，所以这条要钉住：
     * 否则监护人扫到一张票据、被当成绑定码发去 scan-bind，会得到一个
     * 与事实无关的「绑定码不存在」。
     */
    @Test
    public void realLengthTicketIsNeverMistakenForABindCode() {
        String[] tickets = {
                "N7thzczK14lQ2Tixo9LCpw",
                "sasAEGoPO5bTQnirWbnRmg",
                "mi9CQiWmnexkWcLQ8pyWlQ",
        };
        for (String ticket : tickets) {
            QrPayload parsed = QrPayload.parse(ticket);
            assertNotNull(ticket, parsed);
            assertEquals(ticket, QrPayload.Kind.LOGIN, parsed.kind());
        }
    }

    /** 别的 sullivan:// 路径不猜：认不出来就返回 null，而不是退化成裸码。 */
    @Test
    public void unknownSullivanPathIsRejectedInsteadOfGuessed() {
        assertNull(QrPayload.parse("sullivan://pay?amount=100"));
    }

    @Test
    public void emptyAndNullAreRejected() {
        assertNull(QrPayload.parse(null));
        assertNull(QrPayload.parse(""));
        assertNull(QrPayload.parse("   "));
        assertNull(QrPayload.parse("sullivan://bind?code="));
        assertNull(QrPayload.parse("sullivan://login?ticket="));
    }

    @Test
    public void looksLikeBindCodeAcceptsOnlyBindShapedInput() {
        assertTrue(QrPayload.looksLikeBindCode("K7M2XQ9TB4ND"));
        assertTrue(QrPayload.looksLikeBindCode("sullivan://bind?code=K7M2XQ9TB4ND"));
        assertFalse(QrPayload.looksLikeBindCode("N7thzczK14lQ2Tixo9LCpw"));
        assertFalse(QrPayload.looksLikeBindCode("K7M2"));
        assertFalse(QrPayload.looksLikeBindCode(null));
    }

    /**
     * 与服务端 {@code RandomCode.DICTATABLE_ALPHABET} / {@code QrBindCodeService.CODE_LENGTH}
     * 对齐。这两条断言是**跨仓库的契约**：任何一边改了字母表或长度，这里必须同步改，
     * 而同步的过程中一定会有人看到这条注释。
     */
    @Test
    public void codeAlphabetAndLengthMatchTheServerContract() {
        assertEquals(EXPECTED_ALPHABET, QrPayload.CODE_ALPHABET);
        assertEquals(12, QrPayload.BIND_CODE_LENGTH);
        // 字母表里不许出现形近字符。
        for (char forbidden : new char[]{'0', 'O', '1', 'I', 'L', 'U'}) {
            assertTrue("字母表不应包含 " + forbidden,
                    QrPayload.CODE_ALPHABET.indexOf(forbidden) < 0);
        }
    }
}
