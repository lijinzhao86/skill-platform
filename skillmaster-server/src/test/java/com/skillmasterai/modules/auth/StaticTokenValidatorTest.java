package com.skillmasterai.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class StaticTokenValidatorTest {

    private static final String USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";
    private static final String TOKEN = "a-token-long-enough-to-be-real";

    @Test
    void acceptsTheConfiguredTokenAndNothingElse() {
        TokenValidator validator = new StaticTokenValidator(TOKEN, USER_ID, Set.of(Scopes.SKILLS_READ));

        assertThat(validator.validate(TOKEN))
                .get()
                .satisfies(subject -> {
                    assertThat(subject.userId()).isEqualTo(USER_ID);
                    assertThat(subject.scopes()).containsExactly(Scopes.SKILLS_READ);
                });
        assertThat(validator.validate("a-token-long-enough-to-be-rea")).isEmpty(); // one char short
        assertThat(validator.validate("A-TOKEN-LONG-ENOUGH-TO-BE-REAL")).isEmpty(); // case matters
        assertThat(validator.validate(null)).isEmpty();
    }

    @Test
    void refusesToStartWithoutAToken() {
        assertThatThrownBy(() -> new StaticTokenValidator(null, USER_ID, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("static-token");
        assertThatThrownBy(() -> new StaticTokenValidator("  ", USER_ID, Set.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesATokenTooShortToBeASecret() {
        assertThatThrownBy(() -> new StaticTokenValidator("secret", USER_ID, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void refusesASubjectThatIsNotAUserId() {
        // A handle instead of a ULID is the easy mistake here, and it would surface much later
        // as "this user owns nothing" rather than as a configuration error.
        assertThatThrownBy(() -> new StaticTokenValidator(TOKEN, "demo", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject-user-id");
    }
}
