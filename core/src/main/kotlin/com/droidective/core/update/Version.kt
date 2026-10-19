package com.droidective.core.update

/**
 * A release version, `major.minor.patch` with an optional pre-release, ordered the way semver
 * orders them: `0.2.0-beta.1` comes before `0.2.0`, and `beta.2` before `beta.10`.
 */
data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch).takeIf {
            it != 0
        } ?: comparePreRelease(preRelease, other.preRelease)

    override fun toString(): String =
        "$major.$minor.$patch" +
            preRelease.joinToString(".").let { if (it.isEmpty()) "" else "-$it" }

    companion object {
        private val shape =
            Regex(
                """^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z.-]+)?$"""
            )

        /**
         * Reads `1.2.3`, a tag such as `v1.2.3`, and `1.2.3-beta.1`. Build metadata after `+` is
         * dropped, since semver gives it no order.
         *
         * @return the version, or null when [text] isn't one (or a part overflows an Int).
         */
        fun parse(text: String): Version? {
            val match = shape.matchEntire(text.trim()) ?: return null
            val (major, minor, patch) = match.groupValues.drop(1).take(3).map { it.toIntOrNull() }
            return Version(
                major ?: return null,
                minor ?: return null,
                patch ?: return null,
                match.groupValues[4].removePrefix("-").split('.').filter { it.isNotEmpty() },
            )
        }

        private fun comparePreRelease(a: List<String>, b: List<String>): Int {
            if (a.isEmpty() || b.isEmpty()) return b.size.compareTo(a.size).coerceIn(-1, 1)
            for ((x, y) in a.zip(b)) {
                val order = compareIdentifier(x, y)
                if (order != 0) return order
            }
            return a.size.compareTo(b.size)
        }

        /** Numbers compare as numbers and sort before words, which compare as ASCII. */
        private fun compareIdentifier(x: String, y: String): Int {
            val xNumber = x.all(Char::isDigit)
            val yNumber = y.all(Char::isDigit)
            return when {
                xNumber && yNumber ->
                    x.length.compareTo(y.length).takeIf { it != 0 } ?: x.compareTo(y)
                xNumber -> -1
                yNumber -> 1
                else -> x.compareTo(y)
            }
        }
    }
}
