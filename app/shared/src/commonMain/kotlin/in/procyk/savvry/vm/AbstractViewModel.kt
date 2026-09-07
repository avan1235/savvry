package `in`.procyk.savvry.vm

import androidx.compose.ui.platform.Clipboard
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import `in`.procyk.savvry.*
import `in`.procyk.savvry.AppConfig.CLIENT_HOST
import `in`.procyk.savvry.AppConfig.CLIENT_HTTP_PROTOCOL
import `in`.procyk.savvry.AppConfig.CLIENT_PORT
import `in`.procyk.savvry.AppConfig.CLIENT_WS_PROTOCOL
import `in`.procyk.savvry.ui.components.snackbar.SnackbarHostState
import `in`.procyk.savvry.vm.AbstractViewModel.Context
import io.github.xxfast.kstore.KStore
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.rpc.annotations.Rpc
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import savvry.app.generated.resources.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid


internal abstract class AbstractViewModel(
    val context: Context,
) : ViewModel() {
    class Context(
        val navController: NavHostController,
        val snackbarHostState: SnackbarHostState,
        val store: KStore<SavvryStore>,
        val clipboard: Clipboard,
        private val appScope: CoroutineScope,
        val platformContext: PlatformContext,
    ) {
        val storeFlow: StateFlow<SavvryStore> =
            store.updates.filterNotNull()
                .stateIn(appScope, SharingStarted.Eagerly, SavvryStore.Default)

        val useBottomNavigation: StateFlow<Boolean> = storeFlow
            .map { it.useBottomNavigation }
            .stateIn(
                appScope,
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 1_000),
                storeFlow.value.useBottomNavigation,
            )

        val useLiquidGlassNavigation: StateFlow<Boolean> = storeFlow
            .map { it.useLiquidGlassNavigation }
            .stateIn(
                appScope,
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 1_000),
                storeFlow.value.useLiquidGlassNavigation,
            )

        private val _topBarText = MutableStateFlow(AppConfig.APP_NAME)
        val topBarText: StateFlow<String> = _topBarText.asStateFlow()

        val navBarTarget: StateFlow<NavBarTarget> = navController.currentBackStackEntryFlow.map { entry ->
            when {
                entry.navigatesFrom<Screen.EditList>() -> NavBarTarget.Main
                entry.navigatesFrom<Screen.CreateList>() -> NavBarTarget.Main
                entry.navigatesFrom<Screen.Settings>() -> NavBarTarget.Settings
                entry.navigatesFrom<Screen.Recipes>() -> NavBarTarget.Recipes
                entry.navigatesFrom<Screen.Recipe>() -> NavBarTarget.Recipes
                entry.navigatesFrom<Screen.LoyaltyCards>() -> NavBarTarget.LoyaltyCards
                entry.navigatesFrom<Screen.Favorites>() -> NavBarTarget.Favourites
                else -> error("Unknown screen target: $entry")
            }

        }.stateIn(
            appScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 1_000),
            NavBarTarget.Main,
        )

        fun onNavBarTargetSelected(target: NavBarTarget) {
            val current = navBarTarget.value
            when (target) {
                NavBarTarget.LoyaltyCards if target != current -> navigateCards()
                NavBarTarget.Favourites if target != current -> navigateFavourites()
                NavBarTarget.Settings if target != current -> navigateSettings()
                NavBarTarget.Recipes -> when {
                    navController.currentBackStackEntry.navigatesFrom<Screen.Recipe>() -> navigateRecipes()
                    target != current -> navigateRecipes()
                    else -> {}
                }

                NavBarTarget.Main -> when {
                    navController.currentBackStackEntry.navigatesFrom<Screen.EditList>() ->
                        navigateCreateList(cleanLastListId = true)

                    target != current -> navigateList()
                    else -> {}
                }

                else -> {}
            }
        }

        fun showSnackbar(resource: StringResource) {
            appScope.launch {
                snackbarHostState.showSnackbar(getString(resource))
            }
        }

        fun navigateList() {
            appScope.launch {
                when (val lastListId = storeFlow.value.lastListId) {
                    null -> navigateCreateList(cleanLastListId = false)
                    else -> navigateEditList(lastListId, fetchSuggestions = false)
                }
            }
        }

        fun navigateRecipes() {
            appScope.launch {
                updateListLocationPresentation(null)
                _topBarText.value = getString(Res.string.cooking_recipes)
                navigateReusingState(Screen.Recipes)
            }
        }

        fun navigateRecipe(recipeId: String) {
            appScope.launch {
                withContext(Dispatchers.Main) {
                    navController.navigate(Screen.Recipe(recipeId))
                }
            }
        }

        fun navigateCards() {
            appScope.launch {
                updateListLocationPresentation(null)
                _topBarText.value = getString(Res.string.loyalty_cards)
                navigateReusingState(Screen.LoyaltyCards)
            }
        }

        fun navigateFavourites() {
            appScope.launch {
                updateListLocationPresentation(null)
                _topBarText.value = getString(Res.string.favorites)
                navigateReusingState(Screen.Favorites)
            }
        }

        fun navigateSettings() {
            appScope.launch {
                updateListLocationPresentation(null)
                _topBarText.value = getString(Res.string.settings)
                navigateReusingState(Screen.Settings)
            }
        }

        fun navigateEditList(listId: String, fetchSuggestions: Boolean) {
            appScope.launch {
                updateListLocationPresentation(listId)
                store.update { it?.copy(lastListId = listId) }
                withContext(Dispatchers.Main) {
                    navController.navigate<Screen>(Screen.EditList(listId, fetchSuggestions))
                }
            }
        }

        fun navigateEditList(listId: Uuid, fetchSuggestions: Boolean) =
            navigateEditList(listId.toHexDashString(), fetchSuggestions)

        fun navigateCreateList(cleanLastListId: Boolean) {
            appScope.launch {
                updateListLocationPresentation(null)
                if (cleanLastListId) {
                    store.update { it?.copy(lastListId = null) }
                }
                navigateReusingState(Screen.CreateList)
                _topBarText.value = AppConfig.APP_NAME
            }
        }

        fun updateListName(name: String) {
            _topBarText.value = name
        }

        private suspend fun navigateReusingState(screen: Screen) {
            withContext(Dispatchers.Main) {
                navController.navigate(screen) {
                    launchSingleTop = true
                }
            }
        }
    }

    protected val httpClient = HttpClient {
        defaultRequest {
            url(scheme = CLIENT_HTTP_PROTOCOL, host = CLIENT_HOST, port = CLIENT_PORT)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    protected inline fun <@Rpc reified T : Any> durableRpcService(path: String): DurableRpcService<T> =
        DurableRpcService(viewModelScope, httpClient) {
            url(scheme = CLIENT_WS_PROTOCOL, host = CLIENT_HOST, port = CLIENT_PORT, path = path)
        }

    protected inline fun launchUpdateConfig(crossinline f: (SavvryStore) -> SavvryStore) {
        viewModelScope.launch { updateConfig(f) }
    }

    protected suspend inline fun updateConfig(crossinline f: (SavvryStore) -> SavvryStore) {
        context.store.update { it?.let(f) }
    }

    protected fun <T> Flow<T>.state(
        initialValue: T,
        started: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1_000),
    ): StateFlow<T> = stateIn(viewModelScope, started, initialValue)

    protected fun <T> storeState(
        started: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1_000),
        f: SavvryStore.() -> T,
    ): StateFlow<T> = storeFlow.map(f).state(store.f(), started)

    protected fun <T> Flow<T>.eagerState(
        initialValue: T,
    ): StateFlow<T> = state(initialValue, SharingStarted.Eagerly)

    protected val store: SavvryStore
        get() = storeFlow.value

    protected val storeFlow: StateFlow<SavvryStore> = context.storeFlow
}

