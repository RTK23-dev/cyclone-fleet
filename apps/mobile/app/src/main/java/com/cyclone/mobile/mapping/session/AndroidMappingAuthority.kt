package com.cyclone.mobile.mapping.session

import android.content.Context
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.runtime.background.WorkspaceRuntime as VirtualDisplayWorkspaceRuntime
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.InputOwner
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import com.cyclone.mobile.runtime.session.SessionPlane
import com.cyclone.mobile.runtime.session.SessionPlaneKind
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
import org.json.JSONObject

/**
 * Adapter over Cyclone's existing authority primitives. It does not create another input lock.
 */
class AndroidMappingAuthority(
    context: Context,
) : MappingAuthority {
    private val appContext = context.applicationContext

    override fun acquire(request: MappingPlaneRequest): MappingControlLease {
        val plane = classify(request)
        return when (plane.kind) {
            SessionPlaneKind.FOREGROUND -> acquireForeground(plane)
            SessionPlaneKind.SESSION_KERNEL_VD -> acquireNamedVirtualDisplay(plane, request.executionGeneration)
            SessionPlaneKind.LAYER2_WORKSPACE -> acquireLayer2(plane)
        }
    }

    override fun revalidate(lease: MappingControlLease) {
        when (lease.plane.kind) {
            SessionPlaneKind.FOREGROUND -> {
                requireForegroundSession(lease.plane)
                requireAgentController()
                if (DeviceState.controllerEpoch() != lease.controlRevision) {
                    throw MappingSessionException(
                        "STALE_CONTROL_REVISION",
                        "Foreground control changed after the mapping lease was acquired.",
                    )
                }
            }
            SessionPlaneKind.SESSION_KERNEL_VD -> {
                val session = requireNamedSession(lease.plane)
                if (session.inputOwner != InputOwner.CYCLONE ||
                    !VirtualDisplayWorkspaceRuntime.ownsInput(lease.plane.sessionId)
                ) {
                    throw MappingSessionException("HUMAN_HAS_CONTROL", "Named workspace input is not owned by Cyclone.")
                }
                val generation = runCatching {
                    VirtualDisplayWorkspaceRuntime.generation(lease.plane.sessionId)
                }.getOrElse {
                    throw MappingSessionException("SESSION_REQUIRED", "Named workspace session is no longer available.")
                }
                if (generation != lease.controlRevision || lease.executionGeneration != generation) {
                    throw MappingSessionException(
                        "STALE_CONTROL_REVISION",
                        "Named workspace generation changed; acquire fresh mapping authority.",
                    )
                }
            }
            SessionPlaneKind.LAYER2_WORKSPACE -> {
                Layer2Workspaces.initialize(appContext)
                requireAgentController()
                val holder = Layer2Workspaces.engine.holder()
                    ?: throw MappingSessionException("HUMAN_HAS_CONTROL", "Layer-2 input is not currently leased to Cyclone.")
                val expectedId = lease.plane.workspaceId
                val expectedGeneration = lease.plane.workspaceGeneration
                if (holder.workspaceId != expectedId || holder.generation != expectedGeneration ||
                    holder.generation != lease.controlRevision
                ) {
                    throw MappingSessionException(
                        "STALE_CONTROL_REVISION",
                        "Layer-2 workspace generation changed; acquire fresh mapping authority.",
                    )
                }
            }
        }
    }

    private fun classify(request: MappingPlaneRequest): SessionPlane {
        val json = JSONObject()
            .put("sessionId", request.sessionId)
            .put("displayId", request.displayId)
        request.workspaceId?.let { json.put("workspaceId", it) }
        request.workspaceGeneration?.let { json.put("workspaceGeneration", it) }
        return try {
            SessionContract.requireUi(json)
        } catch (error: SessionIdentityException) {
            throw MappingSessionException(error.errorClass, error.message ?: "Invalid mapping session identity.")
        } catch (error: IllegalArgumentException) {
            throw MappingSessionException("SESSION_DISPLAY_MISMATCH", error.message ?: "Invalid mapping session identity.")
        }
    }

    private fun acquireForeground(plane: SessionPlane): MappingControlLease {
        requireForegroundSession(plane)
        requireAgentController()
        return MappingControlLease(
            plane = plane,
            controlRevision = DeviceState.controllerEpoch(),
        )
    }

    private fun acquireNamedVirtualDisplay(
        plane: SessionPlane,
        requestedGeneration: Long?,
    ): MappingControlLease {
        val session = requireNamedSession(plane)
        if (session.inputOwner != InputOwner.CYCLONE ||
            !VirtualDisplayWorkspaceRuntime.ownsInput(plane.sessionId)
        ) {
            throw MappingSessionException("HUMAN_HAS_CONTROL", "Named workspace input is not owned by Cyclone.")
        }
        val currentGeneration = runCatching {
            VirtualDisplayWorkspaceRuntime.generation(plane.sessionId)
        }.getOrElse {
            throw MappingSessionException("SESSION_REQUIRED", "Named workspace session is no longer available.")
        }
        if (requestedGeneration == null || requestedGeneration != currentGeneration) {
            throw MappingSessionException(
                "STALE_CONTROL_REVISION",
                "mapping.start requires the current executionGeneration for a named workspace.",
            )
        }
        return MappingControlLease(
            plane = plane,
            controlRevision = currentGeneration,
            executionGeneration = currentGeneration,
        )
    }

    private fun acquireLayer2(plane: SessionPlane): MappingControlLease {
        Layer2Workspaces.initialize(appContext)
        requireAgentController()
        val holder = Layer2Workspaces.engine.holder()
            ?: throw MappingSessionException("HUMAN_HAS_CONTROL", "Layer-2 input is not currently leased to Cyclone.")
        if (holder.workspaceId != plane.workspaceId) {
            throw MappingSessionException(
                "STALE_CONTROL_REVISION",
                "A different Layer-2 workspace currently owns display 0.",
            )
        }
        if (holder.generation != plane.workspaceGeneration) {
            throw MappingSessionException(
                "STALE_CONTROL_REVISION",
                "Layer-2 workspaceGeneration is stale.",
            )
        }
        return MappingControlLease(
            plane = plane,
            controlRevision = holder.generation,
        )
    }

    private fun requireForegroundSession(plane: SessionPlane) {
        if (plane.sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID || plane.displayId != 0) {
            throw MappingSessionException(
                "SESSION_DISPLAY_MISMATCH",
                "Foreground mapping must use default-foreground/display 0.",
            )
        }
        try {
            LiveVisionRuntime.sessions.requireSessionDisplay(plane.sessionId, plane.displayId)
        } catch (error: SessionIdentityException) {
            throw MappingSessionException(
                if (error.message.orEmpty().contains("unknown session", ignoreCase = true)) {
                    "SESSION_REQUIRED"
                } else {
                    "SESSION_DISPLAY_MISMATCH"
                },
                error.message ?: "Foreground session is unavailable.",
            )
        }
    }

    private fun requireNamedSession(plane: SessionPlane) = try {
        LiveVisionRuntime.sessions.requireSessionDisplay(plane.sessionId, plane.displayId)
    } catch (error: SessionIdentityException) {
        throw MappingSessionException(
            if (error.message.orEmpty().contains("unknown session", ignoreCase = true)) {
                "SESSION_REQUIRED"
            } else {
                "SESSION_DISPLAY_MISMATCH"
            },
            error.message ?: "Named workspace session is unavailable.",
        )
    }

    private fun requireAgentController() {
        if (DeviceState.controller != DeviceState.Controller.AGENT) {
            throw MappingSessionException("HUMAN_HAS_CONTROL", "Human/companion currently owns phone input.")
        }
    }
}
