package com.ztune.libretune.ui.screens.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.git.TuneCommit
import com.ztune.libretune.core.git.TuneDiffResult
import com.ztune.libretune.core.git.TuneVersionControl
import com.ztune.libretune.core.tune.Tune
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 30: Converted to @HiltViewModel.
 *
 * Previously extended AndroidViewModel and constructed its own
 * TuneVersionControl instance, bypassing the Hilt-provided singleton.
 * Now injects the singleton via constructor injection.
 */
@HiltViewModel
class TuneHistoryViewModel @Inject constructor(
    private val vc: TuneVersionControl
) : ViewModel() {

    private val _commits = MutableStateFlow<List<TuneCommit>>(emptyList())
    val commits: StateFlow<List<TuneCommit>> = _commits.asStateFlow()

    private val _diffResult = MutableStateFlow<TuneDiffResult?>(null)
    val diffResult: StateFlow<TuneDiffResult?> = _diffResult.asStateFlow()

    private val _diffCommitMessage = MutableStateFlow("")
    val diffCommitMessage: StateFlow<String> = _diffCommitMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _restoredTune = MutableStateFlow<Tune?>(null)
    val restoredTune: StateFlow<Tune?> = _restoredTune.asStateFlow()

    init {
        loadCommits()
    }

    fun loadCommits() {
        viewModelScope.launch {
            _isLoading.value = true
            _commits.value = vc.listCommits()
            _isLoading.value = false
        }
    }

    fun showDiff(commitId: String, message: String, index: Int) {
        viewModelScope.launch {
            val next = index + 1
            val sortedCommits = _commits.value
            val otherId = sortedCommits.getOrNull(next)?.id ?: commitId
            _diffResult.value = vc.diff(otherId, commitId)
            _diffCommitMessage.value = message
        }
    }

    fun clearDiff() {
        _diffResult.value = null
        _diffCommitMessage.value = ""
    }

    fun restoreCommit(id: String) {
        viewModelScope.launch {
            _restoredTune.value = vc.restoreCommit(id)
        }
    }

    fun clearRestoredFlag() {
        _restoredTune.value = null
    }

    fun pruneCommits() {
        viewModelScope.launch {
            vc.pruneOldCommits()
            loadCommits()
        }
    }

    fun formatTimestamp(ts: Long): String = vc.formatTimestamp(ts)
    fun shortHash(hash: String): String = vc.shortHash(hash)
}
