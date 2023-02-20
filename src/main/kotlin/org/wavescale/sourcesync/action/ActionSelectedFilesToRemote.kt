package org.wavescale.sourcesync.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.project.stateStore
import com.intellij.ui.ExperimentalUI
import org.wavescale.sourcesync.SourceSyncIcons
import org.wavescale.sourcesync.api.FileSynchronizer
import org.wavescale.sourcesync.api.SynchronizationQueue
import org.wavescale.sourcesync.api.Utils
import org.wavescale.sourcesync.factory.ConfigConnectionFactory
import org.wavescale.sourcesync.factory.ConnectionConfig
import org.wavescale.sourcesync.logger.BalloonLogger
import org.wavescale.sourcesync.logger.EventDataLogger
import java.io.File
import java.util.concurrent.Semaphore

class ActionSelectedFilesToRemote : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        // first check if there's a connection type associated to this module.
        // If not alert the user and get out
        val project = e.project ?: return
        val projectName = project.name
        val associationName = ConnectionConfig.getInstance().getAssociationFor(projectName)
        if (associationName == null) {
            Utils.showNoConnectionSpecifiedError(projectName)
            return
        }

        // get a list of selected virtual files
        val virtualFiles = PlatformDataKeys.VIRTUAL_FILE_ARRAY.getData(e.dataContext)!!
        if (virtualFiles.isEmpty()) {
            val builder = StringBuilder("Project <b>")
            builder.append(e.project!!.name).append("</b>! does not have files selected!")
            BalloonLogger.logBalloonInfo(builder.toString(), e.project)
            EventDataLogger.logInfo(builder.toString(), e.project)
            return
        }

        // start sync
        val connectionConfiguration = ConfigConnectionFactory.getInstance().getConnectionConfiguration(associationName)
        val semaphores = Semaphore(connectionConfiguration.simultaneousJobs)
        val allowedSessions =
            if (virtualFiles.size <= connectionConfiguration.simultaneousJobs) virtualFiles.size else connectionConfiguration.simultaneousJobs
        val synchronizationQueue = SynchronizationQueue(e.project, connectionConfiguration, allowedSessions)
        synchronizationQueue.startCountingTo(virtualFiles.size)
        val queue = synchronizationQueue.syncQueue
        for (virtualFile in virtualFiles) {
            if (virtualFile != null && File(virtualFile.path).isFile) {
                if (Utils.canBeUploaded(virtualFile.name, connectionConfiguration.getExcludedFiles())) {
                    val uploadLocation = Utils.relativeLocalUploadDirs(virtualFile, project.stateStore)
                    ProgressManager.getInstance().run(object : Task.Backgroundable(e.project, "Uploading", false) {
                        override fun run(indicator: ProgressIndicator) {
                            var fileSynchronizer: FileSynchronizer?
                            try {
                                semaphores.acquire()
                                fileSynchronizer = queue.take()
                                fileSynchronizer!!.indicator = indicator
                                if (fileSynchronizer != null) {
                                    fileSynchronizer.connect()
                                    // so final destination will look like this:
                                    // root_home/ + project_relative_path_to_file/
                                }
                                fileSynchronizer.syncFile(virtualFile.path, uploadLocation)
                                queue.put(fileSynchronizer)
                                synchronizationQueue.count()
                            } catch (e1: InterruptedException) {
                                e1.printStackTrace()
                            } finally {
                                semaphores.release()
                            }
                        }
                    })
                } else {
                    if (virtualFile != null) {
                        EventDataLogger.logWarning("File <b>" + virtualFile.name + "</b> is filtered out!", e.project)
                        synchronizationQueue.count()
                    }
                }
            } else {
                if (virtualFile != null) {
                    EventDataLogger.logWarning("File <b>" + virtualFile.name + "</b> is a directory!", e.project)
                    synchronizationQueue.count()
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        if (ExperimentalUI.isNewUI()) {
            this.templatePresentation.icon = SourceSyncIcons.ExpUI.SOURCESYNC
        }

        val project = e.project ?: return
        val associationName = ConnectionConfig.getInstance().getAssociationFor(project.name)
        if (associationName != null) {
            templatePresentation.text = "Sync selected files to $associationName"
        } else {
            templatePresentation.text = "Sync selected files to Remote target"
        }
    }
}