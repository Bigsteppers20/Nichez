package app.nichez.profile;

import static org.assertj.core.api.Assertions.assertThat;

import app.nichez.profile.service.HandleRules;
import app.nichez.profile.service.HandleRules.Violation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HandleRulesTests {

    private static final List<String> RESERVED = List.of("admin", "help", "root", "staff", "support", "moderator",
            "legal", "official");
    private static final List<String> VERIFIED = List.of("pivotmaster", "Tobi_Adeyemi");

    private static Optional<Violation> check(String handle) {
        return HandleRules.check(handle, RESERVED, VERIFIED);
    }

    // --- characters and length -------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"abc", "a_b", "a.b", "Mixed.Case_9", "user123", "123", "a1b",
            "abcdefghijklmnopqrstuvwxyz0123"})   // last one is exactly 30
    void acceptsLettersDigitsUnderscoreAndDotFromThreeToThirtyCharacters(String handle) {
        assertThat(HandleRules.isWellFormed(handle)).isTrue();
        assertThat(check(handle)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ab", "a", "abcdefghijklmnopqrstuvwxyz01234",   // 31 characters
            "has space", " lead", "trail ", "dash-ed", "at@sign", "emoji😀x", "émile", "tab\tbed", "new\nline",
            "slash/es", "plus+", "ünï"})
    void rejectsWrongCharactersAndWrongLength(String handle) {
        assertThat(HandleRules.isWellFormed(handle)).isFalse();
        assertThat(check(handle)).contains(Violation.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"___", "...", "._.", "_._._"})
    void rejectsAHandleWithNoLetterOrDigit(String handle) {
        assertThat(check(handle)).contains(Violation.INVALID);
    }

    @Test
    void lengthIsCheckedBeforeReservedSoAShortReservedWordIsInvalid() {
        assertThat(HandleRules.check("me", List.of("me"), List.of())).contains(Violation.INVALID);
    }

    // --- reserved --------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"admin", "ADMIN", "Admin", "aDmIn", "support", "Moderator"})
    void rejectsReservedHandlesInAnyCase(String handle) {
        assertThat(check(handle)).contains(Violation.RESERVED);
    }

    @Test
    void aHandleThatMerelyContainsAReservedWordIsFine() {
        assertThat(check("administrator_fan")).isEmpty();
        assertThat(check("myadmin")).isEmpty();
        assertThat(check("helpful")).isEmpty();
    }

    // --- lookalikes ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "ad.min", "a_d_m_i_n", "ad_min", "A.D.M.I.N",     // dots and underscores are stripped
            "adrnin", "ad.rnin",                               // rn looks like m: adrnin -> admin
            "he1p", "He1p",                                    // 1 looks like l
            "r00t", "R0OT",                                    // 0 looks like o
            "5taff", "5upport",                                // 5 looks like s
            "Iegal", "IEGAL", "1egal",                         // I, i, l and 1 look alike
            "0fficial",                                        // 0 looks like o
            "rnoderator", "rn0derator"})                       // rn looks like m, then 0 looks like o
    void rejectsLookalikesOfReservedHandles(String handle) {
        assertThat(check(handle)).as(handle).contains(Violation.RESERVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pivotmaster", "PivotMaster", "pivot.master", "pivot_master", "pivotrnaster",
            "tobi.adeyemi", "TOBI__ADEYEMI", "t0bi_adeyemi"})
    void rejectsLookalikesOfVerifiedHandles(String handle) {
        assertThat(check(handle)).as(handle).contains(Violation.RESERVED);
    }

    @Test
    void aHandleCloseToAVerifiedOneButNotLookingTheSameIsFine() {
        assertThat(check("pivotmasters")).isEmpty();
        assertThat(check("pivot.masters")).isEmpty();
        assertThat(check("tobiwan")).isEmpty();
    }

    @Test
    void verifiedHandlesAreOnlyAsStrictAsTheListTheCallerPasses() {
        // The service leaves the claimant's own verified handle out, so they can change its case.
        assertThat(HandleRules.check("PivotMaster", RESERVED, List.of())).isEmpty();
    }

    // --- skeleton --------------------------------------------------------------------

    @Test
    void skeletonFoldsLookalikeCharacters() {
        assertThat(HandleRules.skeleton("Il1.O0_5rn")).isEqualTo("llloosm");
        assertThat(HandleRules.skeleton("admin")).isEqualTo("admln");
        assertThat(HandleRules.skeleton("ADMIN")).isEqualTo("admln");
    }

    @Test
    void iAndLAndOneAllFoldToTheSameLetter() {
        for (String variant : List.of("I", "i", "l", "L", "1")) {
            assertThat(HandleRules.skeleton("ab" + variant)).as(variant).isEqualTo("abl");
        }
    }

    @Test
    void anAllCapsHandleWithDotsStillMatchesItsLowerCaseReservedForm() {
        assertThat(check("AD.MIN")).contains(Violation.RESERVED);
        assertThat(check("adm1n")).contains(Violation.RESERVED);
        assertThat(check("admIn")).contains(Violation.RESERVED);
    }

    @Test
    void dotsAreRemovedBeforeRnBecomesM() {
        assertThat(HandleRules.skeleton("r.n")).isEqualTo("m");
        assertThat(HandleRules.skeleton("r_n")).isEqualTo("m");
        assertThat(HandleRules.skeleton("corner")).isEqualTo("comer");
    }
}
