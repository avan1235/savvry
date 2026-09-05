@file:OptIn(ExperimentalAtomicApi::class)

package `in`.procyk.savvry.vm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewModelScope
import arrow.core.Either
import `in`.procyk.savvry.Identifiable
import `in`.procyk.savvry.Orderable
import `in`.procyk.savvry.SavvryStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import savvry.app.generated.resources.Res
import savvry.app.generated.resources.error_internal
import savvry.app.generated.resources.error_removing_item
import savvry.app.generated.resources.loading_importing
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.Uuid

internal abstract class ImportableCollectionViewModel<TStored, TData, TItem, TInputContext>(
    context: Context,
) : AbstractViewModel(context) where TStored : Orderable, TStored : Identifiable, TItem : Orderable, TItem : Identifiable {

    sealed class InputDialogAction {
        data object AddFromFile : InputDialogAction()
        data object ImportById : InputDialogAction()
        data class Loading(val description: String) : InputDialogAction()
    }

    protected abstract fun SavvryStore.storedItems(): List<TStored>

    protected abstract fun SavvryStore.withStoredItems(items: List<TStored>): SavvryStore

    protected abstract fun cachedData(stored: TStored): TData?

    protected abstract fun withCachedData(stored: TStored, data: TData?): TStored

    protected abstract fun color(stored: TStored): Int?

    protected abstract fun withColor(stored: TStored, color: Int?): TStored

    protected abstract suspend fun fetchData(stored: TStored): Either<TData, FetchError>

    protected abstract fun buildItem(stored: TStored, data: TData): TItem

    protected open fun postProcessItems(items: List<TItem>): List<TItem> = items

    protected abstract val useCacheStored: SavvryStore.() -> Boolean

    protected abstract val showLabelsStored: SavvryStore.() -> Boolean

    protected abstract val sortByColorStored: SavvryStore.() -> Boolean

    protected open val idSeparator: String get() = ";"

    protected abstract fun newStored(id: Uuid, order: Double): TStored

    protected abstract suspend fun createFromFile(input: TInputContext): List<Uuid>

    protected abstract suspend fun removeRemote(item: TItem): Either<Unit, RemoveError>

    protected abstract fun getInputContext(): TInputContext

    protected abstract fun isValidContext(input: TInputContext): Boolean

    protected abstract suspend fun share(ids: String)

    enum class FetchError { Internal, UnknownId }
    enum class RemoveError { Internal, UnknownId }

    private val _dialogAction: MutableStateFlow<InputDialogAction?> = MutableStateFlow(null)
    val dialogAction: StateFlow<InputDialogAction?> = _dialogAction.asStateFlow()

    private val _items: MutableStateFlow<List<TItem>?> = MutableStateFlow(null)
    val items: StateFlow<List<TItem>?> = _items.asStateFlow()

    private val _userInput: MutableStateFlow<String> = MutableStateFlow("")
    val userInput: StateFlow<String> = _userInput.asStateFlow()

    private val _isErrorUserInput: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isErrorUserInput: StateFlow<Boolean> = _isErrorUserInput.asStateFlow()

    private val _isLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _enableEditMode = MutableStateFlow(false)
    val enableEditMode: StateFlow<Boolean> = _enableEditMode.asStateFlow()

    val showLabels: StateFlow<Boolean> by lazy {
        storeFlow.map { it.showLabelsStored() }.state(store.showLabelsStored())
    }

    val sortByColors: StateFlow<Boolean> by lazy {
        storeFlow.map { it.sortByColorStored() }.state(store.sortByColorStored())
    }

    abstract val disableScanButtonReason: StateFlow<StringResource?>

    private val launchedUpdateStoredItemsInBackground = AtomicBoolean(false)

    fun updateStoredItemsInBackground() {
        if (!launchedUpdateStoredItemsInBackground.compareAndSet(expectedValue = false, newValue = true)) return

        viewModelScope.launch {
            val inMemoryCache = mutableMapOf<Uuid, TItem>()
            context.storeFlow.map { it.storedItems() }.distinctUntilIdsChanged().collectLatest { stored ->
                _isLoading.value = true
                val useCache = store.useCacheStored()
                val sortByColor = store.sortByColorStored()
                val resultsMutex = Mutex()
                val resolvedById = mutableMapOf<Uuid, TItem>()

                suspend fun publish() {
                    val resolved = resultsMutex.withLock { stored.mapNotNull { resolvedById[it.id] } }
                    val sorted = postProcessItems(resolved).let { items ->
                        if (sortByColor) {
                            val colorMap = stored.associate { it.id to color(it)?.let(::Color)?.toHsv() }
                            items.sortedWith(compareBy(HcvColorComparator, { colorMap[it.id] }))
                        } else {
                            items.sorted()
                        }
                    }
                    _items.value = sorted
                }

                try {
                    coroutineScope {
                        stored.map { entry ->
                            async {
                                val key = entry.id
                                val item = inMemoryCache[key] ?: run {
                                    val cached = if (useCache) cachedData(entry) else null
                                    val data = cached ?: fetchData(entry).fold(
                                        ifLeft = { it },
                                        ifRight = { err ->
                                            when (err) {
                                                FetchError.Internal -> {
                                                    context.showSnackbar(Res.string.error_internal)
                                                }

                                                FetchError.UnknownId -> launchUpdateConfig { st ->
                                                    st.withStoredItems(st.storedItems().filter { it.id != entry.id })
                                                }
                                            }
                                            return@run null
                                        },
                                    )
                                    launchUpdateConfig { st ->
                                        st.withStoredItems(
                                            st.storedItems().map {
                                                if (it.id == key) withCachedData(it, if (useCache) data else null) else it
                                            },
                                        )
                                    }
                                    buildItem(entry, data)
                                }?.also { inMemoryCache[key] = it }
                                if (item != null) {
                                    resultsMutex.withLock { resolvedById[key] = item }
                                    publish()
                                }
                            }
                        }.awaitAll()
                    }
                    resetUserInput()
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun addFromFile() {
        val inputContext = validateUserInputContext(::isValidContext) ?: return
        viewModelScope.launch {
            val ids = createFromFile(inputContext)
            updateConfigWithStoredIds(ids)
        }.invokeOnCompletion { closeDialogAction() }
    }

    fun importByIds() {
        viewModelScope.launch {
            updateDialogActionLoading(getString(Res.string.loading_importing))
            val ids = userInput.value.split(idSeparator)
                .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            updateConfigWithStoredIds(ids)
        }.invokeOnCompletion { closeDialogAction() }
    }

    fun removeItem(item: TItem) {
        launchUpdateConfig { it.withStoredItems(it.storedItems().filter { stored -> stored.id != item.id }) }
        viewModelScope.launch {
            removeRemote(item).onRight { context.showSnackbar(Res.string.error_removing_item) }
        }
    }

    fun shareItem(item: TItem) {
        viewModelScope.launch { share(item.id.toHexDashString()) }
    }

    fun shareAll() {
        viewModelScope.launch {
            val ids = items.value.orEmpty().joinToString(separator = idSeparator) { it.id.toHexDashString() }
            share(ids)
        }
    }

    fun onUpdatedItemOrder(fromKey: Uuid, toKey: Uuid) {
        onUpdatedItemOrder(fromKey, toKey, items.value.orEmpty()) { from, updatedOrder ->
            _items.update { existing ->
                existing?.map { if (it.id == from.id) replaceOrder(it, updatedOrder) else it }?.sorted()
            }
            launchUpdateConfig { st ->
                st.withStoredItems(
                    st.storedItems().map {
                        if (it.id == from.id) replaceStoredOrder(it, updatedOrder) else it
                    },
                )
            }
        }
    }

    protected abstract fun replaceOrder(item: TItem, order: Double): TItem

    protected abstract fun replaceStoredOrder(stored: TStored, order: Double): TStored

    fun itemColor(item: TItem): StateFlow<Color> =
        storeFlow
            .map { st -> st.storedItems().find { it.id == item.id }?.let(::color)?.let(::Color) ?: Color.Unspecified }
            .state(Color.Unspecified)

    fun onItemColorUpdated(itemId: Uuid, color: Color?) {
        launchUpdateConfig { st ->
            st.withStoredItems(
                st.storedItems().map {
                    if (it.id == itemId) withColor(it, color?.toArgb()) else it
                },
            )
        }
    }

    fun onUserInputChange(value: String) {
        _userInput.update { value }
        _isErrorUserInput.update { false }
    }

    fun resetUserInput() {
        onUserInputChange("")
    }

    open fun openAddFromFileDialog() {
        _dialogAction.update { InputDialogAction.AddFromFile }
    }

    fun openImportByIdDialog() {
        _dialogAction.update { InputDialogAction.ImportById }
    }

    fun updateDialogActionLoading(description: String) {
        _dialogAction.update { InputDialogAction.Loading(description) }
    }

    fun closeDialogAction() {
        _dialogAction.update { null }
    }

    fun enableEditMode() {
        _enableEditMode.update { true }
    }

    fun disableEditMode() {
        _enableEditMode.update { false }
    }

    private fun validateUserInputContext(isValid: (TInputContext) -> Boolean): TInputContext? {
        val input = getInputContext()
        if (!isValid(input)) {
            _isErrorUserInput.update { true }
            return null
        }
        return input
    }

    private fun updateConfigWithStoredIds(ids: List<Uuid>) {
        launchUpdateConfig { config ->
            val existing = config.storedItems()
            val maxOrder = existing.maxOfOrNull { it.order } ?: 0.0
            val existingIds = existing.map { it.id }
            config.withStoredItems(
                existing + ids
                    .filter { it !in existingIds }
                    .mapIndexed { idx, id -> newStored(id, maxOrder + 1.0 + idx) },
            )
        }
    }
}

private typealias HsvColor = FloatArray

private object HcvColorComparator : Comparator<HsvColor?> {

    override fun compare(a: HsvColor?, b: HsvColor?): Int = when {
        a.contentEquals(b) -> 0
        a == null -> 1
        b == null -> -1
        else -> {
            for (i in 0..2) {
                val cmp = a[i].compareTo(b[i])
                if (cmp != 0) return cmp
            }
            0
        }
    }
}

private fun Color.toHsv(): HsvColor {
    val cmax = maxOf(red, green, blue)
    val cmin = minOf(red, green, blue)
    val diff = cmax - cmin

    val h = when {
        diff == 0f -> 0.0f
        cmax == red -> (60 * ((green - blue) / diff) + 360f) % 360f
        cmax == green -> (60 * ((blue - red) / diff) + 120f) % 360f
        else -> (60 * ((red - green) / diff) + 240f) % 360f // if (cmax == blue)
    }
    val s = if (cmax == 0f) 0f else diff / cmax
    return floatArrayOf(h, s, cmax)
}
