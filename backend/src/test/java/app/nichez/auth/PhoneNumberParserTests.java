package app.nichez.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nichez.auth.service.PhoneNumberParser;
import app.nichez.auth.service.PhoneNumberParser.ParsedPhone;
import app.nichez.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PhoneNumberParserTests {

    private final PhoneNumberParser parser = new PhoneNumberParser();

    @Test
    void parsesANigerianMobileToE164() {
        assertThat(parser.parse("+234", "803 123 4567")).isEqualTo(new ParsedPhone("+2348031234567", "NG"));
    }

    @Test
    void dropsTheNationalTrunkZeroAndAcceptsADialCodeWithoutPlus() {
        assertThat(parser.parse("234", "0803-123-4567")).isEqualTo(new ParsedPhone("+2348031234567", "NG"));
    }

    @Test
    void parsesOtherRegions() {
        assertThat(parser.parse("+1", "(650) 253-0000")).isEqualTo(new ParsedPhone("+16502530000", "US"));
        assertThat(parser.parse("+44", "7400 123456")).isEqualTo(new ParsedPhone("+447400123456", "GB"));
    }

    @ParameterizedTest
    @CsvSource({
            "+234, 123",              // too short
            "+234, 80312345678901",   // too long
            "+234, 2348031234567",    // dial code typed twice
            "+234, 80CALLNOW",        // letters
            "+234, ''",               // empty
            "abc, 8031234567",        // dial code is not a number
            "+99999, 8031234567",     // dial code too long
            "+999, 8031234567",       // no such country
    })
    void rejectsNumbersThatAreNotValid(String dialCode, String national) {
        assertThatThrownBy(() -> parser.parse(dialCode, national))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_PHONE");
                    assertThat(e.status().value()).isEqualTo(400);
                    // The message never echoes what was typed.
                    assertThat(e.getMessage()).doesNotContain(national.isBlank() ? "\0" : national);
                });
    }
}
