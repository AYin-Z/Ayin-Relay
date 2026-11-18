package com.hermesandroid.relay.ui.components

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import com.hermesandroid.relay.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.AppAnalytics
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProfilePresentation
import com.hermesandroid.relay.data.ProfilePresentationPolicy
import com.hermesandroid.relay.data.ProfilePresence
import com.hermesandroid.relay.data.ProfilePresenceResolver
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.network.upstream.ApiModelOption
import com.hermesandroid.relay.network.upstream.ChatMode
import com.hermesandroid.relay.network.upstream.GatewayApprovalMode
import com.hermesandroid.relay.network.upstream.GatewayApprovalModeCapability
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.relay.ConnectionState
import com.hermesandroid.relay.ui.UiMessageBus
import com.hermesandroid.relay.ui.theme.LocalBrand
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ChatRuntimeStatus
import com.hermesandroid.relay.viewmodel.ChatTransportReadiness
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.resolveChatRuntimeStatus
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    monospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontFamily = if (monospace) FontFamily.Monospace else null
        )
    }
}

@Composable
private fun StatusChip(text: String, background: Color, contentColor: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = contentColor,
        modifier = Modifier
            .clip(appearanceRoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun ChipRow(label: String, chip: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        chip()
    }
}

@Composable
private fun connectionChip(state: ConnectionState) {
    val (label, bg, fg) = when (state) {
        ConnectionState.Connected -> Triple(
            stringResource(R.string.conn_info_connected),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        ConnectionState.Connecting -> Triple(
            stringResource(R.string.conn_info_connecting),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        ConnectionState.Reconnecting -> Triple(
            stringResource(R.string.conn_info_reconnecting),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        ConnectionState.Disconnected -> Triple(
            stringResource(R.string.conn_info_disconnected),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    StatusChip(text = label, background = bg, contentColor = fg)
}

@Composable
private fun authStateChip(state: AuthState) {
    val (label, bg, fg) = when (state) {
        is AuthState.Unpaired -> Triple(
            stringResource(R.string.relay_state_optional),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        is AuthState.Pairing -> Triple(
            stringResource(R.string.conn_info_pairing),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        is AuthState.Paired -> Triple(
            stringResource(R.string.relay_state_ready),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        is AuthState.Failed -> Triple(
            stringResource(R.string.relay_state_needs_repair),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }
    StatusChip(text = label, background = bg, contentColor = fg)
}

// ---------------------------------------------------------------------------
// 1. SessionInfoSheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInfoSheet(
    connectionViewModel: ConnectionViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    val authState by connectionViewModel.authState.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    // Pre-resolve strings for non-Composable callbacks/lambdas
    val pairingCodeLabel = stringResource(R.string.conn_info_pairing_code)
    val copyPairingCodeDesc = stringResource(R.string.conn_info_copy_pairing_code)
    val grantNeverFmt = stringResource(R.string.conn_info_grant_never)
    val channelGrantsLabel = stringResource(R.string.conn_info_channel_grants)
    val transportLabel = stringResource(R.string.conn_info_transport)
    val testingText = stringResource(R.string.conn_info_testing)
    // Pass 2 (2026-04-18): the old `sessionLabels: List<String>` field was
    // replaced by a structured `agentProfiles: List<Profile>` on the VM
    // (sourced from `auth.ok`'s `profiles` entry, whose items are objects
    // with {name, model, description}). Render the profile names here so
    // the user can confirm which agents the server advertised.
    val agentProfiles by connectionViewModel.agentProfiles.collectAsState()
    val pairedSession by connectionViewModel.currentPairedSession.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.conn_info_session_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.conn_info_session_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            ChipRow(label = stringResource(R.string.conn_info_state)) { authStateChip(authState) }

            InfoRow(label = stringResource(R.string.conn_info_relay_url), value = relayUrl, monospace = true)

            ChipRow(label = stringResource(R.string.conn_info_connection)) { connectionChip(relayConnectionState) }

            HorizontalDivider()

            // Pairing code — big monospace + copy
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.conn_info_pairing_code),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pairingCode,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText(pairingCodeLabel, pairingCode)
                                )
                            )
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.conn_info_copy_pairing_code)
                        )
                    }
                }
            }

            HorizontalDivider()

            InfoRow(
                label = stringResource(R.string.conn_info_device),
                value = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            )

            InfoRow(
                label = stringResource(R.string.conn_info_session_token_present),
                value = if (authState is AuthState.Paired) stringResource(R.string.conn_info_yes) else stringResource(R.string.conn_info_no)
            )

            InfoRow(
                label = stringResource(R.string.conn_info_agent_profiles),
                value = if (agentProfiles.isEmpty()) {
                    stringResource(R.string.conn_info_none)
                } else {
                    agentProfiles.joinToString(", ") { it.name }
                }
            )

            // Security overhaul (2026-04-11) — show expiry + grants + storage.
            pairedSession?.let { paired ->
                HorizontalDivider()
                Text(
                    text = if (authState is AuthState.Paired) {
                        stringResource(R.string.conn_info_session_details)
                    } else {
                        stringResource(R.string.conn_info_stored_session_details)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (authState !is AuthState.Paired) {
                    Text(
                        text = stringResource(R.string.conn_info_stored_session_details_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val expiryLabel = when {
                    paired.expiresAt == null -> stringResource(R.string.conn_info_never)
                    else -> java.text.DateFormat
                        .getDateInstance(java.text.DateFormat.MEDIUM)
                        .format(java.util.Date(paired.expiresAt * 1000L))
                }
                InfoRow(label = stringResource(R.string.conn_info_expires), value = expiryLabel)

                if (paired.grants.isNotEmpty()) {
                    val grantsLabel = paired.grants.entries.joinToString(", ") { (k, v) ->
                        if (v == null) String.format(grantNeverFmt, k) else k
                    }
                    InfoRow(label = channelGrantsLabel, value = grantsLabel)
                }

                val transportLabelText = paired.transportHint?.uppercase() ?: "—"
                InfoRow(label = transportLabel, value = transportLabelText)

                InfoRow(
                    label = stringResource(R.string.conn_info_key_storage),
                    value = if (paired.hasHardwareStorage) stringResource(R.string.conn_info_hardware_strongbox) else stringResource(R.string.conn_info_hardware_tee)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        connectionViewModel.clearSession()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(0.5f)
                ) {
                    Text(stringResource(R.string.conn_info_clear_session))
                }
                OutlinedButton(
                    onClick = { connectionViewModel.regeneratePairingCode() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.conn_info_regenerate_code))
                }
            }

            HorizontalDivider()
            DiagnosticsLogPanel(
                categories = setOf(
                    DiagnosticCategory.Session,
                    DiagnosticCategory.Auth,
                    DiagnosticCategory.Relay,
                ),
                limit = 6,
                showCategory = true,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 2. ApiServerInfoSheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiServerInfoSheet(
    connectionViewModel: ConnectionViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val testingText = stringResource(R.string.conn_info_testing)

    val apiServerUrl by connectionViewModel.apiServerUrl.collectAsState()
    val apiServerReachable by connectionViewModel.apiServerReachable.collectAsState()
    val chatMode by connectionViewModel.chatMode.collectAsState()
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()

    // Reactive flag from AuthManager — updated whenever setApiKey/clearApiKey
    // runs and seeded from stored prefs on init.
    val apiKeyPresent by connectionViewModel.authManager.apiKeyPresent.collectAsState()

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.conn_info_api_server_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.conn_info_api_server_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            InfoRow(label = stringResource(R.string.conn_info_url), value = apiServerUrl, monospace = true)

            ChipRow(label = stringResource(R.string.conn_info_reachable)) {
                val (label, bg, fg) = if (apiServerReachable) {
                    Triple(
                        stringResource(R.string.conn_info_yes),
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Triple(
                        stringResource(R.string.conn_info_no),
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                StatusChip(text = label, background = bg, contentColor = fg)
            }

            InfoRow(label = stringResource(R.string.conn_info_streaming_mode), value = chatMode.toString())
            InfoRow(label = stringResource(R.string.conn_info_route_preference), value = streamingEndpoint)
            InfoRow(
                label = stringResource(R.string.conn_info_api_key_set),
                value = if (apiKeyPresent) stringResource(R.string.conn_info_yes_hidden) else stringResource(R.string.conn_info_no)
            )
            InfoRow(
                label = stringResource(R.string.conn_info_last_health_check),
                value = if (apiServerReachable) stringResource(R.string.conn_info_just_now_ok) else stringResource(R.string.conn_info_just_now_failed)
            )

            if (testResult != null) {
                Text(
                    text = testResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedButton(
                onClick = {
                    testing = true
                    testResult = testingText
                    connectionViewModel.testApiConnection { _success, message ->
                        testing = false
                        testResult = message
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (testing) stringResource(R.string.conn_info_testing) else stringResource(R.string.conn_info_test_connection))
            }

            HorizontalDivider()
            DiagnosticsLogPanel(
                categories = setOf(
                    DiagnosticCategory.Api,
                    DiagnosticCategory.Auth,
                    DiagnosticCategory.Endpoint,
                ),
                limit = 6,
                showCategory = true,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 3. RelayInfoSheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayInfoSheet(
    connectionViewModel: ConnectionViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val context = LocalContext.current

    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val isInsecureConnection by connectionViewModel.isInsecureConnection.collectAsState()
    val insecureMode by connectionViewModel.insecureMode.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.conn_info_relay_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.conn_info_relay_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            InfoRow(label = stringResource(R.string.conn_info_url), value = relayUrl, monospace = true)
            ChipRow(label = stringResource(R.string.conn_info_connection_state)) { connectionChip(relayConnectionState) }

            if (isInsecureConnection) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(appearanceRoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.conn_info_ws_unencrypted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            InfoRow(
                label = stringResource(R.string.conn_info_insecure_mode_allowed),
                value = if (insecureMode) stringResource(R.string.conn_info_yes) else stringResource(R.string.conn_info_no)
            )
            Spacer(modifier = Modifier.height(4.dp))

            val isConnected = relayConnectionState == ConnectionState.Connected ||
                relayConnectionState == ConnectionState.Connecting ||
                relayConnectionState == ConnectionState.Reconnecting

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { connectionViewModel.connectRelay() },
                    enabled = relayUrl.isNotBlank() && !isConnected,
                    modifier = Modifier.fillMaxWidth(0.5f)
                ) {
                    Text(stringResource(R.string.conn_info_connect))
                }
                OutlinedButton(
                    onClick = { connectionViewModel.disconnectRelay() },
                    enabled = isConnected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.conn_info_disconnect))
                }
            }

            HorizontalDivider()
            DiagnosticsLogPanel(
                categories = setOf(
                    DiagnosticCategory.Relay,
                    DiagnosticCategory.Endpoint,
                    DiagnosticCategory.Session,
                    DiagnosticCategory.Voice,
                ),
                limit = 8,
                showCategory = true,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 4. AgentInfoSheet
// ---------------------------------------------------------------------------
//
// Consolidated "agent" sheet opened from the Chat top bar. Replaces the three
// separate surfaces that used to split agent state across the UI:
//   - the old AlertDialog on header tap (read-only connection summary)
//   - the ProfilePicker chip (profile dropdown)
//   - the PersonalityPicker chip (personality dropdown)
//
// One bottom sheet, one hierarchy: Profile → Personality → Connection. Mirrors
// ChatViewModel.startStream's precedence rule: a profile with a non-blank
// systemMessage overrides whatever personality is selected. When that case
// triggers, the Personality section visually de-emphasizes (alpha drop) and
// the Profile section footer spells out the override.

private enum class AgentPassportPicker {
    Personality,
    Model,
    Reasoning,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentInfoSheet(
    connectionViewModel: ConnectionViewModel,
    chatViewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onNavigateToConnections: () -> Unit,
    onNavigateToProfileInspector: (String) -> Unit = {},
) {
    val agentProfiles by connectionViewModel.agentProfiles.collectAsState()
    val selectedProfile by connectionViewModel.selectedProfile.collectAsState()
    val resolvedProfile by connectionViewModel.effectiveDisplayProfile.collectAsState()
    val effectiveSessionProfileName by
        connectionViewModel.effectiveSessionProfileName.collectAsState()
    val profilePresentation by connectionViewModel.profilePresentation.collectAsState()
    val profileDisplayAlias by connectionViewModel.profileDisplayAlias.collectAsState()
    val isProfileLocked by connectionViewModel.isProfileLocked.collectAsState()
    val selectedPersonality by chatViewModel.selectedPersonality.collectAsState()
    val personalityNames by chatViewModel.personalityNames.collectAsState()
    val defaultPersonality by chatViewModel.defaultPersonality.collectAsState()
    val selectedModel by chatViewModel.selectedModelOverride.collectAsState()
    val selectedProvider by chatViewModel.selectedProviderOverride.collectAsState()
    val serverModel by chatViewModel.serverModelName.collectAsState()
    val gatewayModel by chatViewModel.gatewayCurrentModel.collectAsState()
    val gatewayProvider by chatViewModel.gatewayCurrentProvider.collectAsState()
    val modelProviders by chatViewModel.modelProviders.collectAsState()
    val apiModelOptions by chatViewModel.apiModelOptions.collectAsState()
    val modelOptionsRefreshing by chatViewModel.modelOptionsRefreshing.collectAsState()
    val reasoningCapabilityRevision by chatViewModel.reasoningCapabilityRevision.collectAsState()
    val selectedReasoning by chatViewModel.selectedReasoningEffort.collectAsState()
    val approvalMode by chatViewModel.approvalMode.collectAsState()
    val approvalCapability by chatViewModel.approvalModeCapability.collectAsState()
    val approvalWritable by chatViewModel.approvalModeWritable.collectAsState()
    val yoloEnabled by chatViewModel.yoloEnabled.collectAsState()
    val fastEnabled by chatViewModel.fastEnabled.collectAsState()
    val gatewayAvailability by connectionViewModel.gatewayAvailability.collectAsState()
    val chatReady by connectionViewModel.chatReady.collectAsState()
    val serverCapabilities by connectionViewModel.serverCapabilities.collectAsState()
    val isStreaming by chatViewModel.isStreaming.collectAsState()
    val messages by chatViewModel.messages.collectAsState()
    val sessions by chatViewModel.sessions.collectAsState()
    val currentSessionId by chatViewModel.currentSessionId.collectAsState()
    val contextWindow by chatViewModel.contextWindow.collectAsState()
    val appStats by AppAnalytics.stats.collectAsState()
    val effectiveApiServerUrl by connectionViewModel.effectiveApiServerUrl.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val effectiveRelayUrl by connectionViewModel.effectiveRelayUrl.collectAsState()
    val effectiveDashboardUrl by connectionViewModel.effectiveDashboardUrl.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()
    val voiceReady by connectionViewModel.voiceReady.collectAsState()
    val standardVoiceReady by connectionViewModel.standardVoiceReady.collectAsState()
    val proactiveEnabled by connectionViewModel.proactiveEnabled.collectAsState()
    val activeEndpoint by connectionViewModel.activeEndpoint.collectAsState()
    val allConnections by connectionViewModel.connectionStore.connections.collectAsState()
    val activeConnectionId by
        connectionViewModel.connectionStore.activeConnectionId.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()

    val reasoningAvailability = remember(
        selectedModel,
        selectedProvider,
        gatewayModel,
        gatewayProvider,
        modelProviders,
        apiModelOptions,
        reasoningCapabilityRevision,
    ) {
        chatViewModel.reasoningEffortAvailability()
    }

    var selectedTab by remember { mutableStateOf(0) }
    var activePicker by remember { mutableStateOf<AgentPassportPicker?>(null) }
    var showProfileSwitcher by remember { mutableStateOf(false) }
    var showIdentityEditor by remember { mutableStateOf(false) }
    var showProfileManager by remember { mutableStateOf(false) }

    val activeConnection = remember(allConnections, activeConnectionId) {
        allConnections.firstOrNull { it.id == activeConnectionId }
    }
    val currentSession = remember(sessions, currentSessionId) {
        sessions.firstOrNull { it.sessionId == currentSessionId }
    }
    val sessionModelState = resolveSessionModelUiState(
        hasSession = currentSessionId != null,
        pendingModel = selectedModel,
        pendingProvider = selectedProvider,
        gatewayModel = gatewayModel,
        gatewayProvider = gatewayProvider,
        persistedSessionModel = currentSession?.model,
        profileDefaultModel = resolvedProfile?.model,
        serverDefaultModel = serverModel,
    )
    val sessionPickerProvider = sessionModelState.pickerProvider
        ?: sessionModelState.pickerModel?.let { model ->
            modelProviders.singleOrNull { model in it.models }?.slug
        }
    val agentName = AgentDisplay.agentName(
        profile = resolvedProfile,
        selectedPersonality = selectedPersonality,
        defaultPersonality = defaultPersonality,
        connectionLabel = null,
        localDisplayAlias = profileDisplayAlias,
    )
    val profileLabel = selectedProfile?.let {
        AgentDisplay.profileDisplayName(it) ?: it.name
    } ?: stringResource(R.string.conn_info_server_default)
    val serverDefaultLabel = stringResource(R.string.conn_info_server_default)
    val modelLabel = AgentDisplay.displayModelName(sessionModelState.model)
        ?: serverDefaultLabel
    val serverDefaultModelLabel = AgentDisplay.displayModelName(serverModel)
        ?: AgentDisplay.displayModelName(resolvedProfile?.model)
    val providerLabel = modelProviders
        .firstOrNull { it.slug.equals(sessionModelState.provider, ignoreCase = true) }
        ?.name
        ?.takeIf { it.isNotBlank() }
        ?: sessionModelState.provider
    val sessionModelLabel = modelLabel
    val sessionProviderLabel = sessionModelState.model
        ?.takeIf { it.isNotBlank() }
        ?.let { sessionModel ->
            modelProviders.firstOrNull { sessionModel in it.models }?.name
        }
        ?: providerLabel
    val connected = chatReady
    val resolvedPresence = resolvedProfile?.let {
        ProfilePresenceResolver.resolve(it, connected)
    }
    val sessionTransport = sessionPathTransport(
        connectionViewModel.resolveStreamingEndpoint(streamingEndpoint),
    )
    val routeLabel = if (sessionTransport.isGateway) {
        activeConnection?.routeCandidates
            ?.firstOrNull {
                it.dashboard?.url?.trimEnd('/') == effectiveDashboardUrl.trimEnd('/')
            }
            ?.displayLabel()
            ?: effectiveDashboardUrl.takeIf { it.isNotBlank() }?.let { url ->
                com.hermesandroid.relay.data.Connection.endpointCandidateFromDashboardUrl(
                    role = com.hermesandroid.relay.data.Connection.inferRouteRole(url),
                    priority = 0,
                    dashboardUrl = url,
                )?.displayLabel()
            }
    } else {
        activeEndpoint?.displayLabel()
            ?: com.hermesandroid.relay.data.Connection
                .extractDefaultLabel(effectiveApiServerUrl)
                .takeIf { it.isNotBlank() }
    }
    val sessionCaps = sessionCapabilities(
        transport = sessionTransport,
        gatewayAvailability = gatewayAvailability,
        upstreamMediaAvailable = gatewayAvailability == GatewayAvailability.Ready || standardVoiceReady,
        relayConnected = relayConnectionState == ConnectionState.Connected,
        relayConfigured = relayUrl.isNotBlank(),
        voiceReady = voiceReady,
        threadsActive = proactiveEnabled && authState is AuthState.Paired,
    )
    val contextLabel = contextWindow?.let {
        "${compactTokenCount(it.usedTokens)} / ${compactTokenCount(it.maxTokens)}"
    } ?: "—"
    val profileSwitchEnabled =
        !isStreaming || chatViewModel.streamingEndpoint == "gateway"
    val gatewayControlsAvailable = gatewayAvailability != GatewayAvailability.Unreachable &&
        gatewayAvailability != GatewayAvailability.SignInRequired &&
        gatewayAvailability != GatewayAvailability.Unsupported
    val unavailableModelLabel = stringResource(R.string.chat_not_on_plan)
    val modelNeedsSetupLabel = stringResource(R.string.chat_needs_setup)
    val passportModelOptions = remember(
        modelProviders,
        apiModelOptions,
        agentProfiles,
        selectedModel,
        selectedProvider,
        sessionModelState,
        sessionPickerProvider,
        modelLabel,
        unavailableModelLabel,
        modelNeedsSetupLabel,
    ) {
        val fallbackOptions = (
            apiModelOptions +
                agentProfiles.mapNotNull { profile ->
                    AgentDisplay.requestModelName(profile.model)?.let { ApiModelOption(it) }
                } +
                listOfNotNull(
                    AgentDisplay.requestModelName(selectedModel)?.let { ApiModelOption(it) },
                )
            )
            .distinctBy { it.id }
        buildList {
            add(
                ChatInputPickerOption(
                    label = serverDefaultLabel,
                    value = null,
                    secondary = serverDefaultModelLabel,
                    selected = sessionModelState.inheritsProfileDefault,
                ),
            )
            modelProviders
                .sortedByDescending { it.isCurrent }
                .forEach { provider ->
                    provider.models.distinct().forEach { model ->
                        val unavailable = model in provider.unavailableModels
                        add(
                            ChatInputPickerOption(
                                label = model,
                                value = model,
                                provider = provider.slug,
                                group = provider.name,
                                secondary = when {
                                    unavailable -> unavailableModelLabel
                                    !provider.authenticated ->
                                        provider.warning ?: modelNeedsSetupLabel
                                    else -> null
                                },
                                selected = sessionModelState.pickerModel == model &&
                                    sessionPickerProvider.equals(provider.slug, ignoreCase = true),
                                enabled = !unavailable,
                            ),
                        )
                    }
                }
            val gatewayModelIds = modelProviders.flatMap { it.models }.toSet()
            fallbackOptions
                .filterNot { it.id in gatewayModelIds }
                .forEach { model ->
                    add(
                        ChatInputPickerOption(
                            label = AgentDisplay.displayModelName(model.id) ?: model.id,
                            value = model.id,
                            group = "Routes",
                            secondary = model.routeDetail,
                            selected = sessionModelState.pickerModel == model.id,
                        ),
                    )
                }
        }
    }

    LaunchedEffect(Unit) {
        chatViewModel.refreshModelOptions(catalogOnly = true)
        chatViewModel.refreshApprovalMode()
        chatViewModel.refreshPersonalities()
        chatViewModel.refreshModels(catalogOnly = true)
        connectionViewModel.refreshDashboardProfiles()
    }

    val passportScrollState = rememberScrollState()
    LaunchedEffect(showIdentityEditor) {
        passportScrollState.scrollTo(0)
    }

    BackHandler(enabled = showIdentityEditor) {
        showIdentityEditor = false
    }

    AgentPassportSheetHost(
        scrollState = passportScrollState,
        onDismiss = onDismiss,
    ) {
            AgentPassportHeader(
                agentName = agentName,
                profileLabel = profileLabel,
                modelLabel = modelLabel,
                providerLabel = providerLabel,
                connected = connected,
                presence = resolvedPresence,
                hasSoul = resolvedProfile?.hasSoul == true,
                skillCount = resolvedProfile?.skillCount ?: 0,
                onProfileClick = { showProfileSwitcher = true },
            )

            if (showIdentityEditor) {
                AgentPassportIdentityEditor(
                    connectionViewModel = connectionViewModel,
                    profileDisplayAlias = profileDisplayAlias,
                    fallbackName = agentName,
                    effectiveProfile = resolvedProfile,
                    hasProfiles = agentProfiles.isNotEmpty(),
                    onBack = { showIdentityEditor = false },
                    onManageProfiles = { showProfileManager = true },
                    onInspectProfile = { profile ->
                        onDismiss()
                        onNavigateToProfileInspector(profile.name)
                    },
                )
            } else {
                AgentPassportTabs(
                    selected = selectedTab,
                    onSelected = { selectedTab = it },
                )

                if (selectedTab == 0) {
                Surface(
                    shape = appearanceRoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    ),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.conn_info_session_title),
                            modifier = Modifier.padding(
                                start = 18.dp,
                                top = 17.dp,
                                end = 18.dp,
                                bottom = 4.dp,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        PassportConfigRow(
                            icon = Icons.Filled.ViewInAr,
                            title = stringResource(R.string.conn_info_model_title),
                            value = if (sessionModelState.inheritsProfileDefault) {
                                stringResource(R.string.conn_info_server_default_model, modelLabel)
                            } else {
                                modelLabel
                            },
                            expanded = false,
                            enabled = !isStreaming,
                            onClick = { activePicker = AgentPassportPicker.Model },
                        )
                        if (
                            reasoningAvailability.supported != false &&
                            reasoningAvailability.choices.isNotEmpty()
                        ) {
                            PassportDivider()
                            PassportConfigRow(
                                icon = Icons.Filled.Psychology,
                                title = stringResource(R.string.chat_select_reasoning_effort),
                                value = selectedReasoning?.let { reasoningEffortLabel(it) }
                                    ?: serverDefaultLabel,
                                expanded = false,
                                enabled = gatewayControlsAvailable && !isStreaming,
                                onClick = { activePicker = AgentPassportPicker.Reasoning },
                            )
                        }
                    }
                }

                // Upstream personality changes persist to the active profile
                // while also applying to the live session. Keep that control
                // visibly separate from the session-only model/effort card so
                // it cannot be mistaken for an ephemeral override.
                Surface(
                    shape = appearanceRoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    ),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.conn_info_profile),
                            modifier = Modifier.padding(
                                start = 18.dp,
                                top = 17.dp,
                                end = 18.dp,
                                bottom = 4.dp,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        PassportConfigRow(
                            icon = Icons.Filled.Person,
                            title = stringResource(R.string.conn_info_personality_title),
                            value = AgentDisplay.personalityLabel(
                                selectedPersonality,
                                defaultPersonality,
                            ),
                            expanded = false,
                            enabled = !isStreaming,
                            onClick = { activePicker = AgentPassportPicker.Personality },
                        )
                    }
                }

                AgentPassportSafetyCard(
                    approvalMode = approvalMode,
                    approvalCapability = approvalCapability,
                    approvalWritable = approvalWritable,
                    yoloEnabled = yoloEnabled,
                    fastEnabled = fastEnabled,
                    controlsAvailable = gatewayControlsAvailable,
                    gatewayReady = gatewayAvailability == GatewayAvailability.Ready,
                    isStreaming = isStreaming,
                    onApprovalMode = chatViewModel::setApprovalMode,
                    onYolo = chatViewModel::setYolo,
                    onFast = chatViewModel::setFast,
                )

                val brand = LocalBrand.current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(appearanceRoundedCornerShape(18.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(brand.purple, brand.relay),
                            ),
                        )
                        .clickable(enabled = !isStreaming) {
                            chatViewModel.createNewChat()
                            onDismiss()
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.AddComment,
                            contentDescription = null,
                            tint = brand.paper,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = stringResource(R.string.conn_info_start_new_chat),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = brand.paper,
                        )
                    }
                }

                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showIdentityEditor = true },
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.conn_info_customize_identity))
                }
                resolvedProfile?.let { profile ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onDismiss()
                            onNavigateToProfileInspector(profile.name)
                        },
                    ) {
                        Icon(Icons.Filled.Visibility, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(
                                R.string.conn_info_inspect_profile,
                                AgentDisplay.profileDisplayName(profile) ?: profile.name,
                            ),
                        )
                    }
                }
                } else {
                AgentPassportSessionTab(
                    currentSessionLabel = currentSession?.title
                        ?.takeIf { it.isNotBlank() }
                        ?: currentSessionId?.take(12)
                        ?: "—",
                    messageCount = messages.size,
                    tokenSummary = stringResource(
                        R.string.conn_info_tokens_in_out,
                        appStats.currentSessionTokensIn,
                        appStats.currentSessionTokensOut,
                    ),
                    sessionId = currentSessionId,
                    sessionProfileName = effectiveSessionProfileName,
                    modelLabel = sessionModelLabel,
                    providerLabel = sessionProviderLabel,
                    reasoningLabel = selectedReasoning?.let { reasoningEffortLabel(it) }
                        ?: stringResource(R.string.conn_info_server_default),
                    approvalMode = approvalMode,
                    approvalCapability = approvalCapability,
                    fastEnabled = fastEnabled,
                    contextLabel = contextLabel,
                    transport = sessionTransport,
                    routeLabel = routeLabel,
                    capabilities = sessionCaps,
                    relayConnectionState = relayConnectionState,
                    apiServerUrl = effectiveApiServerUrl,
                    relayUrl = effectiveRelayUrl,
                    streamingEndpoint = streamingEndpoint,
                    gatewayAvailability = gatewayAvailability,
                    serverCapabilities = serverCapabilities,
                    connectionLabel = activeConnection?.label ?: routeLabel ?: "—",
                    authState = authState,
                    onManageConnections = {
                        onDismiss()
                        onNavigateToConnections()
                    },
                )
                }
            }
    }

    when (activePicker) {
        AgentPassportPicker.Personality -> OptionPickerSheet(
            title = stringResource(R.string.conn_info_personality_title),
            options = buildList {
                add(
                    ChatInputPickerOption(
                        label = stringResource(R.string.conn_info_none),
                        value = "none",
                        secondary = stringResource(R.string.conn_info_no_personality_overlay),
                        selected = AgentDisplay.isClearedPersonality(selectedPersonality),
                    ),
                )
                personalityNames.forEach { personality ->
                    add(
                        ChatInputPickerOption(
                            label = personality.replaceFirstChar { it.uppercase() },
                            value = personality,
                            selected = selectedPersonality == personality,
                        ),
                    )
                }
            },
            onSelect = { option ->
                option.value?.let(chatViewModel::selectPersonality)
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        AgentPassportPicker.Model -> ModelPickerSheet(
            options = passportModelOptions,
            refreshing = modelOptionsRefreshing,
            onRefresh = {
                chatViewModel.refreshModelOptions(refresh = true, catalogOnly = true)
            },
            onSelect = { option ->
                activePicker = null
                if (option.provider == null && apiModelOptions.any { it.id == option.value }) {
                    option.value?.let(chatViewModel::selectApiModel)
                } else {
                    chatViewModel.selectModel(option.value, option.provider)
                }
            },
            onDismiss = { activePicker = null },
        )
        AgentPassportPicker.Reasoning -> {
            val reasoningOptions = reasoningAvailability.choices.map { value ->
                ChatInputPickerOption(
                    label = reasoningEffortLabel(value),
                    value = value,
                    selected = selectedReasoning == value,
                    enabled = reasoningAvailability.supported != false &&
                        gatewayControlsAvailable && !isStreaming,
                )
            }
            OptionPickerSheet(
                title = stringResource(R.string.chat_select_reasoning_effort),
                subtitle = when {
                    reasoningAvailability.exact &&
                        selectedReasoning != null &&
                        selectedReasoning !in reasoningAvailability.choices -> stringResource(
                            R.string.reasoning_effort_current_outside_supported,
                            reasoningEffortLabel(selectedReasoning),
                            reasoningOptions.joinToString { it.label },
                        )
                    !reasoningAvailability.exact ->
                        stringResource(R.string.reasoning_effort_standard_levels_notice)
                    else -> null
                },
                options = reasoningOptions,
                onSelect = { option ->
                    option.value?.let(chatViewModel::selectReasoningEffort)
                    activePicker = null
                },
                onDismiss = { activePicker = null },
            )
        }
        null -> Unit
    }

    if (showProfileSwitcher) {
        ProfileSwitcherSheet(
            connectionViewModel = connectionViewModel,
            profiles = agentProfiles,
            selectedProfile = selectedProfile,
            resolvedProfile = resolvedProfile,
            presentation = profilePresentation,
            isProfileLocked = isProfileLocked,
            switchEnabled = profileSwitchEnabled,
            onSelect = { profile ->
                if (AgentDisplay.profileSessionKey(profile?.name) !=
                    AgentDisplay.profileSessionKey(selectedProfile?.name)
                ) {
                    connectionViewModel.selectProfile(profile)
                    chatViewModel.activateGatewayProfile(profile)
                }
            },
            onManageDisplay = {
                showProfileSwitcher = false
                showProfileManager = true
            },
            onDismiss = { showProfileSwitcher = false },
        )
    }

    if (showProfileManager) {
        ProfileDisplayManagerDialog(
            profiles = agentProfiles,
            presentation = profilePresentation,
            selectedProfileName = selectedProfile?.name,
            onMove = connectionViewModel::moveProfile,
            onHiddenChange = connectionViewModel::setProfileHidden,
            onReset = connectionViewModel::resetProfilePresentation,
            onDismiss = { showProfileManager = false },
        )
    }
}

internal const val AGENT_PASSPORT_SHEET_TAG = "agentPassportSheet"
internal const val AGENT_PASSPORT_SCROLL_TAG = "agentPassportScroll"
internal const val AGENT_PASSPORT_CLOSE_TAG = "agentPassportClose"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPassportSheetHost(
    scrollState: ScrollState,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // A full-height sheet and its long verticalScroll child otherwise
        // compete for the same drag at the content boundaries. On some devices
        // that repeatedly settles/re-expands the sheet and produces a visible
        // vibration at the bottom. Close and system Back remain explicit.
        sheetGesturesEnabled = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.94f)
                .testTag(AGENT_PASSPORT_SHEET_TAG),
        ) {
            // Keep the close action visible while compact or large-font content
            // scrolls independently below it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = 20.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.conn_info_agent_passport),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(AGENT_PASSPORT_CLOSE_TAG),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_close),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(
                        state = scrollState,
                        overscrollEffect = null,
                    )
                    .testTag(AGENT_PASSPORT_SCROLL_TAG)
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun AgentPassportIdentityEditor(
    connectionViewModel: ConnectionViewModel,
    profileDisplayAlias: String?,
    fallbackName: String,
    effectiveProfile: Profile?,
    hasProfiles: Boolean,
    onBack: () -> Unit,
    onManageProfiles: () -> Unit,
    onInspectProfile: (Profile) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.settings_back),
            )
        }
        Text(
            text = stringResource(R.string.conn_info_customize_identity),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }

    Surface(
        shape = appearanceRoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DisplayAliasSection(
                currentAlias = profileDisplayAlias,
                fallbackName = fallbackName,
                onSave = connectionViewModel::setProfileDisplayAlias,
            )
            AgentIconRow(connectionViewModel)
        }
    }

    if (hasProfiles) {
        OutlinedButton(
            onClick = onManageProfiles,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Filled.Tune, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.conn_info_manage_profiles))
        }
    }

    effectiveProfile?.let { profile ->
        TextButton(
            onClick = { onInspectProfile(profile) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    R.string.conn_info_inspect_profile,
                    AgentDisplay.profileDisplayName(profile) ?: profile.name,
                ),
            )
        }
    }
}

