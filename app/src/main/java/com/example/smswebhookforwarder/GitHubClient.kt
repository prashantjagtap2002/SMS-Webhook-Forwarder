package com.example.smswebhookforwarder

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubClient(
    private val owner: String,
    private val repo: String,
    private val workflowFile: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Release(val tagName: String, val name: String, val htmlUrl: String, val publishedAt: String)
    data class WorkflowRun(
        val id: Long,
        val name: String,
        val status: String,          // queued | in_progress | completed
        val conclusion: String?,     // success | failure | cancelled | null
        val htmlUrl: String,
        val displayTitle: String,
        val createdAt: String,
        val headBranch: String
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val code: Int, val message: String) : Result<Nothing>()
    }

    fun getLatestRelease(): Result<Release?> {
        val req = baseRequest("https://api.github.com/repos/$owner/$repo/releases/latest", null).build()
        return execute(req) { body ->
            if (body.isBlank()) return@execute null
            val obj = JsonParser.parseString(body).asJsonObject
            Release(
                tagName = obj.getAsStringOrEmpty("tag_name"),
                name = obj.getAsStringOrEmpty("name"),
                htmlUrl = obj.getAsStringOrEmpty("html_url"),
                publishedAt = obj.getAsStringOrEmpty("published_at")
            )
        }
    }

    fun getLatestRun(pat: String?): Result<WorkflowRun?> {
        val req = baseRequest(
            "https://api.github.com/repos/$owner/$repo/actions/runs?per_page=1",
            pat
        ).build()
        return execute(req) { body ->
            val obj = JsonParser.parseString(body).asJsonObject
            val runs = obj.getAsJsonArray("workflow_runs") ?: return@execute null
            if (runs.size() == 0) return@execute null
            val run = runs[0].asJsonObject
            WorkflowRun(
                id = run.get("id").asLong,
                name = run.getAsStringOrEmpty("name"),
                status = run.getAsStringOrEmpty("status"),
                conclusion = if (run.get("conclusion")?.isJsonNull == false) run.get("conclusion").asString else null,
                htmlUrl = run.getAsStringOrEmpty("html_url"),
                displayTitle = run.getAsStringOrEmpty("display_title"),
                createdAt = run.getAsStringOrEmpty("created_at"),
                headBranch = run.getAsStringOrEmpty("head_branch")
            )
        }
    }

    fun triggerWorkflow(pat: String, ref: String = "main"): Result<Unit> {
        val payload = """{"ref":"$ref"}""".toRequestBody("application/json".toMediaType())
        val req = baseRequest(
            "https://api.github.com/repos/$owner/$repo/actions/workflows/$workflowFile/dispatches",
            pat
        ).post(payload).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.Ok(Unit)
                else Result.Err(resp.code, parseErrorMessage(resp.body?.string().orEmpty()) ?: "HTTP ${resp.code}")
            }
        } catch (e: IOException) {
            Result.Err(-1, e.message ?: "Network error")
        }
    }

    private fun <T> execute(request: Request, transform: (String) -> T): Result<T> {
        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Result.Err(resp.code, parseErrorMessage(body) ?: "HTTP ${resp.code}")
                }
                Result.Ok(transform(body))
            }
        } catch (e: IOException) {
            Result.Err(-1, e.message ?: "Network error")
        } catch (e: Exception) {
            Result.Err(-2, e.message ?: "Parse error")
        }
    }

    private fun baseRequest(url: String, pat: String?): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (!pat.isNullOrBlank()) b.header("Authorization", "Bearer $pat")
        return b
    }

    private fun parseErrorMessage(body: String): String? = try {
        JsonParser.parseString(body).asJsonObject.getAsStringOrEmpty("message").ifBlank { null }
    } catch (_: Exception) { null }

    private fun JsonObject.getAsStringOrEmpty(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""
}
