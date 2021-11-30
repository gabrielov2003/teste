package cms.rendner.intellij.dataframe.viewer.pycharm.actions

import cms.rendner.intellij.dataframe.viewer.core.component.DataFrameTable
import cms.rendner.intellij.dataframe.viewer.core.component.models.IDataFrameModel
import cms.rendner.intellij.dataframe.viewer.notifications.UserNotifier
import cms.rendner.intellij.dataframe.viewer.pycharm.dataframe.models.TableModelFactory
import cms.rendner.intellij.dataframe.viewer.pycharm.evaluator.exceptions.EvaluateException
import cms.rendner.intellij.dataframe.viewer.pycharm.extensions.isDataFrame
import cms.rendner.intellij.dataframe.viewer.pycharm.extensions.isStyler
import cms.rendner.intellij.dataframe.viewer.pycharm.injector.PluginPythonCodeBridge
import cms.rendner.intellij.dataframe.viewer.pycharm.injector.PyPatchedStylerRef
import cms.rendner.intellij.dataframe.viewer.services.ParentDisposableService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.util.BackgroundTaskUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase
import com.jetbrains.python.debugger.PyDebugValue
import com.jetbrains.python.debugger.PyDebuggerException
import java.awt.Component
import java.awt.Dimension
import javax.swing.*

class ShowStyledDataFrameAction : AnAction(), DumbAware {

    companion object {
        private val logger = Logger.getInstance(ShowStyledDataFrameAction::class.java)
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.isEnabledAndVisible = event.project != null && getFrameOrStyler(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val frameOrStyler = getFrameOrStyler(event)
        if (frameOrStyler !== null) {
            val project: Project = XDebuggerTree.getTree(event.dataContext)!!.project
            val dialog = MyDialog(project)
            dialog.createModelFrom(frameOrStyler)
            dialog.title = "Styled DataFrame"
            dialog.show()
        }
    }

    private fun getFrameOrStyler(e: AnActionEvent): PyDebugValue? {
        val nodes = XDebuggerTreeActionBase.getSelectedNodes(e.dataContext)
        if (nodes.size == 1) {
            val container = nodes.first().valueContainer
            if (container is PyDebugValue) {
                if (container.isDataFrame() || container.isStyler()) {
                    return container
                }
            }
        }
        return null
    }

    private class MyDialog(project: Project) :
        DialogWrapper(project, false) {
        private val myDataFrameTable: DataFrameTable
        private val myStatusLabel = JLabel()
        private val myParentDisposable = project.service<ParentDisposableService>()
        private val myUserNotifier = UserNotifier(project)

        init {
            Disposer.register(myParentDisposable, disposable)

            isModal = false

            setOKButtonText("Close")
            // "Alt" + "c" triggers OK action (esc also closes the window)
            setOKButtonMnemonic('C'.toInt())

            setCrossClosesWindow(true)
            //setupChangeListener()

            myDataFrameTable = DataFrameTable()
            myDataFrameTable.preferredSize = Dimension(700, 500)

            init()
        }

        fun createModelFrom(frameOrStyler: PyDebugValue) {
            BackgroundTaskUtil.executeOnPooledThread(myParentDisposable) {
                var patchedStyler: PyPatchedStylerRef? = null
                var model: IDataFrameModel? = null
                try {
                    val pythonBridge = PluginPythonCodeBridge()
                    patchedStyler = pythonBridge.createPatchedStyler(frameOrStyler)

                    model = TableModelFactory.createChunkedModel(patchedStyler, myUserNotifier)

                    ApplicationManager.getApplication().invokeLater {
                        if (!Disposer.isDisposed(disposable)) {
                            Disposer.register(disposable, model)
                            Disposer.register(disposable, patchedStyler)
                            myDataFrameTable.setDataFrameModel(model)
                        } else {
                            patchedStyler.dispose()
                            model.dispose()
                        }
                    }
                } catch (ex: Exception) {
                    logger.error("Creating DataFrame model failed", ex)

                    val reason = "Reason: " + when (ex) {
                        is EvaluateException -> ex.userFriendlyMessage()
                        is PyDebuggerException -> ex.tracebackError
                        else -> ex.localizedMessage
                    }

                    myUserNotifier.error("Creating DataFrame model failed. $reason")

                    patchedStyler?.dispose()
                    model?.dispose()
                }
            }
        }

        /*
                private fun setupChangeListener() {
                    myFrameAccessor.addFrameListener {
                        println("MyDialog:FRAME:CHANGED")
                        ApplicationManager.getApplication().executeOnPooledThread { updateModel() }
                    }
                }

                private fun updateModel() {
                    val model = myDataFrameTable.model ?: return
                    //model.invalidateCache()
                    //updateDebugValue(model)
                    ApplicationManager.getApplication().invokeLater {
                        if (isShowing) {
                            //model.fireTableDataChanged()
                        }
                    }
                }
        */

        override fun createCenterPanel(): JComponent {
            val result = JPanel()
            result.layout = BoxLayout(result, BoxLayout.Y_AXIS)

            myStatusLabel.alignmentX = Component.LEFT_ALIGNMENT
            myDataFrameTable.alignmentX = Component.LEFT_ALIGNMENT

            result.add(myStatusLabel)
            result.add(myDataFrameTable)

            return result
        }

        override fun createActions(): Array<Action> {
            return arrayOf(okAction)
        }

        override fun getDimensionServiceKey(): String {
            return "#cms.rendner.intellij.dataframe.viewer.python.actions.ShowStyledDataFrameAction"
        }

        override fun getPreferredFocusedComponent(): JComponent {
            return myDataFrameTable
        }
    }
}