package com.skillmasterai.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UlidTest {

    /** The canonical example from the ULID spec, kept as a frozen compatibility vector. */
    private static final String SPEC_EXAMPLE = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    @Test
    void generatesSomethingItAccepts() {
        for (int i = 0; i < 100; i++) {
            String id = Ulid.generate();
            assertThat(id).hasSize(Ulid.LENGTH);
            assertThat(Ulid.isValid(id))
                    .as("generated id %s must satisfy the validator", id)
                    .isTrue();
        }
    }

    @Test
    void acceptsTheSpecExample() {
        assertThat(Ulid.isValid(SPEC_EXAMPLE)).isTrue();
    }

    @Test
    void encodesTheTimestampInTheFirstTenCharacters() {
        // Deterministic half: the same millisecond must produce the same 10-character prefix.
        String a = Ulid.generate(1_700_000_000_000L);
        String b = Ulid.generate(1_700_000_000_000L);
        assertThat(a.substring(0, 10)).isEqualTo(b.substring(0, 10));
    }

    @Test
    void ordersByTimestampAcrossMilliseconds() {
        // Within one millisecond the random half decides, so only a gap is guaranteed order.
        String earlier = Ulid.generate(1_700_000_000_000L);
        String later = Ulid.generate(1_700_000_001_000L);
        assertThat(earlier).isLessThan(later);
    }

    @Test
    void firstCharacterNeverExceedsSeven() {
        // 48 bits of timestamp occupy 50 bits of character space, so the top character
        // carries only two significant bits. Anything above '7' would not decode back.
        for (int i = 0; i < 100; i++) {
            assertThat(Ulid.generate().charAt(0)).isBetween('0', '7');
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "01ARZ3NDEKTSV4RRFFQ69G5FA",     // 25 characters
            "01ARZ3NDEKTSV4RRFFQ69G5FAVX",   // 27 characters
            "01ARZ3NDEKTSV4RRFFQ69G5FAI",    // I is not in Crockford base32
            "01ARZ3NDEKTSV4RRFFQ69G5FAL",    // L is not in Crockford base32
            "01ARZ3NDEKTSV4RRFFQ69G5FAO",    // O is not in Crockford base32
            "01ARZ3NDEKTSV4RRFFQ69G5FAU",    // U is not in Crockford base32
            "01arz3ndektsv4rrffq69g5fav",    // lowercase is rejected, not folded
            "81ARZ3NDEKTSV4RRFFQ69G5FAV",    // first character out of the 48-bit range
            "01ARZ3NDEKTSV4RRFFQ69G5FA-",    // punctuation
    })
    void rejectsMalformedIds(String candidate) {
        assertThat(Ulid.isValid(candidate)).isFalse();
    }

    @Test
    void rejectsNullAndEmpty() {
        assertThat(Ulid.isValid(null)).isFalse();
        assertThat(Ulid.isValid("")).isFalse();
    }

    @Test
    void requireValidNamesWhatItRejectedInTheMessage() {
        assertThatThrownBy(() -> Ulid.requireValid("nope", "namespace id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace id")
                .hasMessageContaining("nope");
    }

    @Test
    void rejectsTimestampsOutsideTheEncodableRange() {
        assertThatThrownBy(() -> Ulid.generate(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ulid.generate(0x1_0000_0000_0000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
