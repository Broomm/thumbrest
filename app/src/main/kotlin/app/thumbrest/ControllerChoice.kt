package app.thumbrest

// Enumeration numbers and display names cannot identify a controller across
// reconnects. Identical hardware without a unique identifier requires a choice.
internal fun automaticController(devices: List<PadDevice>, rememberedId: String?): PadDevice? =
    if (rememberedId == null) devices.singleOrNull()
    else devices.filter { it.stableId == rememberedId }.singleOrNull()
