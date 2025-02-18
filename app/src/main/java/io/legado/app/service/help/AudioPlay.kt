package io.legado.app.service.help

import android.content.Context
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.AudioPlayService
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService

object AudioPlay {
    var titleData = MutableLiveData<String>()
    var coverData = MutableLiveData<String>()
    var status = Status.STOP
    var book: Book? = null
    var durChapter: BookChapter? = null
    var inBookshelf = false
    var durChapterIndex = 0
    var durChapterPos = 0
    var webBook: WebBook? = null
    val loadingChapters = arrayListOf<Int>()

    fun headers(): Map<String, String>? {
        return webBook?.bookSource?.getHeaderMap()
    }

    fun play(context: Context) {
        book?.let {
            if (durChapter == null) {
                upDurChapter(it)
            }
            durChapter?.let {
                val intent = Intent(context, AudioPlayService::class.java)
                intent.action = IntentAction.play
                context.startService(intent)
            }
        }
    }

    fun upDurChapter(book: Book) {
        durChapter = appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)
        postEvent(EventBus.AUDIO_SUB_TITLE, durChapter?.title ?: "")
        postEvent(EventBus.AUDIO_SIZE, durChapter?.end?.toInt() ?: 0)
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
    }

    fun pause(context: Context) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.pause
            }
        }
    }

    fun resume(context: Context) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.resume
            }
        }
    }

    fun stop(context: Context) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.stop
            }
        }
    }

    fun adjustSpeed(context: Context, adjust: Float) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.adjustSpeed
                putExtra("adjust", adjust)
            }
        }
    }

    fun adjustProgress(context: Context, position: Int) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.adjustProgress
                putExtra("position", position)
            }
        }
    }

    fun skipTo(context: Context, index: Int) {
        Coroutine.async {
            book?.let { book ->
                durChapterIndex = index
                durChapterPos = 0
                durChapter = null
                saveRead(book)
                play(context)
            }
        }
    }

    fun prev(context: Context) {
        Coroutine.async {
            book?.let { book ->
                if (book.durChapterIndex <= 0) {
                    return@let
                }
                durChapterIndex--
                durChapterPos = 0
                durChapter = null
                saveRead(book)
                play(context)
            }
        }
    }

    fun next(context: Context) {
        book?.let { book ->
            if (book.durChapterIndex >= book.totalChapterNum) {
                return@let
            }
            durChapterIndex++
            durChapterPos = 0
            durChapter = null
            saveRead(book)
            play(context)
        }
    }

    fun addTimer(context: Context) {
        val intent = Intent(context, AudioPlayService::class.java)
        intent.action = IntentAction.addTimer
        context.startService(intent)
    }

    fun saveRead(book: Book) {
        book.lastCheckCount = 0
        book.durChapterTime = System.currentTimeMillis()
        book.durChapterIndex = durChapterIndex
        book.durChapterPos = durChapterPos
        Coroutine.async {
            appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)?.let {
                book.durChapterTitle = it.title
            }
            book.save()
        }
    }

    fun saveDurChapter(audioSize: Long) {
        Coroutine.async {
            durChapter?.let {
                it.end = audioSize
                appDb.bookChapterDao.insert(it)
            }
        }
    }
}