package app.moviestudio.config

import io.github.cdimascio.dotenv.dotenv

/**
 * Central place to read configuration/secrets.
 *
 * Values are loaded from the `.env` file at the project root (via dotenv-kotlin) and merged
 * with real process environment variables (e.g. those set in CI/production), so no manual
 * `source .env` step is required for local development.
 */
object Env {
    private val dotenv = dotenv {
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }

    fun get(key: String): String? = System.getenv(key) ?: dotenv[key]

    fun get(key: String, default: String): String = get(key) ?: default
}
