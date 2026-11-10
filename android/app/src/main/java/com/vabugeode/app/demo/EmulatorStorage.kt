package com.vabugeode.app.demo

/** Device-side checkpoint, independent of the phone's downloaded track database. */
interface EmulatorStorage {
    fun load(): ByteArray?
    fun save(bytes: ByteArray): Boolean
}
