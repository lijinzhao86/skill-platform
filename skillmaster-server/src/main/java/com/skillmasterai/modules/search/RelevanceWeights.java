package com.skillmasterai.modules.search;

import java.util.Objects;

/**
 * What a text hit is worth, per field.
 *
 * <p>A hit in the skill's name is worth more than one in its title, which is worth more than one in
 * its description — a skill named {@code pdf-tools} is more likely to be about PDFs than one that
 * merely mentions them once. The numbers are a starting point to be tuned against real use, which
 * is why they are configuration rather than constants (§3.4: the ranking has to be explainable,
 * adjustable and regression-tested).
 *
 * <p>Hits add up, so a skill matching in all three fields outranks one matching only in its name.
 * That is the intended reading of "relevance" here: each field is independent evidence, and the
 * weights say how much.
 *
 * <p><strong>Where these numbers stop is as important as where they start.</strong> Recency is
 * deliberately <em>not</em> a term in this score. An earlier shape added one, which made the score
 * depend on the current time — and a score that changes between requests cannot be used as a
 * keyset: every page would re-score the same rows against a different clock, silently skipping or
 * repeating some. Recency is instead a tie-breaker inside a tier, where it needs no clock in the
 * comparison at all. See {@link SkillSearchService}.
 */
public record RelevanceWeights(int name, int title, int description) {

    public static final RelevanceWeights DEFAULTS = new RelevanceWeights(100, 40, 20);

    public RelevanceWeights {
        Objects.requireNonNull(name, "name weight");
        if (name < 0 || title < 0 || description < 0) {
            throw new IllegalArgumentException("weights cannot be negative: "
                    + name + "/" + title + "/" + description);
        }
        if (name < title || title < description) {
            // Not a mathematical requirement, but a configuration where a description hit counts
            // for more than a name hit is a mistake every time, and the results would look almost
            // reasonable. Refusing it at startup costs nothing and beats hearing about it.
            throw new IllegalArgumentException(
                    "weights must not increase down the fields: name=" + name
                            + " title=" + title + " description=" + description);
        }
    }

    /**
     * A stable name for the ordering these weights produce.
     *
     * <p>Goes into every cursor. A cursor carries scores computed under one set of weights, so
     * resuming it under another would compare tiers that do not share a scale — which arrives as a
     * page of arbitrary rows rather than as an error.
     */
    public String orderingId() {
        return "relevance:" + name + "," + title + "," + description;
    }
}
