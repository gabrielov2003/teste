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
package cms.rendner.debugger.impl

import cms.rendner.intellij.dataframe.viewer.python.debugger.IPluginPyValueEvaluator

data class EvalOrExecRequest(
    val expression: String,
    val execute: Boolean,
    val trimResult: Boolean,
)

data class EvalOrExecResponse(
    // the evaluated result
    val value: String? = null,
    // the type of the evaluated value
    val type: String? = null,
    val typeQualifier: String? = null,
    // true if an error occurred during evaluation
    val isError: Boolean = false,
    // a unique id to refer to the evaluated var on python side
    val refId: String? = null,
)

interface IDebuggerInterceptor {
    fun onRequest(request: EvalOrExecRequest): EvalOrExecRequest { return request }
    fun onResponse(response: EvalOrExecResponse): EvalOrExecResponse { return response }
}

interface IPythonDebuggerApi {
    val evaluator: IPluginPyValueEvaluator
    fun continueFromBreakpoint()
    fun addInterceptor(interceptor: IDebuggerInterceptor)
    fun removeInterceptor(interceptor: IDebuggerInterceptor)
}