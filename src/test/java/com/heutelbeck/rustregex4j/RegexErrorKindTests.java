package com.heutelbeck.rustregex4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Error kinds shared with the engine")
class RegexErrorKindTests {

    @EnumSource
    @ParameterizedTest(name = "{0}")
    @DisplayName("every kind is found again by its code")
    void whenKindIsLookedUpByItsCodeThenItIsFound(RegexErrorKind kind) {
        assertThat(RegexErrorKind.fromCode(kind.getCode())).isEqualTo(kind);
    }

    @ValueSource(ints = { 0, 9, -1 })
    @ParameterizedTest(name = "code {0}")
    @DisplayName("a code the engine does not define is refused")
    void whenCodeIsUnknownThenItIsRefused(int code) {
        assertThatIllegalArgumentException().isThrownBy(() -> RegexErrorKind.fromCode(code))
                .withMessageContaining(Integer.toString(code));
    }

    @Test
    @DisplayName("the codes are the ones the engine reports")
    void whenCodesAreComparedWithTheEngineThenTheyAgree() {
        assertThat(RegexErrorKind.values()).extracting(RegexErrorKind::getCode).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }
}
