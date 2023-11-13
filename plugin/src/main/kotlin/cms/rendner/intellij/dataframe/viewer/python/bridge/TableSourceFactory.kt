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
package cms.rendner.intellij.dataframe.viewer.python.bridge

import cms.rendner.intellij.dataframe.viewer.models.chunked.*
import cms.rendner.intellij.dataframe.viewer.python.bridge.exceptions.CreateTableSourceException
import cms.rendner.intellij.dataframe.viewer.python.bridge.providers.TableSourceFactoryImport
import cms.rendner.intellij.dataframe.viewer.python.bridge.providers.TableSourceKind
import cms.rendner.intellij.dataframe.viewer.python.debugger.IPluginPyValueEvaluator
import cms.rendner.intellij.dataframe.viewer.python.debugger.PluginPyValue
import cms.rendner.intellij.dataframe.viewer.python.debugger.exceptions.EvaluateException
import cms.rendner.intellij.dataframe.viewer.python.utils.stringifyBool
import cms.rendner.intellij.dataframe.viewer.python.utils.stringifyImportWithObjectRef
import cms.rendner.intellij.dataframe.viewer.python.utils.stringifyMethodCall
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json: Json by lazy { Json { ignoreUnknownKeys = true } }

/**
 * Creates [IPyTableSourceRef] instances to enable "communication" with pandas objects.
 */
class TableSourceFactory {

    companion object {
        fun create(
            evaluator: IPluginPyValueEvaluator,
            tableSourceFactoryImport: TableSourceFactoryImport,
            dataSourceRefExpr: String,
            config: CreateTableSourceConfig? = null,
        ): IPyTableSourceRef {
            val constructorRef = stringifyImportWithObjectRef(tableSourceFactoryImport.packageName, tableSourceFactoryImport.className)
            val tableSource = evaluator.evaluate(
                stringifyMethodCall("$constructorRef()", "create") {
                    refParam(dataSourceRefExpr)
                    if (config == null) noneParam() else refParam(json.encodeToString(config))
                }
            )

            if (tableSource.qualifiedType == "builtins.str") {
                val info: CreateTableSourceFailure =
                    json.decodeFromString(tableSource.forcedValue.removeSurrounding("\""))
                throw CreateTableSourceException(info)
            }

            val sourceKind: TableSourceKind = tableSourceFactoryImport.tableSourceKind ?: run {
                when(val kind = evaluator.evaluate("${tableSource.refExpr}.get_kind().name").forcedValue) {
                    "TABLE_SOURCE" -> TableSourceKind.TABLE_SOURCE
                    "PATCHED_STYLER" -> TableSourceKind.PATCHED_STYLER
                    else -> throw CreateTableSourceException(
                        CreateTableSourceFailure(
                            CreateTableSourceErrorKind.UNSUPPORTED_DATA_SOURCE_TYPE,
                            "Unsupported table source type '${tableSource.qualifiedType}' (kind:$kind)",
                        )
                    )
                }
            }

            return when (sourceKind) {
                TableSourceKind.TABLE_SOURCE -> PyTableSourceRef(tableSource)
                TableSourceKind.PATCHED_STYLER -> PyPatchedStylerRef(tableSource)
            }
        }
    }

    private open class PyTableSourceRef(val pythonValue: PluginPyValue): IPyTableSourceRef {
        @Throws(EvaluateException::class)
        override fun evaluateTableStructure(): TableStructure {
            return fetchResultAsJsonAndDecode(
                stringifyMethodCall(pythonValue.refExpr, "get_table_structure")
            )
        }

        override fun evaluateSetSortCriteria(sortCriteria: SortCriteria?) {
            pythonValue.evaluator.evaluate(
                stringifyMethodCall(pythonValue.refExpr, "set_sort_criteria") {
                    sortCriteria?.byIndex.let {
                        if (it.isNullOrEmpty()) noneParam() else refParam(it.toString())
                    }
                    sortCriteria?.ascending.let {
                        if (it.isNullOrEmpty()) noneParam() else refParam(it.map { e -> stringifyBool(e) }.toString())
                    }
                }
            )
        }

        override fun evaluateComputeChunkTableFrame(
            chunk: ChunkRegion,
            excludeRowHeader: Boolean,
            excludeColumnHeader: Boolean
        ): TableFrame {
            return fetchResultAsJsonAndDecode(
                stringifyMethodCall(pythonValue.refExpr, "compute_chunk_table_frame") {
                    numberParam(chunk.firstRow)
                    numberParam(chunk.firstColumn)
                    numberParam(chunk.numberOfRows)
                    numberParam(chunk.numberOfColumns)
                    boolParam(excludeRowHeader)
                    boolParam(excludeColumnHeader)
                }
            )
        }

        override fun evaluateGetOrgIndicesOfVisibleColumns(partStart: Int, maxColumns: Int): List<Int> {
            return fetchResultAsJsonAndDecode(
                stringifyMethodCall(pythonValue.refExpr, "get_org_indices_of_visible_columns") {
                    numberParam(partStart)
                    numberParam(maxColumns)
                }
            )
        }

        protected inline fun <reified T> fetchResultAsJsonAndDecode(methodCallExpr: String): T {
            return pythonValue.evaluator.evaluate("${pythonValue.refExpr}.jsonify($methodCallExpr)").forcedValue.let {
                json.decodeFromString(it.removeSurrounding("\""))
            }
        }
    }

    /**
     * Kotlin implementation of the "PatchedStyler" Python class.
     * All calls are forwarded to the referenced Python instance. Therefore, the method signatures of
     * the Python class "PatchedStyler" have to match with the ones used by this class.
     *
     * @param pythonValue the wrapped "PatchedStyler" Python instance.
     */
    private class PyPatchedStylerRef(pythonValue: PluginPyValue) : PyTableSourceRef(pythonValue), IPyPatchedStylerRef {

        @Throws(EvaluateException::class)
        override fun evaluateStyleFunctionInfo(): List<StyleFunctionInfo> {
            return fetchResultAsJsonAndDecode(
                stringifyMethodCall(pythonValue.refExpr, "get_style_function_info")
            )
        }

        override fun evaluateValidateStyleFunctions(
            chunk: ChunkRegion,
            validationStrategy: ValidationStrategyType,
        ): List<StyleFunctionValidationProblem> {
            if (validationStrategy == ValidationStrategyType.DISABLED) return emptyList()
            return fetchResultAsJsonAndDecode(
                stringifyMethodCall(pythonValue.refExpr, "validate_style_functions") {
                    numberParam(chunk.firstRow)
                    numberParam(chunk.firstColumn)
                    numberParam(chunk.numberOfRows)
                    numberParam(chunk.numberOfColumns)
                    stringParam(validationStrategy.name)
                }
            )
        }
    }
}