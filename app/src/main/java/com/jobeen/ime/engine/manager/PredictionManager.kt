package com.jobeen.ime.engine.manager

import android.content.Context
import com.jobeen.ime.base.marisa.Prediction
import com.jobeen.ime.base.ngram.GramDb
import com.jobeen.ime.base.ngram.UserCollocationStore
import com.jobeen.ime.base.priority.CandidateFeature
import com.jobeen.ime.base.priority.PriorityCalculator
import com.jobeen.ime.base.priority.WeightConfig
import com.jobeen.ime.base.util.TextUtil
import com.jobeen.ime.data.manager.CandidatePreferCache
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File

class PredictionManager(private val context: Context) {

    private var prediction: Prediction? = null
    private var gramDb: GramDb? = null

    /**
     * 用户搭配学习（仅打字上屏）：与通用预测模型独立的个人搭配表，
     * 懒加载；存储异常在存储层内部吞掉，不影响预测主流程。
     */
    private val collocationStoreDelegate = lazy {
        UserCollocationStore(File(context.filesDir, "user_collocations.tsv"))
    }
    val collocationStore: UserCollocationStore by collocationStoreDelegate

    /** 学一对相邻搭配（非打字上屏路径不要调用）。 */
    fun learnCollocation(prev: String, next: String) {
        collocationStore.learn(prev, next)
    }

    /** 已加载的语法模型（供候选重排打分共用同一实例；未加载/已销毁时为 null） */
    fun gramDb(): GramDb? = gramDb
    private val calculator = PriorityCalculator()

    // 引入 Mutex 锁，防止并发重复加载导致冲突
    private val mutex = Mutex()

    suspend fun loadModels(modelDir: File, sharedDataDir: File, language: String?) =
        mutex.withLock {
            destroyLocked()

            //预测模型
            val predictGram = File(modelDir, "predict.marisa")
            if (predictGram.isFile) {
                prediction = Prediction(predictGram).apply { load() }
            }
            if (language.isNullOrEmpty()) {
                return@withLock
            }
            val gram = File(sharedDataDir, "$language.gram")
            if (gram.isFile) {
                gramDb = GramDb(gram.absolutePath)
            }
        }

    fun destroy() {
        // tryLock：拿不到锁说明正有 loadModels 在跑，旧模型即将被替换，跳过销毁是安全的；
        // 避免在 finalizer 线程 runBlocking 等锁
        if (mutex.tryLock()) {
            try {
                destroyLocked()
            } finally {
                mutex.unlock()
            }
        }
    }

    // 内部私有的安全销毁方法
    private fun destroyLocked() {
        prediction?.destroy()
        prediction = null
        gramDb = null
        if (collocationStoreDelegate.isInitialized()) collocationStore.flush()
    }

    /** 用户搭配候选：按个人计数排序，排在通用模型候选之前。 */
    private fun userCandidates(lastSegment: String?): List<Candidate> {
        if (lastSegment == null) return emptyList()
        val words = collocationStore.continuations(lastSegment)
        return words.mapIndexed { index, (word, _) ->
            Candidate(
                index = index,
                text = word,
                type = Candidate.TYPE_IME_PREDICTION,
                // 与模型分同尺度比较无意义：合并时用户候选整体置前，
                // 此分只用于用户候选内部保序（已按计数降序）
                score = (words.size - index).toDouble()
            )
        }
    }

    suspend fun makePredictions(inputContext: String, lastSegment: String? = null): List<Candidate> {
        val user = userCandidates(lastSegment)
        val model = modelPredictions(inputContext)
        if (user.isEmpty()) return model
        if (model.isEmpty()) return user.take(25)
        val seen = user.mapTo(HashSet()) { it.text }
        val merged = user + model.filter { it.text !in seen }
        return merged.take(25).mapIndexed { i, c -> c.copy(index = i) }
    }

    private suspend fun modelPredictions(inputContext: String): List<Candidate> {
        val pred = prediction ?: return emptyList()
        val possiables = TextUtil.contextSubstrings(inputContext)
        val cfg = WeightConfig()
        // 语法模型的上下文只随 inputContext 变：整轮预测算一次，
        // 逐候选复用，不再每个候选都重编码上下文并重走 trie
        val preparedCtx = gramDb?.prepareContext(inputContext)

        for (contextStr in possiables) {
            if (contextStr.isEmpty()) continue
            // 查询段持锁：防止与 loadModels/destroy 并发导致 native use-after-free
            val words = mutex.withLock { pred.predictNextWords(contextStr) }
            if (words.size >= 5) {
                val prefers = CandidatePreferCache.snapshot(context)
                val candidates = words.mapIndexed { index, it ->
                    val gramScore = preparedCtx?.let { ctx -> gramDb?.query(ctx, it.word) } ?: 0.0
                    val preferCount = prefers[it.word] ?: 0
                    val textLen = it.word.codePointCount(0, it.word.length)
                    val score = calculator.calculate(
                        CandidateFeature(
                            frequency = preferCount.toLong(),
                            wordLength = textLen,
                            candidateCount = 1,
                            baseScore = gramScore
                        ), cfg
                    )
                    Candidate(
                        index = index,
                        text = it.word,
                        type = Candidate.TYPE_IME_PREDICTION,
                        score = score
                    )
                }.sortedByDescending { it.score }

                return candidates.take(25)
            }
        }
        return emptyList()
    }
}