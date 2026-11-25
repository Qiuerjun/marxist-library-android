package app.marxistlibrary.reader.download

private const val StorageReserve = 128L * 1024 * 1024
internal const val StorageCheckInterval = 1024L * 1024

internal fun fitsStorage(available: Long, incoming: Long) =
    available >= StorageReserve && incoming in 0..(available - StorageReserve)
