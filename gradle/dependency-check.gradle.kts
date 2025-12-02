tasks.register("checkDependencyChanges") {
    doLast {
        val token = System.getenv("GITHUB_TOKEN")
        val prNumber = System.getenv("PR_NUMBER")
        val repoName = System.getenv("REPO_NAME")
        val baseBranch = System.getenv("BASE_BRANCH") ?: "develop"

        if (token == null || prNumber == null || repoName == null) {
            println("Missing required environment variables")
            return@doLast
        }

        // Only proceed if base branch is develop
        if (baseBranch != "develop") {
            println("Skipping check - PR is not targeting develop branch (current: $baseBranch)")
            return@doLast
        }

        // Dependencies to skip (ignore these changes)
        val skipDependencies = setOf(
            "gmal-kmm",
            "test.yml-dependency",
            // Add more dependencies to skip here
        )

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
        data class ChangedLine(val lineNumber: Int, val content: String)
        val changedLines = mutableListOf<ChangedLine>()
        var currentLine = 0

        diffOutput.lines().forEach { line ->
            when {
                line.startsWith("@@") -> {
                    val match = Regex("""@@ -\d+,?\d* \+(\d+)""").find(line)
                    currentLine = match?.groupValues?.get(1)?.toInt() ?: 0
                }
                line.startsWith("+") && !line.startsWith("+++") -> {
                    changedLines.add(ChangedLine(currentLine, line.substring(1).trim()))
                    currentLine++
                }
                !line.startsWith("-") -> {
                    currentLine++
                }
            }
        }

        // Filter out lines that contain skipped dependencies
        val filteredLines = changedLines.filter { change ->
            val shouldSkip = skipDependencies.any { dep ->
                change.content.contains(dep)
            }
            if (shouldSkip) {
                println("Skipping line ${change.lineNumber}: ${change.content}")
            }
            !shouldSkip
        }

        if (filteredLines.isEmpty()) {
            println("No relevant dependency changes found (all changes are in skip list)")
            return@doLast
        }

        // Post comments for filtered lines
        filteredLines.forEach { change ->
            println("Posting comment for line ${change.lineNumber}: ${change.content}")
            postComment(token, repoName, prNumber, change.lineNumber)
        }
    }
}

fun postComment(
    token: String,
    repoName: String,
    prNumber: String,
    lineNumber: Int
) {
    val commitSha = Runtime.getRuntime()
        .exec(arrayOf("git", "rev-parse", "HEAD"))
        .inputStream
        .bufferedReader()
        .readText()
        .trim()

    val confluenceLink =
        "https://your-company.atlassian.net/wiki/spaces/DEV/pages/123456789/Dependencies"

    // Create the comment body with proper line breaks
    val commentBody = """📚 **Documentation Update Required**

🔄 A dependency version has been changed on this line.

### Action Items:

1. ✏️ Update the new version in [Confluence]($confluenceLink)
2. ✅ Add a comment below confirming the documentation has been updated
3. ✔️ Resolve this thread

"""

    // Properly escape for JSON
    val escapedBody = commentBody
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    val body = """
        {
          "body": "$escapedBody",
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