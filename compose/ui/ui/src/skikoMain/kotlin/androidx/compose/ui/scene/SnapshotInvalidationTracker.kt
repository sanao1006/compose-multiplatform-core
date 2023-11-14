/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.scene

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.node.OwnerSnapshotObserver

/**
 * SnapshotCommandList is a class that manages commands and invalidations for snapshot-based recomposition.
 * It allows postponing execution of commands and performing them in the future.
 *
 * @param onInvalidate a function that is called whenever an invalidation is requested
 */
internal class SnapshotInvalidationTracker(
    private val onInvalidate: () -> Unit = {}
) {
    private val snapshotChanges = CommandList(onInvalidate)
    private var needLayout = true
    private var needDraw = true

    val hasInvalidations: Boolean
        get() = needLayout || needDraw || snapshotChanges.hasCommands

    fun requestLayout() {
        needLayout = true
        onInvalidate()
    }

    fun onLayout() {
        // Apply changes from recomposition phase to layout phase
        sendAndPerformSnapshotChanges()

        needLayout = false
    }

    fun requestDraw() {
        needDraw = true
        onInvalidate()
    }

    fun onDraw() {
        // Apply changes from layout phase to draw phase
        sendAndPerformSnapshotChanges()

        needDraw = false
    }

    /**
     * Creates an observer for monitoring changes in the snapshot of an owner.
     *
     * @return the observer for monitoring snapshot changes
     */
    fun snapshotObserver() = OwnerSnapshotObserver { command ->
        snapshotChanges.add(command)
    }

    /**
     * Sends any pending apply notifications and performs the changes they cause.
     */
    fun sendAndPerformSnapshotChanges() {
        Snapshot.sendApplyNotifications()
        snapshotChanges.perform()
    }
}