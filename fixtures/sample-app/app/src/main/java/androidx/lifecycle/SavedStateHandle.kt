package androidx.lifecycle

class SavedStateHandle(
    initialState: Map<String, Any?> = emptyMap(),
) {
    private val storage = initialState.toMutableMap()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = storage[key] as T?

    operator fun <T> set(key: String, value: T) {
        storage[key] = value
    }
}
