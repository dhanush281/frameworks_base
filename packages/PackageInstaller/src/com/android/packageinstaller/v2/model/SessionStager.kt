/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.model

import android.content.Context
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.packageinstaller.common.PackageBundleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SessionStager internal constructor(
    private val context: Context,
    private val uri: Uri,
    private val mimeType: String?,
    private val stagedSessionId: Int
) {

    companion object {
        private val LOG_TAG = SessionStager::class.java.simpleName
    }

    private val _progress = MutableLiveData(0)
    val progress: LiveData<Int>
        get() = _progress

    @Volatile
    var isRunning = false

    suspend fun execute(): PackageBundleHelper.BundleStageResult = withContext(Dispatchers.IO) {
        isRunning = true
        val pi: PackageInstaller = context.packageManager.packageInstaller
        var sessionInfo: PackageInstaller.SessionInfo?
        var stageResult = PackageBundleHelper.BundleStageResult(
            success = false,
            isBundle = false,
            hasObb = false,
            obbSizeBytes = 0L,
        )
        try {
            pi.openSession(stagedSessionId).use { session ->
                stageResult = PackageBundleHelper.stageUriIntoSession(
                    context = context,
                    uri = uri,
                    mimeType = mimeType,
                    session = session,
                    cancelCheck = { isRunning },
                    progressCallback = { value -> _progress.postValue(value) },
                )
                sessionInfo = if (stageResult.success) {
                    pi.getSessionInfo(stagedSessionId)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error staging apk from content URI", e)
            sessionInfo = null
        }

        return@withContext if (sessionInfo == null
            || !sessionInfo?.isActive!!
            || sessionInfo?.resolvedBaseApkPath == null
        ) {
            Log.w(LOG_TAG, "Session info is invalid: $sessionInfo")
            stageResult.copy(success = false)
        } else {
            stageResult
        }
    }

    fun cancel() {
        isRunning = false
    }
}