@Composable
private fun AgentPassportHeader(
    agentName: String,
    profileLabel: String,
    modelLabel: String,
    providerLabel: String?,
    connected: Boolean,
    presence: ProfilePresence?,
    hasSoul: Boolean,
    skillCount: Int,
    onProfileClick: () -> Unit,
) {
    val brand = LocalBrand.current
    val (presenceLabel, presenceColor) = when (presence) {
        ProfilePresence.ONLINE ->
            stringResource(R.string.conn_info_profile_online) to brand.green
        ProfilePresence.AVAILABLE ->
            stringResource(R.string.conn_info_profile_available) to MaterialTheme.colorScheme.primary
        ProfilePresence.OFFLINE ->
            stringResource(R.string.conn_info_profile_offline) to MaterialTheme.colorScheme.error
        null -> if (connected) {
            stringResource(R.string.conn_info_connected) to brand.green
        } else {
            stringResource(R.string.conn_info_disconnected) to MaterialTheme.colorScheme.error
        }
    }
    val identityMetadata = listOfNotNull(
        stringResource(R.string.conn_info_soul).takeIf { hasSoul },
        stringResource(R.string.conn_info_skills_count, skillCount).takeIf { skillCount > 0 },
    ).joinToString(" · ")
    Surface(
        shape = appearanceRoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .size(68.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                            CircleShape,
                        ),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    AgentAvatarFace(
                        name = agentName,
                        letterStyle = MaterialTheme.typography.headlineSmall,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = agentName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(presenceColor),
                            )
                            Text(
                                text = presenceLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = presenceColor,
                                maxLines = 1,
                            )
                        }
                    }
                    if (identityMetadata.isNotBlank()) {
                        Text(
                            text = identityMetadata,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onProfileClick),
                shape = appearanceRoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = stringResource(R.string.profile_shelf_switch_agent).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profileLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!agentName.equals(profileLabel, ignoreCase = true)) {
                                Text(
                                    text = agentName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            PassportTechnicalField(
                label = stringResource(R.string.voice_test_label_provider),
                value = providerLabel ?: "—",
                modifier = Modifier.fillMaxWidth(),
            )
            PassportTechnicalField(
                label = stringResource(R.string.conn_info_model_title),
                value = modelLabel,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun PassportTechnicalField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = 1,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = valueColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AgentPassportTabs(
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
    ) {
        listOf(
            stringResource(R.string.subagent_lane_label_fallback),
            stringResource(R.string.conn_info_session_title),
        ).forEachIndexed { index, title ->
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .selectable(
                        selected = selected == index,
                        role = Role.Tab,
                        onClick = { onSelected(index) },
                    ),
                color = Color.Transparent,
                contentColor = if (selected == index) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = title,
                        modifier = Modifier.padding(vertical = 11.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (selected == index) 2.dp else 1.dp)
                            .background(
                                if (selected == index) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PassportConfigRow(
    icon: ImageVector,
    title: String,
    value: String,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val expansionState = stringResource(
        if (expanded) {
            R.string.profile_inspector_expanded_state
        } else {
            R.string.profile_inspector_collapsed_state
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { stateDescription = expansionState }
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .alpha(if (enabled) 1f else 0.58f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = if (expanded) {
                Icons.Filled.KeyboardArrowUp
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PassportDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}

internal const val PASSPORT_APPROVAL_CONTROL_TAG = "passportApproval"
internal const val PASSPORT_CHAT_OVERRIDE_CONTROL_TAG = "passportChatOverride"
internal const val PASSPORT_FAST_CONTROL_TAG = "passportFast"

internal data class PassportSegmentOption(
    val label: String,
    val description: String,
)

@Composable
internal fun AgentPassportSafetyCard(
    approvalMode: GatewayApprovalMode?,
    approvalCapability: GatewayApprovalModeCapability,
    approvalWritable: Boolean,
    yoloEnabled: Boolean?,
    fastEnabled: Boolean?,
    controlsAvailable: Boolean,
    gatewayReady: Boolean,
    isStreaming: Boolean,
    onApprovalMode: (GatewayApprovalMode) -> Unit,
    onYolo: (Boolean) -> Unit,
    onFast: (Boolean) -> Unit,
) {
    val approvalModes = listOf(
        GatewayApprovalMode.Manual to PassportSegmentOption(
            label = stringResource(R.string.conn_info_approval_mode_manual),
            description = stringResource(R.string.conn_info_approval_mode_manual_desc),
        ),
        GatewayApprovalMode.Smart to PassportSegmentOption(
            label = stringResource(R.string.conn_info_approval_mode_smart),
            description = stringResource(R.string.conn_info_approval_mode_smart_desc),
        ),
        GatewayApprovalMode.Off to PassportSegmentOption(
            label = stringResource(R.string.conn_info_approval_mode_off),
            description = stringResource(R.string.conn_info_approval_mode_off_desc),
        ),
    )
    Surface(
        shape = appearanceRoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = stringResource(R.string.conn_info_safety_speed_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            PassportSafetyRow(
                icon = Icons.Filled.Security,
                title = stringResource(R.string.conn_info_approval_policy),
                description = stringResource(R.string.conn_info_approval_mode_short_desc),
            ) {
                when (approvalCapability) {
                    GatewayApprovalModeCapability.Unknown -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.conn_info_checking),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    GatewayApprovalModeCapability.Unsupported -> {
                        Text(
                            text = stringResource(R.string.conn_info_approval_mode_unsupported),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    GatewayApprovalModeCapability.Supported -> {
                        PassportSegmentedControl(
                            options = approvalModes.map { it.second },
                            selected = approvalModes.indexOfFirst { it.first == approvalMode }
                                .takeIf { it >= 0 },
                            enabled = controlsAvailable && gatewayReady &&
                                approvalWritable && !isStreaming,
                            testTagPrefix = PASSPORT_APPROVAL_CONTROL_TAG,
                            onSelected = { index -> onApprovalMode(approvalModes[index].first) },
                        )
                    }
                }
            }

            PassportDivider()
            PassportSafetyRow(
                icon = Icons.Filled.ChatBubble,
                title = stringResource(R.string.conn_info_chat_override),
                description = if (approvalMode == GatewayApprovalMode.Off) {
                    stringResource(R.string.conn_info_profile_already_bypasses)
                } else {
                    stringResource(R.string.conn_info_yolo_mode_desc)
                },
            ) {
                PassportSegmentedControl(
                    options = listOf(
                        PassportSegmentOption(
                            label = stringResource(R.string.conn_info_inherited),
                            description = if (approvalMode == GatewayApprovalMode.Off) {
                                stringResource(R.string.conn_info_profile_already_bypasses)
                            } else {
                                stringResource(R.string.conn_info_chat_override_inherited_desc)
                            },
                        ),
                        PassportSegmentOption(
                            label = stringResource(R.string.conn_info_bypassed),
                            description = stringResource(R.string.conn_info_yolo_mode_desc_ephemeral),
                        ),
                    ),
                    selected = if (
                        approvalMode != GatewayApprovalMode.Off && yoloEnabled == true
                    ) 1 else 0,
                    enabled = controlsAvailable && gatewayReady && !isStreaming &&
                        approvalMode != GatewayApprovalMode.Off,
                    testTagPrefix = PASSPORT_CHAT_OVERRIDE_CONTROL_TAG,
                    onSelected = { onYolo(it == 1) },
                )
            }

            PassportDivider()
            PassportSafetyRow(
                icon = Icons.Filled.Bolt,
                title = stringResource(R.string.conn_info_fast_tier),
                description = stringResource(R.string.conn_info_fast_mode_desc),
            ) {
                PassportSegmentedControl(
                    options = listOf(
                        PassportSegmentOption(
                            label = stringResource(R.string.conn_info_fast),
                            description = stringResource(R.string.conn_info_fast_mode_desc),
                        ),
                        PassportSegmentOption(
                            label = stringResource(R.string.appearance_font_normal),
                            description = stringResource(R.string.conn_info_normal_tier_desc),
                        ),
                    ),
                    selected = if (fastEnabled == true) 0 else 1,
                    enabled = controlsAvailable && gatewayReady && !isStreaming,
                    testTagPrefix = PASSPORT_FAST_CONTROL_TAG,
                    onSelected = { onFast(it == 0) },
                )
            }
        }
    }
}

@Composable
private fun PassportSafetyRow(
    icon: ImageVector,
    title: String,
    description: String,
    control: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        control()
    }
}

@Composable
internal fun PassportSegmentedControl(
    options: List<PassportSegmentOption>,
    selected: Int?,
    enabled: Boolean,
    testTagPrefix: String,
    onSelected: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .clip(appearanceRoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(3.dp),
        ) {
            options.forEachIndexed { index, option ->
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("$testTagPrefix-$index")
                        .selectable(
                            selected = selected == index,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelected(index) },
                        )
                        .semantics {
                            contentDescription = "${option.label}. ${option.description}"
                        },
                    shape = appearanceRoundedCornerShape(11.dp),
                    color = when {
                        selected != index -> Color.Transparent
                        enabled -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = when {
                        selected == index && enabled -> MaterialTheme.colorScheme.onPrimary
                        selected == index -> MaterialTheme.colorScheme.onPrimaryContainer
                        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.label,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected == index) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Medium
                            },
                            maxLines = 2,
                        )
                    }
                }
            }
        }
        selected?.let(options::getOrNull)?.let { option ->
            Text(
                text = option.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun AgentPassportSessionTab(
    currentSessionLabel: String,
    messageCount: Int,
    tokenSummary: String,
    sessionId: String?,
    sessionProfileName: String?,
    modelLabel: String,
    providerLabel: String?,
    reasoningLabel: String,
    approvalMode: GatewayApprovalMode?,
    approvalCapability: GatewayApprovalModeCapability,
    fastEnabled: Boolean?,
    contextLabel: String,
    transport: SessionPathTransport,
    routeLabel: String?,
    capabilities: List<SessionCapability>,
    relayConnectionState: ConnectionState,
    apiServerUrl: String,
    relayUrl: String,
    streamingEndpoint: String,
    gatewayAvailability: GatewayAvailability,
    serverCapabilities: com.hermesandroid.relay.network.upstream.ServerCapabilities,
    connectionLabel: String,
    authState: AuthState,
    onManageConnections: () -> Unit,
) {
    val approvalLabel = when (approvalCapability) {
        GatewayApprovalModeCapability.Unknown ->
            stringResource(R.string.conn_info_checking)
        GatewayApprovalModeCapability.Unsupported ->
            stringResource(R.string.conn_info_approval_mode_unsupported)
        GatewayApprovalModeCapability.Supported -> when (approvalMode) {
            GatewayApprovalMode.Manual ->
                stringResource(R.string.conn_info_approval_mode_manual)
            GatewayApprovalMode.Smart ->
                stringResource(R.string.conn_info_approval_mode_smart)
            GatewayApprovalMode.Off ->
                stringResource(R.string.conn_info_approval_mode_off)
            null -> stringResource(R.string.conn_info_checking)
        }
    }
    Surface(
        shape = appearanceRoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text(
                text = stringResource(R.string.conn_info_session_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            InfoRow(stringResource(R.string.conn_info_name), currentSessionLabel)
            InfoRow(
                stringResource(R.string.conn_info_session_id),
                sessionId?.take(12) ?: "—",
                monospace = true,
            )
            InfoRow(
                stringResource(R.string.conn_info_profile),
                sessionProfileName ?: stringResource(R.string.conn_info_server_default),
                monospace = sessionProfileName != null,
            )
            InfoRow(stringResource(R.string.conn_info_messages), messageCount.toString())
            InfoRow(stringResource(R.string.conn_info_tokens), tokenSummary)
            InfoRow(stringResource(R.string.conn_info_context), contextLabel)
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )
            InfoRow(stringResource(R.string.conn_info_model_title), modelLabel)
            providerLabel?.let {
                InfoRow(stringResource(R.string.voice_settings_label_provider), it)
            }
            InfoRow(stringResource(R.string.conn_info_reasoning), reasoningLabel)
            InfoRow(stringResource(R.string.conn_info_approval_policy), approvalLabel)
            InfoRow(
                stringResource(R.string.conn_info_fast_mode_title),
                if (fastEnabled == true) {
                    stringResource(R.string.conn_info_fast)
                } else if (fastEnabled == false) {
                    stringResource(R.string.appearance_font_normal)
                } else {
                    stringResource(R.string.conn_info_server_default)
                },
            )
        }
    }
    Surface(
        shape = appearanceRoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text(
                text = stringResource(R.string.conn_info_connection_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SessionPathSummary(
                transport = transport,
                routeLabel = routeLabel,
                capabilities = capabilities,
            )
            InfoRow(
                stringResource(R.string.conn_info_name),
                connectionLabel,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.conn_info_relay_auth),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                authStateChip(authState)
            }
            SessionPathDetails(
                transport = transport,
                routeLabel = routeLabel,
                relayConnectionState = relayConnectionState,
                capabilities = capabilities,
                apiServerUrl = apiServerUrl,
                relayUrl = relayUrl,
                streamingEndpoint = streamingEndpoint,
                gatewayAvailability = gatewayAvailability,
                serverCapabilities = serverCapabilities,
            )
        }
    }
    OutlinedButton(
        onClick = onManageConnections,
        modifier = Modifier.fillMaxWidth(),
        shape = appearanceRoundedCornerShape(18.dp),
    ) {
        Icon(Icons.Filled.Tune, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.conn_info_manage_connections))
    }
}

private fun compactTokenCount(value: Int): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}m"
    value >= 1_000 -> "${value / 1_000}k"
    else -> value.toString()
}

// --- AgentInfoSheet helpers ----------------------------------------------

@Composable
internal fun ProfileDisplayManagerDialog(
    profiles: List<Profile>,
    presentation: ProfilePresentation,
    selectedProfileName: String?,
    onMove: (String?, Int) -> Unit,
    onHiddenChange: (String?, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val orderedKeys = ProfilePresentationPolicy.orderedKeys(profiles, presentation)
    val selectedKey = AgentDisplay.profileSessionKey(selectedProfileName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conn_info_manage_profiles)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.conn_info_manage_profiles_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                orderedKeys.forEachIndexed { index, key ->
                    val isServerDefault = key == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY
                    val profile = profiles.firstOrNull { it.name == key }
                    val label = if (isServerDefault) {
                        stringResource(R.string.profile_follow_server_default)
                    } else {
                        profile?.let(AgentDisplay::profileDisplayName)
                            ?: key.replaceFirstChar { it.uppercase() }
                    }
                    val hidden = key in presentation.hidden
                    ProfileDisplayManagerRow(
                        label = label,
                        hidden = hidden,
                        canHide = hidden || selectedKey != key,
                        canMoveUp = index > 0,
                        canMoveDown = index < orderedKeys.lastIndex,
                        onMoveUp = { onMove(profile?.name, -1) },
                        onMoveDown = { onMove(profile?.name, 1) },
                        onHiddenChange = { onHiddenChange(profile?.name, it) },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text(stringResource(R.string.conn_info_reset_profile_display)) }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
        },
    )
}

@Composable
private fun ProfileDisplayManagerRow(
    label: String,
    hidden: Boolean,
    canHide: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onHiddenChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.conn_info_move_profile_up, label))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.conn_info_move_profile_down, label))
        }
        IconButton(onClick = { onHiddenChange(!hidden) }, enabled = canHide) {
            Icon(
                imageVector = if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = stringResource(
                    if (hidden) R.string.conn_info_show_profile else R.string.conn_info_hide_profile,
                    label,
                ),
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String, hint: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A space-saving picker section: a tappable header (the [SectionLabel] plus the
 * current value and a chevron) that collapses its option rows by default and
 * expands them on tap — a dropdown for the agent sheet's Profile / Personality
 * / Model lists so the sheet doesn't render every option at once. Selecting an
 * option (inside [content]) updates [currentValue] in the header; callers may
 * collapse on select by toggling their own state if desired, but leaving it
 * open lets the user see the new selection land.
 */
@Composable
private fun CollapsiblePickerSection(
    title: String,
    hint: String?,
    currentValue: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val collapseDesc = stringResource(R.string.conn_info_collapse, title)
    val expandDesc = stringResource(R.string.conn_info_expand, title)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(appearanceRoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SectionLabel(title = title, hint = hint)
            }
            if (!expanded && currentValue.isNotBlank()) {
                Text(
                    text = currentValue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 150.dp)
                        .padding(end = 8.dp),
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) collapseDesc else expandDesc,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun DisplayAliasSection(
    currentAlias: String?,
    fallbackName: String,
    onSave: (String?) -> Unit,
) {
    var draft by remember { mutableStateOf(currentAlias.orEmpty()) }
    LaunchedEffect(currentAlias) {
        draft = currentAlias.orEmpty()
    }
    val normalizedDraft = AgentDisplay.localDisplayAlias(draft)
    val hasChange = normalizedDraft != currentAlias

    val localDisplayNameTitle = stringResource(R.string.conn_info_local_display_name)
    val phoneLabelHint = stringResource(R.string.conn_info_phone_label)
    val notSetValue = stringResource(R.string.conn_info_not_set)
    val nameLabel = stringResource(R.string.conn_info_name)
    val clearText = stringResource(R.string.conn_info_clear)
    val saveText = stringResource(R.string.conn_info_save)

    CollapsiblePickerSection(
        title = localDisplayNameTitle,
        hint = phoneLabelHint,
        currentValue = currentAlias ?: notSetValue,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(nameLabel) },
            placeholder = { Text(fallbackName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    draft = ""
                    onSave(null)
                },
                enabled = currentAlias != null,
            ) {
                Text(clearText)
            }
            OutlinedButton(
                onClick = { onSave(normalizedDraft) },
                enabled = hasChange,
            ) {
                Text(saveText)
            }
        }
    }
}

/**
 * Radio-style row used by both Profile and Personality sections. Whole row
 * is a tap target (and selectable() for a11y). Disabled when [enabled] is
 * false — look and behaviour both propagate the gate.
 *
 * Layout hierarchy inside the text column:
 *   1. [primary]   — the name, on its own line.
 *   2. [secondary] — a metadata/description line. When [secondaryExpandable]
 *      is set it truncates to [SECONDARY_COLLAPSED_LINES] with an ellipsis and
 *      becomes tap-to-expand, so a long description (e.g. "Sentinel —
 *      Infrastructure / Security / Reliability · gpt-5.5") can never push the
 *      badges off-screen or crunch them.
 *   3. [secondaryTrailing] badges — rendered in a FlowRow on their OWN line
 *      below the description, so WHOLE pills wrap to the next line rather than
 *      the badge text wrapping internally ("141\nskills" / vertical "S O U L").
 *   4. [tertiary]  — an optional caption.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileRadioRow(
    primary: String,
    secondary: String?,
    tertiary: String? = null,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    /**
     * Extra alpha multiplier applied to the whole row on top of the
     * disabled-state dimming. Used by the Profile section to hint that a
     * gateway-off profile is selectable-but-probably-stale (50%). 1f is
     * neutral.
     */
    contentAlpha: Float = 1f,
    /**
     * Optional status dot rendered between the RadioButton and the text
     * column. 6 dp circle; hide by leaving null.
     */
    leadingDotColor: Color? = null,
    /**
     * Optional accessibility content description for the leading status
     * dot. Screen readers announce this string so users don't have to
     * rely on the dot's colour to know the profile's runtime state.
     * Pass e.g. "Gateway running" / "Gateway idle". Null skips the
     * a11y announcement (and renders the dot as a decoration-only).
     */
    leadingDotContentDescription: String? = null,
    /**
     * When true the [secondary] line is treated as a potentially-long
     * description: it truncates to [SECONDARY_COLLAPSED_LINES] with an
     * ellipsis and gains a tap-to-expand affordance (progressive disclosure)
     * so the full text is reachable without crunching the badges. Default
     * false keeps the short single-line caption behaviour every other caller
     * relies on.
     */
    secondaryExpandable: Boolean = false,
    /**
     * Optional trailing chip/badge slot. Rendered in a FlowRow on its own
     * line BELOW the [secondary] description (not inline with it), so whole
     * badges wrap gracefully and never compete with the description for
     * width. Slot-based so each call site composes its own badges.
     */
    secondaryTrailing: (@Composable () -> Unit)? = null,
) {
    val rowAlpha = (if (enabled) 1f else 0.5f) * contentAlpha
    // Local expand state for a long [secondary] description. Only consulted
    // when [secondaryExpandable]; tapping the description toggles it. Scoped
    // to this row's identity so a list re-order doesn't carry the flag across.
    var descriptionExpanded by remember(primary, secondary) { mutableStateOf(false) }
    // Whether the collapsed description is actually being clipped — drives the
    // "More" affordance so a short description that fits doesn't get a pointless
    // expand link. Reported by the Text's onTextLayout below.
    var descriptionOverflows by remember(primary, secondary) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(appearanceRoundedCornerShape(8.dp))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .alpha(rowAlpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        if (leadingDotColor != null) {
            Spacer(modifier = Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(leadingDotColor)
                    .then(
                        if (leadingDotContentDescription != null) {
                            Modifier.semantics {
                                contentDescription = leadingDotContentDescription
                            }
                        } else {
                            Modifier
                        }
                    ),
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = primary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Description line on its own row. When expandable, truncate +
            // make the text a tap-to-toggle target so the full description is
            // reachable without pushing the badges off-screen. The whole-row
            // selectable() still drives selection; this inner clickable only
            // toggles disclosure, so a tap on the description text expands it
            // rather than selecting the row.
            if (secondary != null) {
                val collapsed = secondaryExpandable && !descriptionExpanded
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (collapsed) SECONDARY_COLLAPSED_LINES else Int.MAX_VALUE,
                    overflow = if (collapsed) TextOverflow.Ellipsis else TextOverflow.Clip,
                    // Only flag overflow from the COLLAPSED measure — once
                    // expanded the text fits by definition, so don't clobber
                    // the flag (the "Show less" affordance still wants it true).
                    onTextLayout = { layout ->
                        if (collapsed) descriptionOverflows = layout.hasVisualOverflow
                    },
                    modifier = if (secondaryExpandable && enabled) {
                        Modifier.clickable { descriptionExpanded = !descriptionExpanded }
                    } else {
                        Modifier
                    },
                )
                // "More" / "Show less" affordance — only when the collapsed
                // description is actually clipped (or already expanded), so a
                // short description that fits gets no pointless expand link.
                if (secondaryExpandable && enabled && (descriptionOverflows || descriptionExpanded)) {
                    val showLessText = stringResource(R.string.conn_info_show_less)
                    val moreText = stringResource(R.string.conn_info_more)
                    Text(
                        text = if (descriptionExpanded) showLessText else moreText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            descriptionExpanded = !descriptionExpanded
                        },
                    )
                }
            }
            // Badges get their OWN line in a FlowRow so whole pills wrap to the
            // next line on a narrow row — never crunched, never split internally.
            if (secondaryTrailing != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    secondaryTrailing()
                }
            }
            if (tertiary != null) {
                Text(
                    text = tertiary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Single static, non-interactive row shown in place of the profile radio list
 * when the connection is locked to one profile. There is intentionally no
 * onSelect — the only way to change the target or unlock is the dedicated
 * "Profile lock" control in Settings, which always lists every profile.
 */
@Composable
private fun LockedProfileRow(lockedDisplayName: String) {
    val lockedToText = stringResource(R.string.conn_info_locked_to, lockedDisplayName)
    val manageLockHint = stringResource(R.string.conn_info_manage_lock_hint)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = lockedToText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = manageLockHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Lines a collapsed (truncated) [ProfileRadioRow] description shows before its
 *  tap-to-expand affordance reveals the rest. Two keeps the badge FlowRow on
 *  screen even when the description is long. */
private const val SECONDARY_COLLAPSED_LINES = 2

/**
 * Tiny pill rendered inline with a profile row's secondary line. Used for
 * "N skills" and "SOUL" indicators. Kept compact and visually subordinate
 * to the model name — ~labelSmall, rounded to the max, padding trimmed.
 */
@Composable
private fun ProfileMetadataBadge(
    text: String,
    background: Color,
    contentColor: Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        // A badge must read as one whole pill. maxLines=1 + softWrap=false keeps
        // "141 skills" / "SOUL" on a single line so a width crunch can never break
        // it into "141\nskills" or vertical "S O U L" — the FlowRow in
        // ProfileRadioRow wraps WHOLE pills to the next line instead.
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** A small spinner + label row used as a BOUNDED "list still loading" cue under
 *  a picker section whose section header is always present. */
@Composable
private fun PickerLoadingRow(text: String) {
    Row(
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Trailing control for a standard upstream gateway toggle (YOLO / Fast) that is
 * NEVER hidden:
 *  - not [available] (gateway unreachable / needs sign-in) → a cleanly disabled
 *    switch; the reason is shown once at the section level;
 *  - [available] but [value] still unknown (null):
 *     - [gatewayReady] → the value re-confirms from `session.info` on the user's
 *       NEXT message (it's reset on a new chat / profile switch), so instead of
 *       an indefinite spinner the placeholder says so honestly;
 *     - still probing (Unknown) → the "Checking…" spinner pose;
 *  - [available] with a confirmed [value] → the live switch.
 */
@Composable
private fun GatewayToggleControl(
    available: Boolean,
    gatewayReady: Boolean,
    value: Boolean?,
    enabled: Boolean,
    label: String,
    onChange: (Boolean) -> Unit,
) {
    if (!available) {
        Switch(checked = value == true, enabled = false, onCheckedChange = {})
        return
    }
    val confirmsOnNextMessage = stringResource(R.string.conn_info_confirms_on_next_message)
    val checkingText = stringResource(R.string.conn_info_checking)
    LoadedFadeIn(
        value = value,
        label = label,
        placeholder = {
            if (gatewayReady) {
                // Socket is up; the value is just unconfirmed for THIS draft.
                // Honest + subtle: tell the user it settles on their next turn
                // rather than spinning forever.
                Text(
                    text = confirmsOnNextMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = checkingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { enabledValue ->
        Switch(
            checked = enabledValue,
            enabled = enabled,
            onCheckedChange = onChange,
        )
    }
}

@Composable
private fun AgentSheetHeader(
    profile: Profile?,
    selectedPersonality: String,
    defaultPersonality: String,
    localDisplayAlias: String?,
    serverModelName: String,
    sessionModelName: String? = null,
    modelProviderLabel: String? = null,
    chatRuntimeStatus: ChatRuntimeStatus,
    isCustomized: Boolean,
) {
    val agentName = AgentDisplay.agentName(
        profile = profile,
        selectedPersonality = selectedPersonality,
        defaultPersonality = defaultPersonality,
        connectionLabel = null,
        localDisplayAlias = localDisplayAlias,
    )
    // Session model wins so it pairs with the (session-scoped) provider label;
    // falls back to the profile model, then the server default.
    val modelLabel = AgentDisplay.displayModelName(sessionModelName)
        ?: AgentDisplay.displayModelName(profile?.model)
        ?: AgentDisplay.displayModelName(serverModelName)
    // The global default — surfaced as a quiet caption only when THIS session
    // runs something different (the always-visible global-vs-session split).
    val serverDefaultLabel = AgentDisplay.displayModelName(serverModelName)
    val isConnected = chatRuntimeStatus is ChatRuntimeStatus.Connected
    val isConnecting = chatRuntimeStatus is ChatRuntimeStatus.Connecting
    val connectedText = stringResource(R.string.conn_info_connected)
    val connectingText = stringResource(R.string.conn_info_connecting)
    val disconnectedText = stringResource(R.string.conn_info_disconnected)
    val statusText = when {
        isConnected -> connectedText
        isConnecting -> connectingText
        else -> disconnectedText
    }
    val statusColor = when {
        isConnected -> MaterialTheme.colorScheme.primary
        isConnecting -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .then(
                    if (isCustomized && LocalAgentIconPath.current.isNullOrBlank()) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        )
                    } else Modifier
                )
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                AgentAvatarFace(
                    name = agentName,
                    letterStyle = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = agentName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            // Fade the model in when it's CONFIRMED (from /api/config), rather
            // than popping. While still connecting we show a skeleton (honest
            // "loading"); once connected with no model reported we render
            // nothing rather than claim a model we don't have.
            LoadedFadeIn(
                value = modelLabel?.takeIf { it.isNotBlank() },
                label = "agentSheetModel",
                placeholder = {
                    // Never collapse the model line — show a loading pose, not an
                    // empty slot (which reads as "no model").
                    RelaySkeletonLine(width = 104.dp, height = 12.dp)
                },
            ) { label ->
                Text(
                    text = modelProviderLabel?.takeIf { it.isNotBlank() }
                        ?.let { "$label · $it" } ?: label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // Global-vs-session split: when the session runs a different model
            // than the server's global default, name the default here so the
            // scope is obvious at a glance (matches a mid-session switch).
            if (modelLabel != null && serverDefaultLabel != null &&
                !modelLabel.equals(serverDefaultLabel, ignoreCase = true)
            ) {
                val serverDefaultModelText = stringResource(R.string.conn_info_server_default_model, serverDefaultLabel)
                Text(
                    text = serverDefaultModelText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                maxLines = 1,
            )
        }
    }
}
