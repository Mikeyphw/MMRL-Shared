package io.github.mikeyphw.mmrl.shared

/** Strict ASCII GitHub repository identity; does not imply a trusted publisher. */
@ConsistentCopyVisibility
data class GitHubRepository private constructor(val owner: String, val name: String) {
    val fullName: String get() = "$owner/$name"

    companion object {
        private val part = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")

        fun parse(fullName: String): GitHubRepository? {
            val fields = fullName.split('/')
            if (fields.size != 2 || fields.any { !part.matches(it) || it.contains("..") }) return null
            return GitHubRepository(fields[0], fields[1])
        }
    }
}

/** A URL-bound identity check only. Never equate this with a verified APK signer. */
class ReleaseAssetIdentity private constructor(
    val repository: GitHubRepository,
    val tag: String,
    val name: String,
) {
    val releasePage: String get() = "https://github.com/${repository.fullName}/releases/tag/$tag"
    val assetUrl: String get() = "https://github.com/${repository.fullName}/releases/download/$tag/$name"

    fun matchesAssetUrl(candidate: String): Boolean = candidate == assetUrl
    fun matchesReleasePage(candidate: String): Boolean = candidate == releasePage

    companion object {
        private val tagPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
        private val filePattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\\.(apk|zip)", RegexOption.IGNORE_CASE)

        fun of(repository: GitHubRepository, tag: String, name: String): ReleaseAssetIdentity? {
            if (!tagPattern.matches(tag) || tag.contains("..") ||
                !filePattern.matches(name) || name.contains("..")) return null
            return ReleaseAssetIdentity(repository, tag, name)
        }
    }
}
