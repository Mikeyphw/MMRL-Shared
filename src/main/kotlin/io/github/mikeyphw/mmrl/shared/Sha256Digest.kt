package io.github.mikeyphw.mmrl.shared

import java.io.InputStream
import java.security.MessageDigest

/** Lowercase, exactly 256-bit SHA-256 digest; no algorithm agility or unsigned coercion. */
class Sha256Digest private constructor(val hex: String) {
    val prefixed: String get() = "sha256:$hex"

    fun verifies(bytes: ByteArray): Boolean = matches(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun verifies(input: InputStream): Boolean {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) sha.update(buffer, 0, count)
        }
        return matches(sha.digest())
    }

    private fun matches(actual: ByteArray): Boolean {
        val expected = ByteArray(32) { i ->
            val high = hex[i * 2].digitToInt(16)
            val low = hex[i * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
        return MessageDigest.isEqual(expected, actual)
    }

    override fun equals(other: Any?): Boolean = other is Sha256Digest && other.hex == hex
    override fun hashCode(): Int = hex.hashCode()
    override fun toString(): String = prefixed

    companion object {
        private val hexPattern = Regex("[0-9a-f]{64}")

        fun parse(value: String): Sha256Digest? {
            val normalized = if (value.startsWith("sha256:")) value.removePrefix("sha256:") else value
            return if (hexPattern.matches(normalized)) Sha256Digest(normalized) else null
        }

        fun calculate(bytes: ByteArray): Sha256Digest = Sha256Digest(
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        )
    }
}
