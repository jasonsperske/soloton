// Shared pure-Kotlin interfaces between apps and libraries. No Android or libGDX imports.
// What a game reads from the bike (PaceSource, ResistanceSource) is not here: it is in
// libs/bike-core, with the rest of the bike library.
// Owned by the coordinator: change only by agreement, since several modules implement or consume it.
plugins {
    id("soloton.kotlin.jvm")
}
