package com.skillmasterai.modules.account;

import java.util.Optional;

/**
 * M1's public face, as far as P0 needs one.
 *
 * <p>P0 does not implement M1 — registration, login and credentials are P1. This is the minimum
 * M4 needs in order to answer "which namespace is this person's", and nothing else: the personal
 * namespace's slug equals its owner's handle (§3.2), so resolving it requires reading
 * {@code app_user.handle}, which is M1's table and may only be read through M1.
 *
 * <p>Deliberately not a general account service. Every method added here is a column of
 * {@code app_user} that another module is now coupled to, and the point of §2.5 rule 1 is that
 * there should be as few of those as the work actually requires.
 */
public interface AccountDirectory {

    /**
     * @return the user's handle, or empty if no such user exists
     */
    Optional<String> handleOf(String userId);
}
