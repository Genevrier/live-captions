package com.asr.live.pipeline.engine

import com.asr.live.pipeline.Profile
import org.junit.Assert.*
import org.junit.Test

class BenchmarkCorpusTest {
    @Test fun everyConfiguredProfileHasANonEmptyCorpus() {
        Profile.entries.forEach { profile ->
            assertTrue("${profile.label} corpus must not be empty", BenchmarkCorpus.forProfile(profile).isNotEmpty())
        }
    }

    @Test fun tuningAndHeldOutSubsetsArePartitionedWithNoOverlap() {
        Profile.entries.forEach { profile ->
            val tuning = BenchmarkCorpus.tuning(profile)
            val heldOut = BenchmarkCorpus.heldOut(profile)
            assertTrue("$profile must have both a tuning and a held-out subset", tuning.isNotEmpty() && heldOut.isNotEmpty())
            assertTrue("no item may appear in both subsets, or the policy would be tuned on its own test set",
                tuning.toSet().intersect(heldOut.toSet()).isEmpty())
            assertEquals(BenchmarkCorpus.forProfile(profile).size, tuning.size + heldOut.size)
        }
    }

    @Test fun corpusVersionIsExplicitlyMarkedUnvalidated() {
        assertTrue("scores from this corpus must not be presented as a quality certification",
            BenchmarkCorpus.VERSION.contains("unvalidated"))
        assertEquals(BenchmarkCorpus.CorpusQualification.PERFORMANCE_TESTED_QUALITY_NOT_QUALIFIED,
            BenchmarkCorpus.qualification)
    }

    @Test fun everyItemHasANonBlankSourceAndReference() {
        Profile.entries.forEach { profile ->
            BenchmarkCorpus.forProfile(profile).forEach {
                assertTrue(it.source.isNotBlank())
                assertTrue(it.reference.isNotBlank())
                assertTrue(it.category.isNotBlank())
            }
        }
    }

    @Test fun coversTheRequestedCategoriesForEachDirection() {
        val expected = setOf("conversation", "negation-correction", "numbers-time", "dates", "names",
            "incomplete-prefix", "units", "short-reply")
        Profile.entries.forEach { profile ->
            val categories = BenchmarkCorpus.forProfile(profile).map { it.category }.toSet()
            assertEquals("${profile.label} must cover every requested category", expected, categories)
        }
    }
}
