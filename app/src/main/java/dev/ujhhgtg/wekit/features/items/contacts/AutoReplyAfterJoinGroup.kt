package dev.ujhhgtg.wekit.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.ChatroomSyncStateReadResult
import dev.ujhhgtg.wekit.features.api.core.models.WeChatroomSyncState
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookCallback
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookDirectly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.Locale

object AutoReplyAfterJoinGroup : ClickableFeature(), IResolveDex {

    override val technicalId = "加入群聊自动回复"
    override val nameRes = R.string.feature_auto_reply_after_join_group_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_auto_reply_after_join_group_description

    override val defaultEnabled: Boolean = true

    private const val TAG = "AutoReplyAfterJoinGroup"
    private const val MAX_SNAPSHOTS = 128
    private const val MAX_DEDUP_KEYS = 256

    private const val KEY_REPLY_COUNT = "auto_reply_after_join_group_count"
    private const val KEY_REPLY_ITEM_PREFIX = "auto_reply_after_join_group_item_"
    private const val KEY_SEND_INTERVAL_MS = "auto_reply_after_join_group_interval_ms"

    private const val DEFAULT_REPLY_COUNT = 1
    private const val MAX_REPLY_COUNT = 20
    private const val DEFAULT_INTERVAL_MS = 100L
    private const val MAX_INTERVAL_MS = 60_000L
    private const val MAX_MESSAGE_LENGTH = 4_000

    private val methodSyncChatroomMembers by dexMethod()
    private val stateLock = Any()
    private val snapshots = IdentityHashMap<HookParam, SyncSnapshot>()
    private val dedupKeys = LinkedHashMap<String, Unit>(MAX_DEDUP_KEYS, 0.75f, true)
    private var observedSelfWxId: String? = null
    private var scope = newScope()

    private data class SyncSnapshot(
        val roomId: String,
        val oldState: ChatroomSyncStateReadResult,
        val selfWxId: String,
    )

    override fun resolveDex(dexKit: DexKitBridge) {
        val matches = dexKit.findMethod {
            matcher {
                returnType = "boolean"
                paramCount(10, 11)
                usingStrings("MicroMsg.ChatroomMembersLogic", "SyncAddChatroomMember")
            }
        }.filter { method ->
            val params = method.paramTypeNames
            params[0] == "java.lang.String" &&
                params[1] == "java.lang.String" &&
                params[3] == "int" &&
                params[4] == "int" &&
                params[5] == "int" &&
                params[6] == "java.lang.String" &&
                params[8] == "boolean" &&
                params[9] == "boolean" &&
                (params.size == 10 || params[10] == "int") &&
                params[2] !in PRIMITIVE_TYPE_NAMES &&
                params[7] !in PRIMITIVE_TYPE_NAMES
        }

        check(matches.size == 1) {
            "expected one ChatroomMembersLogic sync method, found ${matches.size}: " +
                matches.joinToString { it.descriptor }
        }
        methodSyncChatroomMembers.setDescriptor(matches.single())
    }

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()

