package app.moviestudio.database

/**
 * Single source of truth for ArangoDB collection names. Every repository and the database
 * initializer should reference these entries instead of hardcoding collection name strings,
 * so the full set of collections is always defined in exactly one place.
 */
enum class DbCollection(val collectionName: String) {
    MOVIES("movies"),
    ASSETS("assets"),
    TRACKS("tracks"),
    CLIPS("clips"),
    JOBS("jobs"),
    CHARACTERS("characters"),
    SCENES("scenes"),
    VOICE_CLONES("voiceclones"),
    VOICE_DESIGNS("voicedesigns"),
    RENDERS("renders"),
    NOTES("notes"),
    DOCUMENTS("documents"),
    TIPS("tips");

    override fun toString(): String = collectionName
}
