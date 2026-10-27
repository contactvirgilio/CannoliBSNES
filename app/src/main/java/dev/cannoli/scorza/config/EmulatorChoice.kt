package dev.cannoli.scorza.config

data class EmulatorChoice(
    val source: EmulatorSource,
    val coreId: String = "",
    val appPackage: String? = null,
)

// Bundled cores that a release stopped shipping, mapped to the core that replaced them. A stored
// Internal choice naming a retired core would point at a .so that no longer exists, so it is
// read as its replacement instead. RetroArch choices are left alone: RetroArch may still have
// the old core installed.
internal val RETIRED_INTERNAL_CORES = mapOf(
    "snes9x_libretro" to "bsnes_libretro",
)

internal fun EmulatorChoice.withRetiredCoreReplaced(): EmulatorChoice {
    if (source != EmulatorSource.Internal) return this
    val replacement = RETIRED_INTERNAL_CORES[coreId] ?: return this
    return copy(coreId = replacement)
}