        registerUnhook(methodSyncChatroomMembers.method.hookDirectly(object : HookCallback() {
            override fun beforeHookedMethod(param: HookParam) {
                val roomId = param.args[0] as String
                if (!roomId.isSupportedChatroomId()) return

                val selfWxId = WeApi.selfWxId
                if (selfWxId.isEmpty()) return

                val snapshot = SyncSnapshot(roomId, WeDatabaseApi.getChatroomSyncState(roomId), selfWxId)
                synchronized(stateLock) {
                    observeAccount(selfWxId)
                    if (snapshots.size >= MAX_SNAPSHOTS) snapshots.entries.iterator().run {
                        next()
                        remove()
                    }
                    snapshots[param] = snapshot
                }
            }

            override fun afterHookedMethod(param: HookParam) {
                val selfWxId = WeApi.selfWxId
                val snapshot = synchronized(stateLock) {
                    val pendingSnapshot = snapshots.remove(param) ?: return
                    if (selfWxId != pendingSnapshot.selfWxId) {
                        observeAccount(selfWxId)
                        return
                    }
                    observeAccount(selfWxId)
                    pendingSnapshot
                }
                if (param.throwable != null) return

                val oldState = when (snapshot.oldState) {
                    ChatroomSyncStateReadResult.MissingRow -> null
                    is ChatroomSyncStateReadResult.Available -> snapshot.oldState.state
                    ChatroomSyncStateReadResult.Unavailable -> {
                        WeLogger.d(TAG, "skip unavailable pre-sync state for ${snapshot.roomId}")
                        return
                    }
                }
                val newState = when (val result = WeDatabaseApi.getChatroomSyncState(snapshot.roomId)) {
                    is ChatroomSyncStateReadResult.Available -> result.state
                    ChatroomSyncStateReadResult.MissingRow -> {
                        WeLogger.d(TAG, "skip missing post-sync state for ${snapshot.roomId}")
                        return
                    }
                    ChatroomSyncStateReadResult.Unavailable -> {
                        WeLogger.d(TAG, "skip unavailable post-sync state for ${snapshot.roomId}")
                        return
                    }
                }

                if (!shouldMuteJoinedGroup(oldState, newState, snapshot.selfWxId)) return

                submitReplies(snapshot.roomId, newState, snapshot.selfWxId)
            }
        }))
    }

    override fun onDisable() {
        scope.cancel()
        synchronized(stateLock) {
            snapshots.clear()
            dedupKeys.clear()
            observedSelfWxId = null
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val savedCount = WePrefs.getIntOrDef(KEY_REPLY_COUNT, DEFAULT_REPLY_COUNT)
                .coerceIn(1, MAX_REPLY_COUNT)

            var countDraft by remember { mutableStateOf(savedCount.toString()) }

            var intervalInput by remember {
                mutableStateOf(
                    WePrefs.getLongOrDef(KEY_SEND_INTERVAL_MS, DEFAULT_INTERVAL_MS).toString()
                )
            }

            val items = remember {
                List(savedCount) { index ->
                    WePrefs.getStringOrDef(
                        "$KEY_REPLY_ITEM_PREFIX$index",
                        if (index == 0) "大家好，我是新来的，请多关照～" else ""
                    )
                }.toMutableStateList()
            }

            // 根据草稿条数应用到 items：增加或删除输入框
            fun applyCount() {
                val target = countDraft.toIntOrNull()?.coerceIn(1, MAX_REPLY_COUNT) ?: return
                countDraft = target.toString()
                while (items.size < target) items.add("")
                while (items.size > target) items.removeAt(items.size - 1)
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_reply_after_join_group_name)) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 条数：输入框 + 确定按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            TextField(
                                value = countDraft,
                                onValueChange = { input ->
                                    countDraft = input.filter { it.isDigit() }
                                },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(stringResource(R.string.auto_reply_after_join_group_count_label))
                                }
                            )
                            Button(
                                onClick = { applyCount() },
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(stringResource(R.string.dialog_confirm))
                            }
                        }

                        // 每条内容输入框（数量由 countDraft 决定）
                        items.forEachIndexed { index, value ->
                            key(index) {
                                TextField(
                                    value = value,
                                    onValueChange = { items[index] = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.auto_reply_after_join_group_item_label,
                                                index + 1
                                            )
                                        )
                                    }
                                )
                            }
                        }

                        TextField(
                            value = intervalInput,
                            onValueChange = { intervalInput = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(stringResource(R.string.auto_reply_after_join_group_interval_label))
                            }
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(onClick = {
                        // 保存前兜底应用一次条数
                        applyCount()

                        val intervalMs = intervalInput.toLongOrNull()
                            ?.coerceIn(0L, MAX_INTERVAL_MS) ?: return@Button

                        val finalCount = items.size.coerceIn(1, MAX_REPLY_COUNT)

                        WePrefs.putInt(KEY_REPLY_COUNT, finalCount)
                        WePrefs.putLong(KEY_SEND_INTERVAL_MS, intervalMs)

                        items.forEachIndexed { index, text ->
                            WePrefs.putString(
                                "$KEY_REPLY_ITEM_PREFIX$index",
                                text.trim().take(MAX_MESSAGE_LENGTH)
                            )
                        }
                        // 清理多余旧 key
                        for (i in finalCount until MAX_REPLY_COUNT) {
                            WePrefs.remove("$KEY_REPLY_ITEM_PREFIX$i")
                        }

                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }

    private fun submitReplies(roomId: String, state: WeChatroomSyncState, selfWxId: String) {
        val key = dedupKey(state)
        if (!markDedupKey(key)) {
            WeLogger.d(TAG, "skip duplicate reply room=$roomId key=$key version=${state.memberVersion}")
            return
        }

        val count = WePrefs.getIntOrDef(KEY_REPLY_COUNT, DEFAULT_REPLY_COUNT)
            .coerceIn(1, MAX_REPLY_COUNT)
        val intervalMs = WePrefs.getLongOrDef(KEY_SEND_INTERVAL_MS, DEFAULT_INTERVAL_MS)
            .coerceIn(0L, MAX_INTERVAL_MS)

        val messages = (0 until count).mapNotNull { index ->
            WePrefs.getStringOrDef("$KEY_REPLY_ITEM_PREFIX$index", "")
                .trim()
                .takeIf { it.isNotEmpty() }
        }

        if (messages.isEmpty()) {
            WeLogger.w(TAG, "no valid reply messages configured, skip room=$roomId")
            return
        }

        scope.launch {
            try {
                if (WeApi.selfWxId != selfWxId) {
                    WeLogger.d(TAG, "skip stale reply room=$roomId key=$key")
                    return@launch
                }
                messages.forEachIndexed { index, msg ->
                    val sent = WeMessageApi.sendText(roomId, msg)
                    WeLogger.i(
                        TAG,
                        "sent reply[$index/${messages.size - 1}] to room=$roomId " +
                            "key=$key ok=$sent content=${msg.take(30)}"
                    )
                    // 第一条失败即中断
                    if (!sent && index == 0) {
                        WeLogger.w(TAG, "first reply failed, abort remaining for room=$roomId key=$key")
                        return@launch
                    }
                    if (index < messages.size - 1 && intervalMs > 0L) {
                        delay(intervalMs)
                    }
                }
                WeLogger.i(TAG, "all replies sent to room=$roomId key=$key count=${messages.size}")
            } catch (e: Exception) {
                WeLogger.w(TAG, "reply submission failed room=$roomId key=$key", e)
            }
        }
    }

    private fun observeAccount(selfWxId: String) {
        if (observedSelfWxId != null && observedSelfWxId != selfWxId) {
            snapshots.clear()
            dedupKeys.clear()
        }
        observedSelfWxId = selfWxId
    }

    private fun markDedupKey(key: String): Boolean = synchronized(stateLock) {
        if (dedupKeys.containsKey(key)) return@synchronized false
        if (dedupKeys.size >= MAX_DEDUP_KEYS) dedupKeys.entries.iterator().run {
            next()
            remove()
        }
        dedupKeys[key] = Unit
        true
    }

    private fun String.isSupportedChatroomId(): Boolean {
        val lowerCaseId = lowercase(Locale.ROOT)
        return lowerCaseId.endsWith("@chatroom") || lowerCaseId.endsWith("@im.chatroom")
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val PRIMITIVE_TYPE_NAMES = setOf(
        "boolean",
        "byte",
        "char",
        "double",
        "float",
        "int",
        "long",
        "short",
        "void",
        "java.lang.String",
    )
}