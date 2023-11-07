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
package cms.rendner.intellij.dataframe.viewer.python.bridge.providers

import cms.rendner.intellij.dataframe.viewer.python.bridge.providers.pandas.DataFrameCodeProvider
import cms.rendner.intellij.dataframe.viewer.python.bridge.providers.pandas.PatchedStylerCodeProvider

class TableSourceCodeProviderRegistry {
    companion object {
        private val myProviders: List<ITableSourceCodeProvider> = listOf(
            DataFrameCodeProvider(),
            PatchedStylerCodeProvider(),
        )

        fun getApplicableProvider(qName: String): ITableSourceCodeProvider? {
            if (qName.isEmpty()) return null
            return myProviders.firstOrNull { it.isApplicable(qName) }
        }

        fun getProviders(): List<ITableSourceCodeProvider> = myProviders
    }
}