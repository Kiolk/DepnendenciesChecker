// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// build.gradle.kts
tasks.register("checkDependencyChanges") {
    doLast {
        val token = System.getenv("GITHUB_TOKEN")
        val prNumber = System.getenv("PR_NUMBER")
        val repoName = System.getenv("REPO_NAME")

        if (token == null || prNumber == null || repoName == null) {
            println("Missing required environment variables")
            return@doLast
        }

        // Get the diff for libs.versions.toml
        val diffOutput = Runtime.getRuntime()
            .exec(arrayOf("git", "diff", "origin/develop", "HEAD", "--", "gradle/libs.versions.toml"))
            .inputStream
            .bufferedReader()
            .readText()

        if (diffOutput.isEmpty()) {
            println("No changes in libs.versions.toml")
            return@doLast
        }

        // Parse the diff to find changed lines
        val changedLines = mutableListOf<Int>()
        var currentLine = 0

        diffOutput.lines().forEach { line ->
            when {
                line.startsWith("@@") -> {
                    // Extract the line number from diff header
                    val match = Regex("""@@ -\d+,?\d* \+(\d+)""").find(line)
                    currentLine = match?.groupValues?.get(1)?.toInt() ?: 0
                }
                line.startsWith("+") && !line.startsWith("+++") -> {
                    changedLines.add(currentLine)
                    currentLine++
                }
                !line.startsWith("-") -> {
                    currentLine++
                }
            }
        }

        // Post comments to the PR for each changed line
        changedLines.forEach { lineNumber ->
            postComment(token, repoName, prNumber, lineNumber)
        }
    }
}

fun postComment(token: String, repoName: String, prNumber: String, lineNumber: Int) {
    val commitSha = Runtime.getRuntime()
        .exec(arrayOf("git", "rev-parse", "HEAD"))
        .inputStream
        .bufferedReader()
        .readText()
        .trim()

    val body = """
        {
          "body": "Please update documentation for dependency",
          "commit_id": "$commitSha",
          "path": "gradle/libs.versions.toml",
          "line": $lineNumber,
          "side": "RIGHT"
        }
    """.trimIndent()

    val command = arrayOf(
        "curl",
        "-X", "POST",
        "-H", "Authorization: token $token",
        "-H", "Accept: application/vnd.github.v3+json",
        "-H", "Content-Type: application/json",
        "https://api.github.com/repos/$repoName/pulls/$prNumber/comments",
        "-d", body
    )

    val result = Runtime.getRuntime()
        .exec(command)
        .inputStream
        .bufferedReader()
        .readText()

    println("Posted comment for line $lineNumber: $result")
}