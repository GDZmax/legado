package io.legado.app.ui.book.changecover

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.AppConfig
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.min

class ChangeCoverViewModel(application: Application) : BaseViewModel(application) {
    private val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    val handler = Handler(Looper.getMainLooper())
    var name: String = ""
    var author: String = ""
    private var tasks = CompositeCoroutine()
    private var bookSourceList = arrayListOf<BookSource>()
    val searchStateData = MutableLiveData<Boolean>()
    val searchBooksLiveData = MutableLiveData<List<SearchBook>>()
    private val searchBooks = CopyOnWriteArraySet<SearchBook>()
    private val sendRunnable = Runnable { upAdapter() }
    private var postTime = 0L

    @Volatile
    private var searchIndex = -1

    fun initData(arguments: Bundle?) {
        arguments?.let { bundle ->
            bundle.getString("name")?.let {
                name = it
            }
            bundle.getString("author")?.let {
                author = it.replace(AppPattern.authorRegex, "")
            }
        }
    }

    private fun initSearchPool() {
        searchPool = Executors.newFixedThreadPool(min(threadCount,8)).asCoroutineDispatcher()
        searchIndex = -1
    }

    fun loadDbSearchBook() {
        execute {
            appDb.searchBookDao.getEnableHasCover(name, author).let {
                searchBooks.addAll(it)
                searchBooksLiveData.postValue(searchBooks.toList())
                if (it.size <= 1) {
                    startSearch()
                }
            }
        }
    }

    @Synchronized
    private fun upAdapter() {
        if (System.currentTimeMillis() >= postTime + 500) {
            handler.removeCallbacks(sendRunnable)
            postTime = System.currentTimeMillis()
            val books = searchBooks.toList()
            searchBooksLiveData.postValue(books.sortedBy { it.originOrder })
        } else {
            handler.removeCallbacks(sendRunnable)
            handler.postDelayed(sendRunnable, 500)
        }
    }

    private fun startSearch() {
        execute {
            bookSourceList.clear()
            bookSourceList.addAll(appDb.bookSourceDao.allEnabled)
            searchStateData.postValue(true)
            initSearchPool()
            for (i in 0 until threadCount) {
                search()
            }
        }
    }

    @Synchronized
    private fun search() {
        if (searchIndex >= bookSourceList.lastIndex) {
            return
        }
        searchIndex++
        val source = bookSourceList[searchIndex]
        if (source.getSearchRule().coverUrl.isNullOrBlank()) {
            searchNext()
            return
        }
        val task = WebBook(source)
            .searchBook(viewModelScope, name, context = searchPool!!)
            .timeout(60000L)
            .onSuccess(searchPool) {
                if (it.isNotEmpty()) {
                    val searchBook = it[0]
                    if (searchBook.name == name && searchBook.author == author
                        && !searchBook.coverUrl.isNullOrEmpty()
                    ) {
                        appDb.searchBookDao.insert(searchBook)
                        if (!searchBooks.contains(searchBook)) {
                            searchBooks.add(searchBook)
                            upAdapter()
                        }
                    }
                }
            }
            .onFinally(searchPool) {
                searchNext()
            }
        tasks.add(task)
    }

    @Synchronized
    private fun searchNext() {
        if (searchIndex < bookSourceList.lastIndex) {
            search()
        } else {
            searchIndex++
        }
        if (searchIndex >= bookSourceList.lastIndex + min(
                bookSourceList.size,
                threadCount
            )
        ) {
            searchStateData.postValue(false)
        }
    }

    fun stopSearch() {
        if (tasks.isEmpty) {
            startSearch()
        } else {
            tasks.clear()
            searchStateData.postValue(false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchPool?.close()
    }

}