internal inline fun <reified T : Screen> NavBackStackEntry?.navigatesFrom(): Boolean =
    this?.destination?.route?.split('/')?.getOrNull(0)?.split('.')?.lastOrNull() == T::class.simpleName

enum class NavBarTarget {
    Main, Recipes, LoyaltyCards, Favourites, Settings;
}

inline fun <T> onUpdatedItemOrder(
    fromKey: Uuid,
    toKey: Uuid,
    items: List<T>,
    crossinline updateOrder: (from: T, updatedOrder: Double) -> Unit,
) where T : Orderable, T : Identifiable {
    val (fromIndex, from) = items.withIndex().firstOrNull { (_, item) -> item.id == fromKey } ?: return
    val (toIndex, to) = items.withIndex().firstOrNull { (_, item) -> item.id == toKey } ?: return
    val relativeOrder = when {
        toIndex < fromIndex -> items.getOrNull(toIndex - 1)?.order ?: (to.order + 1.0)
        toIndex > fromIndex -> items.getOrNull(toIndex + 1)?.order ?: (to.order - 1.0)
        else -> return
    }
    val updatedOrder = (to.order + relativeOrder) / 2.0
    updateOrder(from, updatedOrder)
}

fun <T, U> Flow<U>.debounceSameIds(delay: Duration): Flow<U> where T : Identifiable, U : Iterable<T> {
    var lastIds = setOf<Uuid>()
    return debounce {
        val ids = it.mapTo(HashSet()) { it.id }
        val sameIds = lastIds == ids
        lastIds = ids
        if (sameIds) delay else 0.seconds
    }
}

internal expect fun updateListLocationPresentation(listId: String?)

internal expect suspend fun onShareList(listId: String, context: Context)

internal expect suspend fun onShareLoyaltyCard(cardId: String, context: Context)

internal expect suspend fun onShareRecipe(recipeId: String, context: Context)
