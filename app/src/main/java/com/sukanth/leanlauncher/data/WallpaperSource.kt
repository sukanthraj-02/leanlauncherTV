package com.sukanth.leanlauncher.data

/** Where the optional home-screen photo comes from. */
enum class WallpaperSource {
    /** Random landscape photos from Wallhaven's General category. */
    BUILTIN,

    /** Images chosen with Android's document picker and read in place. */
    LOCAL,

    /** Images discovered on a user-supplied web page or feed. */
    WEB,
    ;

    companion object {
        fun fromStored(value: String?): WallpaperSource =
            entries.firstOrNull { it.name == value } ?: BUILTIN
    }
}
