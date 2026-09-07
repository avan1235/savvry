package `in`.procyk.savvry.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import `in`.procyk.savvry.Identifiable
import `in`.procyk.savvry.Orderable
import `in`.procyk.savvry.ui.components.*
import `in`.procyk.savvry.ui.components.card.ElevatedCard
import `in`.procyk.savvry.ui.components.liquid.LiquidBottomTabsSpacer
import `in`.procyk.savvry.ui.components.progressindicators.CircularProgressIndicator
import `in`.procyk.savvry.ui.components.textfield.OutlinedTextField
import `in`.procyk.savvry.vm.ImportableCollectionViewModel
import `in`.procyk.savvry.vm.ImportableCollectionViewModel.InputDialogAction.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import savvry.app.generated.resources.*
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
internal fun <V, I> ImportableCollectionScreen(
    testTag: String,
    padding: PaddingValues,
    vm: V,
    addDialogTitle: StringResource,
    importDialogTitle: StringResource,
    scanButtonTestTag: String? = null,
    overlay: @Composable () -> Unit = {},
    itemContent: @Composable (item: I) -> Unit,
) where V : ImportableCollectionViewModel<*, *, I, *>,
        I : Orderable,
        I : Identifiable = AppScreen(testTag, padding) {
    val dialogAction by vm.dialogAction.collectAsState()
    val items by vm.items.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val enableEditMode by vm.enableEditMode.collectAsState()
    val sortByColors by vm.sortByColors.collectAsState()
    val allowReorderItems by remember { derivedStateOf { enableEditMode && !sortByColors } }
    val listState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        vm.onUpdatedItemOrder(from.key as Uuid, to.key as Uuid)
    }

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            vm.updateStoredItemsInBackground()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            state = listState,
        ) {
            if (enableEditMode || (!isLoading && items?.isEmpty() == true)) item(key = "$testTag-actions") {
                Box(
                    modifier = Modifier
                        .padding(
                            start = 16.dp,
                            bottom = if (items?.isNotEmpty() == true) 8.dp else 0.dp,
                        )
                        .fillMaxWidth()
                        .animateItem(),
                ) {
                    ActionButton(
                        icon = Icons.Outlined.IosShare,
                        variant = IconButtonVariant.PrimaryOutlined,
                        onClick = vm::shareAll,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    val disableScanButtonReason by vm.disableScanButtonReason.collectAsState()
                    ActionButton(
                        icon = Icons.Outlined.QrCodeScanner,
                        text = stringResource(Res.string.scan),
                        variant = IconButtonVariant.Primary,
                        onClick = {
                            when (val disableScanButtonReason = disableScanButtonReason) {
                                null -> vm.openAddFromFileDialog()
                                else -> vm.context.showSnackbar(disableScanButtonReason)
                            }
                        },
                        testTag = scanButtonTestTag,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    ActionButton(
                        icon = Icons.Outlined.FileUpload,
                        variant = IconButtonVariant.PrimaryOutlined,
                        onClick = vm::openImportByIdDialog,
                        iconModifier = Modifier.rotate(180f),
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            items?.let { items ->
                items(items, key = { it.id }) { item ->
                    ReorderableItemRow(
                        state = reorderableLazyListState,
                        key = item.id,
                        enabled = allowReorderItems,
                        modifier = Modifier
                            .padding(start = if (allowReorderItems) 4.dp else 16.dp)
                            .fillParentMaxWidth()
                            .animateItem(),
                    ) {
                        if (allowReorderItems) Spacer(Modifier.width(4.dp))
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            itemContent(item)
                        }
                    }
                }

            }
            if (isLoading) item(key = "$testTag-loading-indicator") {
                Row(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .fillParentMaxWidth()
                        .animateItem(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }
            item(key = "$testTag-liquid-spacer") {
                LiquidBottomTabsSpacer(vm)
            }
        }
        Column(
            modifier = Modifier.padding(FabPadding).fillMaxSize(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Bottom,
        ) {
            AnimatedVisibility(
                visible = enableEditMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ActionButton(
                    icon = Icons.Default.Check,
                    variant = IconButtonVariant.PrimaryElevated,
                    onClick = vm::disableEditMode,
                )
            }
            LiquidBottomTabsSpacer(vm)
        }
    }
    dialogAction?.let { currentAction ->
        AlertDialogComponent(
            onDismissRequest = { vm.run { closeDialogAction(); resetUserInput() } },
            confirmButton = {
                if (currentAction is Loading) return@AlertDialogComponent
                Button(
                    variant = ButtonVariant.PrimaryOutlined,
                    text = when (currentAction) {
                        AddFromFile -> stringResource(Res.string.select_file)
                        ImportById -> stringResource(Res.string.import_action)
                    },
                    onClick = when (currentAction) {
                        ImportById -> vm::importByIds
                        AddFromFile -> vm::addFromFile
                    },
                )
            },
            dismissButton = composeIfNotNull(currentAction.takeIf { it !is Loading }) {
                Button(
                    variant = ButtonVariant.Ghost,
                    text = stringResource(Res.string.cancel),
                    onClick = { vm.run { closeDialogAction(); resetUserInput() } },
                )
            },
            icon = when (currentAction) {
                is AddFromFile -> {
                    { Icon(Icons.Outlined.QrCodeScanner) }
                }

                is ImportById -> {
                    { Icon(Icons.Outlined.EditNote) }
                }

                is Loading -> null
            },
            title = {
                if (currentAction is Loading) return@AlertDialogComponent
                Text(
                    text = when (currentAction) {
                        AddFromFile -> stringResource(addDialogTitle)
                        ImportById -> stringResource(importDialogTitle)
                    },
                )
            },
            text = {
                if (currentAction is Loading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                        Text(currentAction.description)
                    }
                } else {
                    val userInput by vm.userInput.collectAsState()
                    val isErrorUserInput by vm.isErrorUserInput.collectAsState()
                    OutlinedTextField(
                        value = userInput,
                        onValueChange = vm::onUserInputChange,
                        leadingIcon = {
                            Icon(
                                imageVector = when (currentAction) {
                                    AddFromFile -> Icons.Outlined.NewLabel
                                    ImportById -> Icons.Outlined.EditNote
                                },
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        },
                        singleLine = true,
                        isError = isErrorUserInput,
                    )
                }
            },
        )
    }
    overlay()
}

private val FabPadding = 16.dp

@Composable
private fun ActionButton(
    icon: ImageVector,
    variant: IconButtonVariant,
    onClick: () -> Unit,
    enabled: Boolean = true,
    text: String? = null,
    testTag: String? = null,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    IconButton(
        content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(icon, modifier = iconModifier)
                if (text != null) Text(text)
            }
        },
        variant = variant,
        onClick = onClick,
        modifier = if (testTag != null) modifier.testTag(testTag) else modifier,
        enabled = enabled,
    )
}

private inline fun <T : Any> composeIfNotNull(
    value: T?,
    crossinline f: @Composable () -> Unit,
): @Composable () -> Unit =
    if (value == null) {
        {}
    } else {
        { f() }
    }
