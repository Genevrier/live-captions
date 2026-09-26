package com.asr.live.pipeline.engine

import com.asr.live.pipeline.Profile

/** One source/reference pair plus what property of a translation it is meant to probe. */
data class CorpusItem(val source: String, val reference: String, val category: String)

/**
 * A small, versioned, per-direction source-text screening corpus.
 *
 * Honesty about provenance, per explicit instruction: these reference translations are
 * machine-authored drafts written for this project, **not** independently human-reviewed, not
 * sourced from a licensed parallel corpus, and not validated against a trusted external
 * implementation. They are adequate for Stage B relative screening between local candidates
 * (does engine A track a reasonable translation better than engine B on these items) and for
 * regression-testing the scorer's own wiring. They are **not** adequate as a quality
 * certification, and results derived from them must be reported as such — see
 * [CorpusQualification].
 *
 * Tuning vs. held-out split: items tagged with an even index are the tuning subset (used while
 * iterating on the selection policy itself); odd-index items are held out for the actual
 * candidate comparison, so the policy is not fit to the exact set it is scored against.
 */
object BenchmarkCorpus {
    const val VERSION = "v1-unvalidated"

    enum class CorpusQualification { PERFORMANCE_TESTED_QUALITY_NOT_QUALIFIED }
    val qualification = CorpusQualification.PERFORMANCE_TESTED_QUALITY_NOT_QUALIFIED

    private val dutchEnglish = listOf(
        CorpusItem("Goedemorgen, kun je het rapport voor de middag versturen?",
            "Good morning, can you send the report before the afternoon?", "conversation"),
        CorpusItem("Nee, dat is niet correct, ik bedoelde de tweede optie.",
            "No, that's not correct, I meant the second option.", "negation-correction"),
        CorpusItem("De vergadering begint om drie uur en duurt ongeveer negentig minuten.",
            "The meeting starts at three o'clock and lasts about ninety minutes.", "numbers-time"),
        CorpusItem("Kunt u het formulier vóór vrijdag 5 maart indienen?",
            "Could you submit the form before Friday, March 5th?", "dates"),
        CorpusItem("Mevrouw Van Dijk werkt bij de afdeling logistiek in Rotterdam.",
            "Mrs. Van Dijk works in the logistics department in Rotterdam.", "names"),
        CorpusItem("Ik denk dat we... eigenlijk, laat maar, het is niet belangrijk.",
            "I think that we... actually, never mind, it's not important.", "incomplete-prefix"),
        CorpusItem("Het gewicht van het pakket is drie kilo en de prijs is tien euro.",
            "The weight of the package is three kilos and the price is ten euros.", "units"),
        CorpusItem("Sorry, kun je dat nog een keer herhalen? Ik heb je niet goed verstaan.",
            "Sorry, could you repeat that once more? I didn't hear you clearly.", "short-reply"),
    )

    private val englishFrench = listOf(
        CorpusItem("Good morning, could you send the invoice before noon?",
            "Bonjour, pourriez-vous envoyer la facture avant midi ?", "conversation"),
        CorpusItem("No, that's not right, I meant the first version.",
            "Non, ce n'est pas ça, je voulais dire la première version.", "negation-correction"),
        CorpusItem("The train leaves at half past six and arrives in forty minutes.",
            "Le train part à six heures et demie et arrive en quarante minutes.", "numbers-time"),
        CorpusItem("Please submit the application before March 5th.",
            "Merci de soumettre la demande avant le 5 mars.", "dates"),
        CorpusItem("Mr. Dubois manages the logistics team in Lyon.",
            "M. Dubois dirige l'équipe logistique à Lyon.", "names"),
        CorpusItem("I think that we... actually, never mind, it's not important.",
            "Je pense que nous... en fait, laisse tomber, ce n'est pas important.", "incomplete-prefix"),
        CorpusItem("The package weighs two kilos and costs fifteen euros.",
            "Le colis pèse deux kilos et coûte quinze euros.", "units"),
        CorpusItem("Sorry, could you say that again? I didn't quite catch it.",
            "Pardon, pourriez-vous répéter ? Je n'ai pas bien entendu.", "short-reply"),
    )

    private val chineseEnglish = listOf(
        CorpusItem("早上好，你能在中午之前把报告发过来吗？",
            "Good morning, can you send the report before noon?", "conversation"),
        CorpusItem("不，这不对，我说的是第二个方案。",
            "No, that's not right, I meant the second option.", "negation-correction"),
        CorpusItem("会议三点开始，大概持续九十分钟。",
            "The meeting starts at three and lasts about ninety minutes.", "numbers-time"),
        CorpusItem("请在三月五日星期五之前提交表格。",
            "Please submit the form before Friday, March 5th.", "dates"),
        CorpusItem("王女士在上海负责物流部门。",
            "Ms. Wang is in charge of the logistics department in Shanghai.", "names"),
        CorpusItem("我觉得……其实，算了，不重要。",
            "I think... actually, never mind, it's not important.", "incomplete-prefix"),
        CorpusItem("包裹重三公斤，价格是十欧元。",
            "The package weighs three kilos and costs ten euros.", "units"),
        CorpusItem("抱歉，你能再说一次吗？我没听清楚。",
            "Sorry, could you say that again? I didn't hear clearly.", "short-reply"),
    )

    fun forProfile(profile: Profile): List<CorpusItem> = when (profile) {
        Profile.DUTCH_ENGLISH -> dutchEnglish
        Profile.ENGLISH_FRENCH -> englishFrench
        Profile.CHINESE_ENGLISH -> chineseEnglish
    }

    fun tuning(profile: Profile): List<CorpusItem> = forProfile(profile).filterIndexed { i, _ -> i % 2 == 0 }
    fun heldOut(profile: Profile): List<CorpusItem> = forProfile(profile).filterIndexed { i, _ -> i % 2 != 0 }
}
