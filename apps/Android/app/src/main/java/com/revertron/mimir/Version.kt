package com.revertron.mimir

/**
 *  2     -> 2.0.0 stable
 *  2.0.0a35  -> 2.0.0 alpha 35
 *  2.5.1b14  -> 2.5.1 beta 14
 *  3.0.0n5   -> 3.0.0 nightly 5
 *
 *  Channel order: alpha < beta < nightly < stable
 */
data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val channel: Channel = Channel.STABLE,
    val build: Int = 0
) : Comparable<Version> {

    enum class Channel(val code: Int) { ALPHA(0), BETA(1), NIGHTLY(2), STABLE(3) }

    companion object {
        private val REGEX =
            Regex("""^(\d+)\.(\d+)\.(\d+)(?:([abn])(\d+))?$""", RegexOption.IGNORE_CASE)

        fun parse(raw: String): Version {
            val low = raw.lowercase()
            // if no channel letter, treat as stable
            if (!low.contains(Regex("[abn]"))) {
                val nums = low.split(".").map { it.toInt() }
                return Version(nums[0], nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
            }

            val m = REGEX.matchEntire(low)
                ?: throw IllegalArgumentException("Bad version: $raw")

            val (maj, min, pat, chan, build) = m.destructured
            val channel = when (chan) {
                "a" -> Channel.ALPHA
                "b" -> Channel.BETA
                "n" -> Channel.NIGHTLY
                else -> Channel.STABLE
            }
            return Version(
                maj.toInt(),
                min.toInt(),
                pat.toInt(),
                channel,
                build.toIntOrNull() ?: 0
            )
        }
    }

    /** true if both versions are on the same channel */
    fun sameChannel(other: Version) = channel == other.channel

    override fun compareTo(other: Version): Int {
        // 1. numeric part
        var cmp = major.compareTo(other.major)
        if (cmp != 0) return cmp
        cmp = minor.compareTo(other.minor)
        if (cmp != 0) return cmp
        cmp = patch.compareTo(other.patch)
        if (cmp != 0) return cmp

        // 2. channel
        cmp = channel.code.compareTo(other.channel.code)
        if (cmp != 0) return cmp

        // 3. build number inside the same channel
        return build.compareTo(other.build)
    }

    override fun toString() = buildString {
        append("$major.$minor.$patch")
        when (channel) {
            Channel.ALPHA   -> append('a')
            Channel.BETA    -> append('b')
            Channel.NIGHTLY -> append('n')
            Channel.STABLE  -> return@buildString
        }
        append(build)
    }
}