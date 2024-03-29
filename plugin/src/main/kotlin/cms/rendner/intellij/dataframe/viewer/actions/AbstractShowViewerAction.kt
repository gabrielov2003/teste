/*
 * Copyright 2021-2024 cms.rendner (Daniel Schmidt)
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
package cms.rendner.intellij.dataframe.viewer.actions

import cms.rendner.intellij.dataframe.viewer.components.DataFrameViewerDialog
import cms.rendner.intellij.dataframe.viewer.notifications.ErrorNotification
import cms.rendner.intellij.dataframe.viewer.python.bridge.DataSourceTransformHint
import cms.rendner.intellij.dataframe.viewer.python.bridge.PythonPluginCodeInjector
import cms.rendner.intellij.dataframe.viewer.python.bridge.providers.ITableSourceCodeProvider
import cms.rendner.intellij.dataframe.viewer.python.pycharm.debugProcessIsTerminated
import cms.rendner.intellij.dataframe.viewer.python.pycharm.isConsole
import cms.rendner.intellij.dataframe.viewer.python.pycharm.toPluginType
import cms.rendner.intellij.dataframe.viewer.python.pycharm.toValueEvalExpr
import cms.rendner.intellij.dataframe.viewer.services.ParentDisposableService
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.util.BackgroundTaskUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerUtil
import com.jetbrains.python.debugger.*

private class MySelectCodeProviderAction(
    val provider: ITableSourceCodeProvider,
    val transformHint: DataSourceTransformHint?,
) : AbstractShowViewerAction() {
    override fun getApplicableCodeProvider(event: AnActionEvent, dataSource: PyDebugValue) = provider
    override fun getDataSourceTransformHint() = transformHint
    override fun update(e: AnActionEvent) {
        e.presentation.text = provider.getDataFrameLibrary().moduleName
    }
}

/**
 * Opens a popup in case more than one matching [ITableSourceCodeProvider] are available to transform a data source
 * into a viewable data frame. The user can select the preferred code provider from the popup.
 */
abstract class BaseShowViewerAction : AbstractShowViewerAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val dataSource = getSelectedDebugValue(event) ?: return

        val providers = getApplicableCodeProviders(event, dataSource)
        if (providers.size > 1) {
            runInEdt { showSelectCodeProviderPopup(event, providers) }
            return
        }

        super.actionPerformed(event)
    }

    protected abstract fun getApplicableCodeProviders(event: AnActionEvent, dataSource: PyDebugValue): List<ITableSourceCodeProvider>

    final override fun getApplicableCodeProvider(event: AnActionEvent, dataSource: PyDebugValue): ITableSourceCodeProvider? {
        return getApplicableCodeProviders(event, dataSource).firstOrNull()
    }

    private fun showSelectCodeProviderPopup(event: AnActionEvent, providers: List<ITableSourceCodeProvider>) {
        val transformHint = getDataSourceTransformHint()
        JBPopupFactory.getInstance().createActionGroupPopup(
            "Select DataFrame Library",
            DefaultActionGroup(providers.map { MySelectCodeProviderAction(it, transformHint) }),
            event.dataContext,
            JBPopupFactory.ActionSelectionAid.NUMBERING,
            false,
            null,
        ).also {
            event.project?.let { p -> it.showCenteredInCurrentWindow(p) } ?: it.showInBestPositionFor(event.dataContext)
        }
    }
}

/**
 * Opens the "DataFrameViewerDialog" from the PyCharm debugger.
 */
