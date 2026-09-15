package dev.ujhhgtg.wekit.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

    // 默认开启，进群时立即生效，和"自动免打扰"行为一致；用户可在设置页关闭
    override val defaultEnabled: Boolean = true

    private const val TAG = "AutoReplyAfterJoinGroup"
    private const val MAX_SNAPSHOTS = 128
    private const val MAX_DEDUP_KEYS = 256
    private const val KEY_REPLY_TEXT = "auto_reply_after_join_group_text"
    private const val DEFAULT_REPLY_TEXT = "大家好，我是新来的，请多关照～"

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

                submitReply(snapshot.roomId, newState, snapshot.selfWxId)
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
            var textInput by remember {
                mutableStateOf(WePrefs.getStringOrDef(KEY_REPLY_TEXT, DEFAULT_REPLY_TEXT))
            }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_reply_after_join_group_name)) },
                text = {
                    TextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(R.string.auto_reply_after_join_group_reply_text_label))
                        }
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(onClick = {
                        WePrefs.putString(KEY_REPLY_TEXT, textInput)
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }

    private fun submitReply(roomId: String, state: WeChatroomSyncState, selfWxId: String) {
        val key = dedupKey(state)
        if (!markDedupKey(key)) {
            WeLogger.d(TAG, "skip duplicate reply room=$roomId key=$key version=${state.memberVersion}")
            return
        }

        val replyText = WePrefs.getStringOrDef(KEY_REPLY_TEXT, DEFAULT_REPLY_TEXT)

        scope.launch {
            try {
                if (WeApi.selfWxId != selfWxId) {
                    WeLogger.d(TAG, "skip stale reply room=$roomId key=$key")
                    return@launch
                }
                val sent = WeMessageApi.sendText(roomId, replyText)
                if (sent) {
                    WeLogger.i(TAG, "sent reply to room=$roomId key=$key version=${state.memberVersion}")
                } else {
                    WeLogger.w(TAG, "reply send returned false room=$roomId key=$key")
                }
            } catch (e: Exception) {
                WeLogger.w(TAG, "reply submission failed room=$roomId key=$key version=${state.memberVersion}", e)
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
