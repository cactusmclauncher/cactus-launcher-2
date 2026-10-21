package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModVersionsTest {
    private static void yes(ModVersions.Range range, String... versions) {
        for (String version : versions) assertTrue(range.test(version), range + " should match " + version);
    }

    private static void no(ModVersions.Range range, String... versions) {
        for (String version : versions) assertFalse(range.test(version), range + " should not match " + version);
    }

    @Test
    void fabricComparisonOperators() {
        yes(ModVersions.fabric(">=0.92.0"), "0.92.0", "0.92.1+1.20.1", "1.0.0");
        no(ModVersions.fabric(">=0.92.0"), "0.91.9", "0.92.0-beta.1");
        yes(ModVersions.fabric("<1.21"), "1.20.6", "1.21-pre.1");
        no(ModVersions.fabric("<1.21"), "1.21", "1.21.0", "1.21.1");
        yes(ModVersions.fabric(">1.0"), "1.0.1");
        no(ModVersions.fabric(">1.0"), "1.0.0");
        yes(ModVersions.fabric("<=1.2"), "1.2.0", "1.1");
        yes(ModVersions.fabric("=1.20.1"), "1.20.1", "1.20.1+build.5");
        no(ModVersions.fabric("1.20.1"), "1.20.2", "1.20");
    }

    @Test
    void fabricSpaceSeparatedTermsAreAnd() {
        var range = ModVersions.fabric(">=1.20.2 <1.21");
        yes(range, "1.20.2", "1.20.6");
        no(range, "1.20.1", "1.21");
        assertEquals("≥ 1.20.2, < 1.21", range.display());
        // Some mods put a space after the operator.
        yes(ModVersions.fabric(">= 0.15.0"), "0.16.5");
    }

    @Test
    void fabricArraysAreOr() {
        var range = ModVersions.fabric(List.of("1.20.1", "1.20.2"));
        yes(range, "1.20.1", "1.20.2");
        no(range, "1.20.3");
        assertEquals("1.20.1 или 1.20.2", range.display());
        yes(ModVersions.fabric("1.19.x || 1.20.x"), "1.19.4", "1.20.1");
    }

    @Test
    void fabricTildeCaretAndWildcards() {
        yes(ModVersions.fabric("~1.2.3"), "1.2.3", "1.2.9");
        no(ModVersions.fabric("~1.2.3"), "1.2.2", "1.3.0");
        yes(ModVersions.fabric("^0.5.3"), "0.5.3", "0.9.0");
        no(ModVersions.fabric("^0.5.3"), "1.0.0", "0.5.2");
        yes(ModVersions.fabric("1.20.x"), "1.20", "1.20.6", "1.20.1-rc.1");
        no(ModVersions.fabric("1.20.x"), "1.21", "1.19.4");
        yes(ModVersions.fabric("1.*"), "1.0", "1.21.1");
        no(ModVersions.fabric("1.*"), "2.0");
        yes(ModVersions.fabric(">=1.20.x"), "1.20.0", "1.21");
        assertEquals("1.20.x", ModVersions.fabric("1.20.x").display());
        assertTrue(ModVersions.fabric("*").any());
        assertTrue(ModVersions.fabric("x").any());
        yes(ModVersions.fabric(""), "anything");
    }

    @Test
    void fabricPrereleaseOrdering() {
        yes(ModVersions.fabric(">=1.0.0-beta.2"), "1.0.0-beta.10", "1.0.0-rc.1", "1.0.0");
        no(ModVersions.fabric(">=1.0.0-beta.2"), "1.0.0-beta.1", "1.0.0-alpha");
        // Numeric identifiers sort before alphanumeric ones.
        yes(ModVersions.fabric(">1.0.0-1"), "1.0.0-alpha");
    }

    @Test
    void fabricNonSemanticVersionsOnlyMatchEquality() {
        yes(ModVersions.fabric("${version}"), "${version}");
        no(ModVersions.fabric(">=1.0"), "unknown");
        yes(ModVersions.fabric("*"), "unknown");
    }

    @Test
    void mavenRanges() {
        var forge = ModVersions.maven("[47,)");
        yes(forge, "47", "47.2.0", "48.0.1");
        no(forge, "46.0.14");
        assertEquals("≥ 47", forge.display());
        var minecraft = ModVersions.maven("[1.20.1,1.21)");
        yes(minecraft, "1.20.1", "1.20.4");
        no(minecraft, "1.20", "1.21", "1.21.1");
        assertEquals("≥ 1.20.1, < 1.21", minecraft.display());
        yes(ModVersions.maven("[1.20.1]"), "1.20.1", "1.20.1.0");
        no(ModVersions.maven("[1.20.1]"), "1.20.2");
        assertEquals("1.20.1", ModVersions.maven("[1.20.1]").display());
        yes(ModVersions.maven("(,1.0]"), "0.9", "1.0");
        no(ModVersions.maven("(,1.0)"), "1.0");
        no(ModVersions.maven("(1.0,2.0)"), "1.0", "2.0");
    }

    @Test
    void mavenUnionsAndSoftRequirements() {
        var union = ModVersions.maven("[1.0,2.0),[3.0,)");
        yes(union, "1.5", "3.1");
        no(union, "2.5");
        assertEquals("≥ 1.0, < 2.0 или ≥ 3.0", union.display());
        // A bare version is Maven's soft requirement and matches any version, as in Forge.
        yes(ModVersions.maven("1.0"), "0.1", "9.9");
        yes(ModVersions.maven("*"), "1");
        yes(ModVersions.maven(""), "1");
        yes(ModVersions.maven("[broken"), "1");
    }

    @Test
    void mavenOrdering() {
        assertTrue(ModVersions.compareMaven("1.0-alpha1", "1.0-beta1") < 0);
        assertTrue(ModVersions.compareMaven("1.0-beta", "1.0-rc1") < 0);
        assertTrue(ModVersions.compareMaven("1.0-rc1", "1.0-SNAPSHOT") < 0);
        assertTrue(ModVersions.compareMaven("1.0-SNAPSHOT", "1.0") < 0);
        assertEquals(0, ModVersions.compareMaven("1.0", "1"));
        assertEquals(0, ModVersions.compareMaven("1.0.0", "1-final"));
        assertTrue(ModVersions.compareMaven("1.0", "1.0-sp1") < 0);
        assertTrue(ModVersions.compareMaven("1.9", "1.10") < 0);
        assertTrue(ModVersions.compareMaven("47.1.0", "47.2") < 0);
        assertTrue(ModVersions.compareMaven("1.0-b2", "1.0-beta3") < 0);
    }
}
