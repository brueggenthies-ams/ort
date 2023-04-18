/*
 * Copyright (C) 2019 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.utils.ort.storage

import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.use
import org.apache.logging.log4j.kotlin.Logging
import org.ossreviewtoolkit.utils.ort.OkHttpClientHelper
import org.ossreviewtoolkit.utils.ort.execute
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit

private const val HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS = 30L
private const val HTTP_CLIENT_KEEP_ALIVE_DURATION_IN_SECONDS = 1 * 60L
private const val HTTP_CLIENT_MAX_IDLE_CONNECTIONS = 5

/**
 * A [FileStorage] that stores files on an HTTP server.
 */
class HttpFileStorage(
    /**
     * The URL to store files at.
     */
    val url: String,

    /**
     * The query string that is appended to the combination of the URL and some additional path. Some storages process
     * authentication via parameters that are within the final URL, so certain credentials can be stored in this
     * query, e.g, "?user=standard&pwd=123". Thus, the final URL could be
     * "https://example.com/storage/path?user=standard&pwd=123".
     */
    val query: String = "",

    /**
     * Custom headers that are added to all HTTP requests.
     */
    private val headers: Map<String, String> = emptyMap(),

    /**
     * The max age of an HTTP cache entry in seconds. Defaults to 0 which always validates the cached response with the
     * remote server.
     */
    private val cacheMaxAgeInSeconds: Int = 0
) : FileStorage {
    private companion object : Logging

    private val httpClient by lazy {
        OkHttpClientHelper.buildClient {
            val connectionPool = ConnectionPool(
                HTTP_CLIENT_MAX_IDLE_CONNECTIONS,
                HTTP_CLIENT_KEEP_ALIVE_DURATION_IN_SECONDS,
                TimeUnit.SECONDS
            )

            connectionPool(connectionPool)
            connectTimeout(Duration.ofSeconds(HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS))
        }
    }

    override fun exists(path: String): Boolean {
        val request = Request.Builder()
            .headers(headers.toHeaders())
            .cacheControl(CacheControl.Builder().maxAge(cacheMaxAgeInSeconds, TimeUnit.SECONDS).build())
            .head()
            .url(urlForPath(path))
            .build()

        return httpClient.execute(request).isSuccessful
    }

    override fun read(path: String): InputStream {
        val httpPath = path.encodeForHttp()

        val request = Request.Builder()
            .headers(headers.toHeaders())
            .cacheControl(CacheControl.Builder().maxAge(cacheMaxAgeInSeconds, TimeUnit.SECONDS).build())
            .get()
            .url(urlForPath(httpPath))
            .build()

        logger.debug { "Reading file from storage: ${request.url}" }

        val response = httpClient.execute(request)
        if (response.isSuccessful) {
            response.body?.let { body ->
                return body.byteStream()
            }

            response.close()
            throw IOException("The response body must not be null.")
        }

        response.close()
        throw IOException("Could not read from '${request.url}': ${response.code} - ${response.message}")
    }

    override fun write(path: String, inputStream: InputStream) {
        val httpPath = path.encodeForHttp()
        val data: ByteArray = inputStream.use { it.readBytes() }

        val request = Request.Builder()
            .headers(headers.toHeaders())
            .put(data.toRequestBody())
            .url(urlForPath(httpPath))
            .build()

        logger.debug { "Writing file to storage: ${request.url}" }

        httpClient.execute(request).use { response ->
            if (!response.isSuccessful) {
                // Check if Error is 404. Then create directories and retry it
                if (response.code == 404) {
                    logger.info { "Writing failed with error 404. Retrying after MKCOL" }
                    writeWithWebDav(httpPath, data)
                } else {
                    throwWriteError(request, response)
                }
            }
        }
    }

    private fun writeWithWebDav(path: String, data: ByteArray) {
        createCollections(path)
        val request = Request.Builder()
            .headers(headers.toHeaders())
            .put(data.toRequestBody())
            .url(urlForPath(path))
            .build()
        httpClient.execute(request).use { response ->
            if (!response.isSuccessful) {
                throwWriteError(request, response)
            }
        }
    }

    private fun createCollections(path: String) {
        var collectionPath = ""
        // MKCOL does not work for nested collections at once
        path.split("/").dropLast(1).forEach {
            collectionPath = "$collectionPath$it/"
            logger.debug { "Calling MKCOL for ${urlForPath(collectionPath)}" }
            val request = Request.Builder()
                .headers(headers.toHeaders())
                .method("MKCOL", null)
                .url(urlForPath(collectionPath))
                .build()
            httpClient.execute(request).use {
                logger.debug { "MKCOL result: ${it.code}" }
            }
        }
    }

    private fun throwWriteError(request: Request, response: Response): Nothing {
        throw IOException("Could not store file at '${request.url}': ${response.code} - ${response.message}")
    }

    private fun String.encodeForHttp() = replace("%2F", "%252F") // HTTP spec does not support encoded slashes

    private fun urlForPath(path: String) = "$url/$path$query"
}
