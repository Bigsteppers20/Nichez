package app.nichez.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTests {

    @Test
    void phoneKeepsOnlyThePlusAndTheLastFourDigits() {
        assertThat(Masking.phone("+2348031234567")).isEqualTo("+*********4567");
    }

    @Test
    void phoneThatIsNotE164OrIsTooShortIsFullyMasked() {
        assertThat(Masking.phone("08031234567")).isEqualTo("***********");
        assertThat(Masking.phone("+1234")).isEqualTo("*****");
    }

    @Test
    void missingPhoneIsMarkedNotMasked() {
        assertThat(Masking.phone(null)).isEqualTo("<none>");
        assertThat(Masking.phone(" ")).isEqualTo("<none>");
    }

    @Test
    void codeIsNeverShown() {
        assertThat(Masking.code()).isEqualTo("******");
    }
}
