package app.marxistlibrary.reader

import app.marxistlibrary.reader.update.GitHubReleases
import app.marxistlibrary.reader.update.ProjectReleases
import app.marxistlibrary.reader.update.ReleaseApk
import app.marxistlibrary.reader.update.ReleaseVersion
import app.marxistlibrary.reader.update.UpdateProblem
import app.marxistlibrary.reader.update.parseRelease
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubReleasesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun comparesStableReleaseVersionsNumerically() {
        assertTrue(requireNotNull(ReleaseVersion.parse("v1.10.0")) > requireNotNull(ReleaseVersion.parse("1.9.9")))
        assertEquals(ReleaseVersion.parse("1.1.5"), ReleaseVersion.parse("v1.1.5"))
        assertNull(ReleaseVersion.parse("v1.1.5-beta.1"))
        assertNull(ReleaseVersion.parse("v1.999999999999.0"))
    }

    @Test
    fun prefersTheVersionedApkAndBuildsLinksForTheProject() {
        val hash = "a".repeat(64)
        val release = requireNotNull(parseRelease("""
            {
              "tag_name":"v1.1.6","html_url":"https://untrusted.example/",
              "assets":[
                {"name":"other.apk","state":"uploaded","size":20},
                {"name":"MarxistLibrary-1.1.6.apk","state":"uploaded","size":30,"digest":"sha256:$hash",
                 "browser_download_url":"https://untrusted.example/update.apk"}
              ]
            }
        """.trimIndent()))
        assertEquals("$ProjectReleases/tag/v1.1.6", release.page)
        val apk = requireNotNull(release.apk)
        assertEquals("$ProjectReleases/download/v1.1.6/MarxistLibrary-1.1.6.apk", apk.url.toString())
        assertEquals(30L, apk.size)
        assertEquals(hash, apk.sha256)
    }

    @Test
    fun recognizesOneApkAndLeavesAmbiguousAssetsToGitHub() {
        val one = requireNotNull(parseRelease("""
            {"tag_name":"1.1.6","assets":[{"name":"reader.apk","state":"uploaded","size":20},
               {"name":"bundle.aab","state":"uploaded","size":50}]}
        """.trimIndent()))
        assertEquals("$ProjectReleases/download/1.1.6/reader.apk", one.apk?.url.toString())
        val mixed = requireNotNull(parseRelease("""
            {"tag_name":"1.1.6","assets":[{"name":"reader.apk","state":"uploaded","size":20},
               {"name":"debug.apk","state":"uploaded","size":25}]}
        """.trimIndent()))
        assertNull(mixed.apk)
        assertNull(parseRelease("""{"tag_name":"v1.1.7-beta","prerelease":true}"""))
    }

    @Test
    fun emptyRepositoryHasNoRelease() = runBlocking {
        val requests = mutableListOf<Request>()
        val github = github { request ->
            requests.add(request)
            response(request, code = 404)
        }
        assertNull(github.latest())
        val url = requests.single().url
        assertEquals("api.github.com", url.host)
        assertTrue(url.encodedPath.endsWith("/releases/latest"))
    }

    @Test
    fun publishesTheApkOnlyAfterCheckingItsDigestAndPackage() = runBlocking {
        val data = "hello".toByteArray()
        val digest = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        val directory = temp.newFolder()
        val github = github { request -> response(request, data) }
        val asset = ReleaseApk("$ProjectReleases/download/v1.1.6/reader.apk".toHttpUrl(), 5, digest)
        val progress = mutableListOf<Long>()
        var verifiedBytes = ByteArray(0)
        var publishedBeforeVerification = true
        val apk = github.download(asset, directory, received = { progress.add(it) }) {
            verifiedBytes = it.readBytes()
            publishedBeforeVerification = directory.resolve("update.apk").exists()
        }
        assertFalse(publishedBeforeVerification)
        assertArrayEquals(data, verifiedBytes)
        assertEquals("update.apk", apk.name)
        assertArrayEquals(data, apk.readBytes())
        assertEquals(listOf(5L), progress)
        assertEquals(listOf("update.apk"), directory.list()?.toList())

        var checkedMismatchedApk = false
        val mismatch = runCatching {
            github.download(asset.copy(sha256 = "0".repeat(64)), directory, received = {}) {
                checkedMismatchedApk = true
            }
        }.exceptionOrNull()
        assertTrue(mismatch is UpdateProblem)
        assertFalse(checkedMismatchedApk)
        assertArrayEquals(data, apk.readBytes())
        assertEquals(listOf("update.apk"), directory.list()?.toList())
    }

    @Test
    fun rejectsRedirectsOutsideGitHubBeforeSendingAnotherRequest() = runBlocking {
        val hosts = mutableListOf<String>()
        val github = github { request ->
            hosts.add(request.url.host)
            response(request, code = 302, location = "https://untrusted.example/app.apk")
        }
        val failure = runCatching {
            github.download(
                ReleaseApk("$ProjectReleases/download/v1.1.6/reader.apk".toHttpUrl(), 5, null),
                temp.newFolder(),
                received = {},
                verifyApk = {}
            )
        }.exceptionOrNull()
        assertTrue(failure is UpdateProblem)
        assertEquals(listOf("github.com"), hosts)
    }

    private fun github(handle: (Request) -> Response) =
        GitHubReleases(OkHttpClient.Builder().addInterceptor { handle(it.request()) }.build())

    private fun response(
        request: Request,
        data: ByteArray = ByteArray(0),
        code: Int = 200,
        location: String? = null
    ): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test response")
        .apply { location?.let { header("Location", it) } }
        .body(data.toResponseBody()).build()
}
