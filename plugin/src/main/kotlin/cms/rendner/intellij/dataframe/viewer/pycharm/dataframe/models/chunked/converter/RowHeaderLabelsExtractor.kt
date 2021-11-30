package cms.rendner.intellij.dataframe.viewer.pycharm.dataframe.models.chunked.converter

import cms.rendner.intellij.dataframe.viewer.core.component.models.HeaderLabel
import cms.rendner.intellij.dataframe.viewer.core.component.models.IHeaderLabel
import cms.rendner.intellij.dataframe.viewer.core.component.models.LeveledHeaderLabel
import cms.rendner.intellij.dataframe.viewer.core.html.TableBodyRow
import com.intellij.util.SmartList

class RowHeaderLabelsExtractor {

    private var openParents: MutableList<SpannedHeader> = ArrayList()
    private var parentNames: List<String> = emptyList()
    private var shouldRebuildParentNames = false

    fun extract(bodyRows: List<TableBodyRow>): List<IHeaderLabel> {
        // the cache is used to reduce the amount of used lists
        val levelsCache = mutableMapOf<List<String>, List<String>>()
        return bodyRows.mapNotNull { row ->
            val headerRows = row.headers.filter { header -> header.hasClass(HeaderCssClasses.ROW_HEADING_CLASS.value) }
            when (headerRows.isEmpty()) {
                true -> null
                false -> {
                    val lastHeader = headerRows.lastOrNull()
                    headerRows.forEach { header ->
                        if (header != lastHeader) {
                            addParent(
                                header.text(),
                                header.attr("rowSpan").toIntOrNull() ?: 0
                            )
                        }
                    }
                    convertToHeaderLabel(lastHeader?.text() ?: "", levelsCache)
                }
            }
        }
    }

    private fun addParent(name: String, rowSpan: Int) {
        shouldRebuildParentNames = true
        openParents.add(SpannedHeader(name, rowSpan))
    }

    private fun convertToHeaderLabel(name: String, levelsCache: MutableMap<List<String>, List<String>>): IHeaderLabel {
        if (openParents.isEmpty()) return HeaderLabel(name)

        if (shouldRebuildParentNames) {
            shouldRebuildParentNames = false
            parentNames = SmartList(openParents.map { it.name })

            if (levelsCache.containsKey(parentNames)) {
                parentNames = levelsCache[parentNames]!!
            } else {
                levelsCache[parentNames] = parentNames
            }
        }

        val result = LeveledHeaderLabel(name, parentNames)
        decrementSpanOfOpenLevels()
        return result
    }

    private fun decrementSpanOfOpenLevels() {
        openParents.forEach { it.consume() }
        if (openParents.removeAll { !it.hasNext() }) {
            shouldRebuildParentNames = true
        }
    }

    data class SpannedHeader(val name: String, private var remainingSpanCount: Int) {
        fun consume() {
            remainingSpanCount--
        }

        fun hasNext() = remainingSpanCount > 0
    }
}