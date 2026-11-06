import io.github.mikeyphw.mmrl.shared.*

fun main() {
    val repo = GitHubRepository.parse("Mikeyphw/MMRL")!!
    check(GitHubRepository.parse("user/../bad") == null)
    val asset = ReleaseAssetIdentity.of(repo, "v1.0", "build.apk")!!
    check(asset.matchesAssetUrl("https://github.com/Mikeyphw/MMRL/releases/download/v1.0/build.apk"))
    check(!asset.matchesAssetUrl("http://github.com/Mikeyphw/MMRL/releases/download/v1.0/build.apk"))
    val digest = Sha256Digest.calculate("abc".encodeToByteArray())
    check(digest.hex == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    check(digest.verifies("abc".encodeToByteArray()))
    check(!digest.verifies("abcd".encodeToByteArray()))
    check(StableModuleOrder.compare("A", "a", "b", "b") < 0)
    val run = GitHubRunIdentity.of(repo, 4, 1, "a".repeat(40), "push", "next")!!
    check(run.matchesCurrentRun(4, 1, "a".repeat(40), "push", "next", "completed", "success"))
    check(!run.matchesCurrentRun(4, 2, "a".repeat(40), "push", "next", "completed", "success"))
    println("N05A_PURE_KOTLIN_SMOKE_PASS")
}
