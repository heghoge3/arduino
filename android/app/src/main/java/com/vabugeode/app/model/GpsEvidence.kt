package com.vabugeode.app.model

/** Та же классификация, что в firmware/lib/core/spoof: ожидание не равно обнаружению. */
object GpsEvidence {
    fun recoveryOnly(mask: Int): Boolean {
        val strong = (1 shl 0) or (1 shl 2)
        val weak = (1 shl 3) or (1 shl 4) or (1 shl 5)
        return mask and (1 shl 7) != 0 && mask and strong == 0 &&
            Integer.bitCount(mask and weak) < 2
    }
}
