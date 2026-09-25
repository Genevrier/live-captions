package com.asr.live.asr

internal object WordErrorScorer {
    private val NEGATIONS = setOf("niet", "geen", "nooit", "niets", "niemand", "nergens", "zonder")

    fun normalize(text: String): List<String> = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim().split(Regex("\\s+")).filter(String::isNotEmpty)

    fun count(reference: List<String>, hypothesis: List<String>): WordErrorCounts {
        val errors = Array(reference.size + 1) { IntArray(hypothesis.size + 1) }
        val operations = Array(reference.size + 1) { Array(hypothesis.size + 1) { ' ' } }
        for (i in 1..reference.size) { errors[i][0] = i; operations[i][0] = 'D' }
        for (j in 1..hypothesis.size) { errors[0][j] = j; operations[0][j] = 'I' }
        for (i in 1..reference.size) for (j in 1..hypothesis.size) {
            if (reference[i - 1] == hypothesis[j - 1]) {
                errors[i][j] = errors[i - 1][j - 1]; operations[i][j] = 'C'
            } else {
                val substitution = errors[i - 1][j - 1] + 1
                val deletion = errors[i - 1][j] + 1
                val insertion = errors[i][j - 1] + 1
                val minimum = minOf(substitution, deletion, insertion)
                errors[i][j] = minimum
                operations[i][j] = when (minimum) {
                    substitution -> 'S'
                    deletion -> 'D'
                    else -> 'I'
                }
            }
        }
        var i = reference.size
        var j = hypothesis.size
        var substitutions = 0
        var deletions = 0
        var insertions = 0
        while (i > 0 || j > 0) when {
            i > 0 && j > 0 && operations[i][j] == 'C' -> { i--; j-- }
            i > 0 && j > 0 && operations[i][j] == 'S' -> { substitutions++; i--; j-- }
            i > 0 && operations[i][j] == 'D' -> { deletions++; i-- }
            else -> { insertions++; j-- }
        }
        return WordErrorCounts(errors[reference.size][hypothesis.size], substitutions, deletions,
            insertions, reference.size)
    }

    fun countNegations(reference: List<String>, hypothesis: List<String>): WordErrorCounts = count(
        reference.filter { it in NEGATIONS }, hypothesis.filter { it in NEGATIONS })
}
