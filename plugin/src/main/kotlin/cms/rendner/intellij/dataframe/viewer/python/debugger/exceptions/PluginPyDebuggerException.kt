/*
 * Copyright 2021-2023 cms.rendner (Daniel Schmidt)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cms.rendner.intellij.dataframe.viewer.python.debugger.exceptions

import com.jetbrains.python.PyBundle

class PluginPyDebuggerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    fun isDisconnectException(): Boolean {
        // In PyCharm a "PyDebuggerException" with the message "Disconnected"
        // is thrown in case the debugger was disconnected.
        return message == "Disconnected"
    }

    fun isProcessIsRunningException(): Boolean {
        // In PyCharm a "PyDebuggerException" with the message "Process is running"
        // is thrown when XDebugSession::getCurrentStackFrame returns null in PyDebugProcess::currentFrame.
        // This can happen when jumping very fast between breakpoints while in the background data is evaluated.
        // In such a scenario the frame of the previous breakpoint is cleared and the new one is set after
        // the position of the new breakpoint is reached. Between these two breakpoints the frame is always
        // null and therefore no expression can be evaluated.
        return message == "Process is running" || message == PyBundle.message("debugger.debug.process.running")
    }
}