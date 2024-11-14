/*
 * Copyright (C) 2024 Cash App
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.cash.zipline.gradle

import app.cash.zipline.ZiplineManifest
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.io.File
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import org.gradle.deployment.internal.Deployment
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZiplineDevelopmentServerTest {

  @JvmField
  @Rule
  var temporaryFolder = TemporaryFolder()

  private val helloZipline = "Hello World".encodeUtf8()

  private val manifest = ZiplineManifest.create(
    modules = mapOf(
      "hello" to ZiplineManifest.Module(
        url = "hello.zipline",
        sha256 = helloZipline.sha256(),
        dependsOnIds = listOf(),
      ),
    ),
    mainFunction = "zipline.ziplineMain",
    baseUrl = "http://localhost:16630/manifest.zipline.json",
  )

  private val manifestUrl = "http://localhost:16630/manifest.zipline.json".toHttpUrl()

  private lateinit var server: ZiplineDevelopmentServer

  @Before
  fun setUp() {
    server = ZiplineDevelopmentServer(
      inputDirectory = temporaryFolder.root,
      port = 16630,
    )
    server.start(FakeDeployment)

    File(temporaryFolder.root, "manifest.zipline.json").writeText(manifest.encodeJson())
    File(temporaryFolder.root, "hello.zipline").writeBytes(helloZipline.toByteArray())
  }

  @After
  fun tearDown() {
    server.stop()
  }

  @Test
  fun happyPath() {
    val httpClient = OkHttpClient()
    val downloadedManifest = httpClient.call(manifestUrl).use { response ->
      ZiplineManifest.decodeJson(response.body!!.string())
    }

    val module = downloadedManifest.modules.values.single()
    httpClient.call(manifestUrl.resolve(module.url)!!).use { response ->
      val moduleData = response.body!!.byteString()
      assertThat(moduleData).isEqualTo(helloZipline)
    }
  }

  /**
   * We had a bug where the dev server's manifests were incorrectly cached by NSURLSession. We don't
   * have NSURLSession here, but we can confirm that responses aren't cached by OkHttp's cache.
   *
   * https://github.com/cashapp/zipline/issues/1461
   */
  @Test
  fun manifestIsNotCached() {
    val httpClient = OkHttpClient.Builder()
      .cache(Cache(File(temporaryFolder.root, "cache"), 1024 * 1024))
      .build()

    val downloadedManifest1 = httpClient.call(manifestUrl).use { response ->
      assertThat(response.networkResponse!!.headers["Cache-Control"]).isEqualTo("no-cache")
      ZiplineManifest.decodeJson(response.body!!.string())
    }
    assertThat(downloadedManifest1.version).isNull()

    val manifest2 = manifest.copy(version = "2")
    File(temporaryFolder.root, "manifest.zipline.json").writeText(manifest2.encodeJson())
    val downloadedManifest2 = httpClient.call(manifestUrl).use { response ->
      // OkHttp should add a cache validation header, but the server should return a 200 because
      // the resource has changed. (And because it doesn't implement conditional caching.)
      assertThat(response.networkResponse!!.request.headers.names()).contains("If-Modified-Since")
      assertThat(response.networkResponse!!.code).isEqualTo(200)
      ZiplineManifest.decodeJson(response.body!!.string())
    }
    assertThat(downloadedManifest2.version).isEqualTo("2")
  }

  private fun OkHttpClient.call(url: HttpUrl): Response {
    val call = newCall(
      Request.Builder()
        .url(url)
        .build(),
    )
    return call.execute()
  }

  object FakeDeployment : Deployment {
    override fun status(): Deployment.Status {
      error("unexpected call")
    }
  }
}
