package dev.soloton.roadwork

import dev.soloton.roadwork.env.riverfront.Riverfront

/** Every environment the app has. Adding one is a new package plus one line in [all]. */
object Environments {
    /** In the order a picker would show them. The first is the default. */
    val all: List<Environment> = listOf(
        Riverfront.environment,
    )

    val default: Environment get() = all.first()

    /** The environment with this id, or null if there is none. */
    fun find(id: String?): Environment? = all.firstOrNull { it.id == id }

    /** The environment with this id, or [default] if the id is null or unknown (for example a saved id of a removed environment). */
    fun byId(id: String?): Environment = find(id) ?: default
}
