package com.tans.tfiletransporter.ui.activity.filetransport

import android.util.Log
import com.tans.tadapter.adapter.DifferHandler
import com.tans.tadapter.recyclerviewutils.MarginDividerItemDecoration
import com.tans.tadapter.spec.SimpleAdapterSpec
import com.tans.tadapter.spec.plus
import com.tans.tadapter.spec.toAdapter
import com.tans.tfiletransporter.R
import com.tans.tfiletransporter.databinding.FileItemLayoutBinding
import com.tans.tfiletransporter.databinding.FolderItemLayoutBinding
import com.tans.tfiletransporter.databinding.RemoteDirFragmentBinding
import com.tans.tfiletransporter.file.*
import com.tans.tfiletransporter.ui.activity.BaseFragment
import com.tans.tfiletransporter.ui.activity.commomdialog.loadingDialog
import com.tans.tfiletransporter.ui.activity.commomdialog.showLoadingDialog
import com.tans.tfiletransporter.ui.activity.filetransport.activity.FileTransportScopeData
import com.tans.tfiletransporter.ui.activity.filetransport.activity.newRequestFilesShareWriterHandle
import com.tans.tfiletransporter.ui.activity.filetransport.activity.newRequestFolderChildrenShareWriterHandle
import com.tans.tfiletransporter.ui.activity.filetransport.activity.toFile
import com.tans.tfiletransporter.utils.dp2px
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.withLatestFrom
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.rxSingle
import kotlinx.coroutines.withContext
import org.kodein.di.instance
import java.util.*

data class RemoteDirState(
        val fileTree: Optional<FileTree> = Optional.empty(),
        val selectedFiles: Set<CommonFileLeaf> = emptySet()
)

class RemoteDirFragment : BaseFragment<RemoteDirFragmentBinding, RemoteDirState>(R.layout.remote_dir_fragment, RemoteDirState()) {

    private val fileTransportScopeData: FileTransportScopeData by instance()

    override fun onInit() {

        updateState {
            RemoteDirState(Optional.of(newRootFileTree(path = fileTransportScopeData.remoteDirSeparator)), emptySet())
        }.bindLife()

        bindState()
                .map { it.fileTree }
                .filter { it.isPresent }
                .map { it.get() }
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .flatMapSingle { oldTree ->
                    if (!oldTree.notNeedRefresh) {
                        rxSingle {
                            fileTransportScopeData.fileTransporter.writerHandleChannel.send(newRequestFolderChildrenShareWriterHandle(oldTree.path))
                            fileTransportScopeData.remoteFolderModelEvent.firstOrError()
                                    .flatMap { remoteFolder ->
                                        if (remoteFolder.path == oldTree.path) {
                                            updateState {
                                                val children: List<YoungLeaf> = remoteFolder.childrenFolders
                                                        .map {
                                                            DirectoryYoungLeaf(
                                                                    name = it.name,
                                                                    childrenCount = it.childCount,
                                                                    lastModified = it.lastModify.toInstant().toEpochMilli()
                                                            )
                                                        } + remoteFolder.childrenFiles
                                                        .map {
                                                            FileYoungLeaf(
                                                                    name = it.name,
                                                                    size = it.size,
                                                                    lastModified = it.lastModify.toInstant().toEpochMilli()
                                                            )
                                                        }
                                                RemoteDirState(Optional.of(children.refreshFileTree(oldTree)), emptySet())
                                            }.map {

                                            }.onErrorResumeNext {
                                                Log.e(this::class.qualifiedName, it.toString())
                                                Single.just(Unit)
                                            }
                                        } else {
                                            Single.just(Unit)
                                        }
                                    }.await()
                        }
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .loadingDialog(requireActivity())
                    } else {
                        Single.just(Unit)
                    }
                }
                .bindLife()

        render({ it.fileTree }) {
            binding.remotePathTv.text = if (it.isPresent) it.get().path else ""
        }.bindLife()

        binding.remoteFileFolderRv.adapter = (SimpleAdapterSpec<DirectoryFileLeaf, FolderItemLayoutBinding>(
                layoutId = R.layout.folder_item_layout,
                bindData = { _, data, binding -> binding.data = data },
                dataUpdater = bindState().map { if (it.fileTree.isPresent) it.fileTree.get().dirLeafs else emptyList() },
                differHandler = DifferHandler(
                        itemsTheSame = { a, b -> a.path == b.path },
                        contentTheSame = { a, b -> a == b }
                ),
                itemClicks = listOf { binding, _ ->
                    binding.root to { _, data ->
                        updateState { parentTree ->
                            RemoteDirState(Optional.of(data.newSubTree(parentTree.fileTree.get())), emptySet())
                        }.map { }
                    }
                }
        ) + SimpleAdapterSpec<Pair<CommonFileLeaf, Boolean>, FileItemLayoutBinding>(
                layoutId = R.layout.file_item_layout,
                bindData = { _, data, binding -> binding.data = data.first; binding.isSelect = data.second },
                dataUpdater = bindState().map { state -> state.fileTree.get().fileLeafs.map { it to state.selectedFiles.contains(it) } },
                differHandler = DifferHandler(
                        itemsTheSame = { a, b -> a.first.path == b.first.path },
                        contentTheSame = { a, b -> a == b },
                        changePayLoad = { d1, d2 ->
                            if (d1.first == d2.first && d1.second != d2.second) {
                                FileSelectChange
                            } else {
                                null
                            }
                        }
                ),
                bindDataPayload = { _, data, binding, payloads ->
                    if (payloads.contains(FileSelectChange)) {
                        binding.isSelect = data.second
                        true
                    } else {
                        false
                    }
                },
                itemClicks = listOf { binding, _ ->
                    binding.root to { _, (file, isSelect) ->
                        updateState { oldState ->
                            val selectedFiles = oldState.selectedFiles
                            oldState.copy(selectedFiles = if (isSelect) selectedFiles - file else selectedFiles + file)
                        }.map {  }
                    }
                }
        )).toAdapter()

        binding.remoteFileFolderRv.addItemDecoration(MarginDividerItemDecoration.Companion.Builder()
                .divider(MarginDividerItemDecoration.Companion.ColorDivider(requireContext().getColor(R.color.line_color),
                        requireContext().dp2px(1)))
                .marginStart(requireContext().dp2px(65))
                .build()
        )

        fileTransportScopeData.floatBtnEvent
                .withLatestFrom(bindState().map { it.selectedFiles })
                .map { it.second }
                .filter { !isHidden && it.isNotEmpty() }
                .observeOn(AndroidSchedulers.mainThread())
                .flatMapSingle {
                    rxSingle {
                        val dialog = withContext(Dispatchers.Main) { requireActivity().showLoadingDialog() }
                        withContext(Dispatchers.IO) {
                            fileTransportScopeData.fileTransporter.startWriterHandleWhenFinish(newRequestFilesShareWriterHandle(it.map { it.toFile() }))
                        }
                        updateState {
                            it.copy(selectedFiles = emptySet())
                        }.await()
                        withContext(Dispatchers.Main) { dialog.cancel() }
                    }
                }
                .bindLife()
    }

    override fun onBackPressed(): Boolean {
        return if (bindState().firstOrError().blockingGet().fileTree.get().isRootFileTree()) {
            false
        } else {
            updateState { state ->
                val parent = state.fileTree.get().parentTree
                if (parent != null) RemoteDirState(Optional.of(parent)) else state
            }.bindLife()
            true
        }
    }

    companion object {
        const val FRAGMENT_TAG = "remote_dir_fragment_tag"
    }
}