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

package androidx.compose.ui

import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.FlushCoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class ComposeSceneRecomposer(
    coroutineContext: CoroutineContext,
    vararg elements: CoroutineContext.Element
) {
    private val job = Job()
    private val coroutineScope = CoroutineScope(coroutineContext + job)

    // We use FlushCoroutineDispatcher for effectDispatcher not because we need `flush` for
    // LaunchEffect tasks, but because we need to know if it is idle (hasn't scheduled tasks)
    private val effectDispatcher = FlushCoroutineDispatcher(coroutineScope)
    private val recomposeDispatcher = FlushCoroutineDispatcher(coroutineScope)
    private val recomposer = Recomposer(coroutineContext + job + effectDispatcher)

    val hasPendingWork: Boolean
        get() = recomposer.hasPendingWork ||
        effectDispatcher.hasTasks() ||
        recomposeDispatcher.hasTasks()

    val compositionContext: CompositionContext
        get() = recomposer

    init {
        var context: CoroutineContext = recomposeDispatcher
        for (element in elements) {
            context += element
        }
        coroutineScope.launch(context,
            start = CoroutineStart.UNDISPATCHED
        ) {
            recomposer.runRecomposeAndApplyChanges()
        }
    }

    fun flush() {
        recomposeDispatcher.flush()
    }

    fun cancel() {
        recomposer.cancel()
        job.cancel()
    }
}