abstract class AbstractShowViewerAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    protected abstract fun getApplicableCodeProvider(event: AnActionEvent, dataSource: PyDebugValue): ITableSourceCodeProvider?

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        if (project.isDisposed) return
        val dataSource = getSelectedDebugValue(event) ?: return

        val parentDisposable = project.service<ParentDisposableService>()
        val evaluator = dataSource.toPluginType().evaluator
        val codeProvider = getApplicableCodeProvider(event, dataSource)
            ?: throw IllegalStateException("No codeProvider found for ${dataSource.qualifiedType}")

        BackgroundTaskUtil.executeOnPooledThread(parentDisposable) {
            val dataSourceInfo = try {
                PythonPluginCodeInjector.injectIfRequired(evaluator, codeProvider)
                codeProvider.createSourceInfo(dataSource.toValueEvalExpr(), evaluator)
            } catch (ex: Throwable) {
                ErrorNotification("Initialize plugin code failed", ex.localizedMessage ?: "", ex).notify(project)
                return@executeOnPooledThread
            }

            runInEdt {
                if (dataSource.frameAccessor.debugProcessIsTerminated() || parentDisposable.isDisposed || project.isDisposed) return@runInEdt
                DataFrameViewerDialog(
                    project,
                    evaluator,
                    dataSourceInfo.copy(filterable = dataSourceInfo.filterable && !dataSource.frameAccessor.isConsole()),
                    getDataSourceTransformHint()
                ).apply {
                    Disposer.register(parentDisposable, disposable)
                    Disposer.register(disposable, MyEdtAwareDebugSessionListener(dataSource.frameAccessor, this))
                    fetchInitialData()
                    show()
                }
            }
        }
    }

    protected open fun getDataSourceTransformHint(): DataSourceTransformHint? = null

    protected fun getSelectedDebugValue(event: AnActionEvent): PyDebugValue? {
        return XDebuggerUtil.getInstance().getValueContainer(event.dataContext)?.let {
            if (it is PyDebugValue) it else null
        }
    }
}

/**
 * The "PyFrameListener" had a breaking change in 2023.2.
 * "sessionStopped()" -> "sessionStopped(communication: PyFrameAccessor?)"
 *
 * This interface is used to make the plugin code compatible with
 * versions < 2023.2. and >= 2023.2.
 */
// todo: remove interface when setting min version for plugin >= 2023.2
interface PyFrameListenerBreakingChanges {
    fun sessionStopped()
    fun sessionStopped(communication: PyFrameAccessor?)
}

private class MyEdtAwareDebugSessionListener(
    frameAccessor: PyFrameAccessor,
    delegate: DataFrameViewerDialog,
    ): XDebugSessionListener, PyFrameListener, PyFrameListenerBreakingChanges, Disposable {

        // Props are nullable to release references.
        // Is required because "PyFrameAccessor" has no "removeFrameListener".
        private var frameAccessor: PyFrameAccessor? = frameAccessor
        private var delegate: DataFrameViewerDialog? = delegate

    init {
        if (frameAccessor is IPyDebugProcess) {
            delegate.setFilterSourcePosition(frameAccessor.session.currentPosition)
            frameAccessor.session.addSessionListener(this)
        } else {
            frameAccessor.addFrameListener(this)
        }
    }

    override fun frameChanged() = runInEdt {
        stackFrameChanged()
    }

    override fun sessionPaused() = runInEdt {
        delegate?.sessionPaused()
    }

    override fun sessionResumed() = runInEdt {
        delegate?.sessionResumed()
    }

    override fun sessionStopped() = runInEdt {
        delegate?.sessionStopped()
    }

    override fun sessionStopped(communication: PyFrameAccessor?) = runInEdt {
        if (communication == null || communication == frameAccessor) {
            delegate?.sessionStopped()
        }
    }

    override fun stackFrameChanged() = runInEdt {
        frameAccessor?.let {
            if (it is IPyDebugProcess) {
                delegate?.setFilterSourcePosition(it.session.currentPosition)
            }
        }

        delegate?.stackFrameChanged()
    }

    override fun beforeSessionResume() = runInEdt {
        delegate?.beforeSessionResume()
    }

    override fun dispose() {
        frameAccessor?.let {
            if (it is IPyDebugProcess) {
                it.session.removeSessionListener(this)
            }
        }

        frameAccessor = null
        delegate = null
    }
}