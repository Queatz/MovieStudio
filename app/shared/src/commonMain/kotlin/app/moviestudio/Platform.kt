package app.moviestudio

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform