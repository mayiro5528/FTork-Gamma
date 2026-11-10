package com.ftork.gamma.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val id: Long,
    val source: String,
    val target: String,
    val text: String,
    val result: String,
    val saved: Boolean,
    val time: Long,
)

/**
 * 번역 기록 + 저장(별표) 목록을 앱 내부 파일(history.json)에 보관해요.
 * 원본 앱은 Room DB(LocalPhraseDatabase)를 써요. 연습 삼아 Room 으로 바꿔 볼 수 있어요.
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    suspend fun load() = mutex.withLock {
        _entries.value = withContext(Dispatchers.IO) { read() }
    }

    /**
     * 기록을 추가해요. 같은 언어쌍에서 방금(60초 안) 입력하던 글을 이어 쓴 경우에는
     * 새 줄을 만들지 않고 마지막 기록을 고쳐요. (한 글자마다 기록이 쌓이지 않게)
     * @return 기록의 id
     */
    suspend fun record(source: String, target: String, text: String, result: String): Long = mutex.withLock {
        val now = System.currentTimeMillis()
        val list = _entries.value.toMutableList()
        val last = list.firstOrNull()
        val id: Long
        if (last != null && last.source == source && last.target == target &&
            now - last.time < 60_000 &&
            (text.startsWith(last.text) || last.text.startsWith(text))
        ) {
            id = last.id
            list[0] = last.copy(text = text, result = result, time = now)
        } else {
            id = now
            list.add(0, HistoryEntry(id, source, target, text, result, saved = false, time = now))
        }
        // 저장 안 한 기록은 최근 200개까지만 둬요.
        var unsaved = 0
        val trimmed = list.filter { it.saved || ++unsaved <= 200 }
        commit(trimmed)
        id
    }

    suspend fun setSaved(id: Long, saved: Boolean) = mutex.withLock {
        commit(_entries.value.map { if (it.id == id) it.copy(saved = saved) else it })
    }

    suspend fun delete(id: Long) = mutex.withLock {
        commit(_entries.value.filterNot { it.id == id })
    }

    /** 기록 전체 삭제. 별표로 저장한 항목은 남겨요. */
    suspend fun clearUnsaved() = mutex.withLock {
        commit(_entries.value.filter { it.saved })
    }

    private suspend fun commit(list: List<HistoryEntry>) {
        _entries.value = list
        withContext(Dispatchers.IO) { write(list) }
    }

    private fun read(): List<HistoryEntry> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            HistoryEntry(
                id = o.getLong("id"),
                source = o.getString("source"),
                target = o.getString("target"),
                text = o.getString("text"),
                result = o.getString("result"),
                saved = o.optBoolean("saved"),
                time = o.getLong("time"),
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("source", it.source).put("target", it.target)
                    .put("text", it.text).put("result", it.result)
                    .put("saved", it.saved).put("time", it.time)
            )
        }
        val tmp = File(file.parentFile, "history.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
