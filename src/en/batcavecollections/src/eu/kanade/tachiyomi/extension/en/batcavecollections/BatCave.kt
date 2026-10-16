package eu.kanade.tachiyomi.extension.en.batcavecollections

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Calendar

@Source
abstract class BatCave : KeiSource() {

    // ============================== Client ==============================

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(DleGuardResolver.interceptor(baseUrl))
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("Sec-Fetch-Dest", "document")
        set("Sec-Fetch-Mode", "navigate")
        set("Sec-Fetch-Site", "none")
        set("Sec-Fetch-User", "?1")
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("comix")
            if (page > 1) {
                addPathSegments("page/$page")
            }
            addPathSegment("")
        }.build()

        val body = FormBody.Builder()
            .add("dlenewssortby", "rating")
            .add("dledirection", "desc")
            .add("set_new_sort", "dle_sort_cat_1")
            .add("set_direction_sort", "dle_direction_cat_1")
            .build()

        return parseSearchMangas(client.post(url, body))
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) {
                addPathSegments("page/$page")
                addPathSegment("")
            }
        }.build()

        val document = client.get(url).asJsoup()

        val entries = document.select("#content-load > .latest.grid-item").mapNotNull { element ->
            SManga.create().apply {
                with(element.selectFirst(".latest__title > a")!!) {
                    if (ownText().isEmpty()) return@mapNotNull null
                    setUrlWithoutDomain(absUrl("href"))
                    title = ownText()
                }
                thumbnail_url = element.selectFirst(".latest__img img")?.absUrl("src")
            }
        }

        val hasNextPage = document.selectFirst("li.pagination a[href]") != null

        return MangasPage(entries, hasNextPage)
    }

    // =============================== Search ==============================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {

        /*
         * Collection format:
         *
         * @collection|Collection Name|Issue 1|Issue 2|Issue 3
         */

        if (query.startsWith(COLLECTION_PREFIX)) {
            if (page > 1) {
                return MangasPage(emptyList(), false)
            }

            val collection = parseCollectionQuery(query)

            val manga = SManga.create().apply {
                title = collection.title

                setUrlWithoutDomain(
                    buildCollectionUrl(collection),
                )

                description = buildString {
                    append("Virtual collection")
                    appendLine()
                    appendLine()
                    append("Issues: ${collection.issues.size}")
                }

                genre = "Collection"
                status = SManga.COMPLETED
            }

            return MangasPage(
                listOf(manga),
                false,
            )
        }

        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("search")
                addPathSegment(query)

                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }

                addPathSegment("")
            }.build()

            return parseSearchMangas(client.get(url))
        }

        var filtersApplied = false

        val filterPath = buildString {
            filters.firstInstanceOrNull<YearFilter>()
                ?.takeIf { it.addFilterToUrl(this) }
                ?.let { filtersApplied = true }

            filters.firstInstanceOrNull<PublisherFilter>()
                ?.takeIf { it.addFilterToUrl(this) }
                ?.let { filtersApplied = true }

            filters.firstInstanceOrNull<GenreFilter>()
                ?.takeIf { it.addFilterToUrl(this) }
                ?.let { filtersApplied = true }
        }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("ComicList")

            if (filtersApplied) {
                addPathSegments(filterPath)
            } else {
                addPathSegment("y[from]=1926")
                addPathSegment(
                    "y[to]=${Calendar.getInstance().get(Calendar.YEAR)}",
                )
            }

            if (page > 1) {
                addPathSegments("page/$page")
            }

            addPathSegment("")
        }.build()

        val sort =
            filters.firstInstanceOrNull<SortFilter>() ?: SortFilter()

        val body = FormBody.Builder()
            .add("dlenewssortby", sort.getSort())
            .add("dledirection", sort.getDirection())
            .add("set_new_sort", "dle_sort_xfilter")
            .add("set_direction_sort", "dle_direction_xfilter")
            .build()

        return parseSearchMangas(client.post(url, body))
    }

    private fun parseSearchMangas(response: Response): MangasPage {
        val document = response.asJsoup()

        val entries = document.select("#dle-content > .readed").map { element ->
            SManga.create().apply {
                with(element.selectFirst(".readed__title > a")!!) {
                    setUrlWithoutDomain(absUrl("href"))
                    title = ownText()
                }

                thumbnail_url =
                    element.selectFirst(".readed__img img")
                        ?.absUrl("data-src")
            }
        }

        val hasNextPage =
            document.selectFirst("div.pagination__pages")
                ?.children()
                ?.last()
                ?.tagName() == "a"

        return MangasPage(entries, hasNextPage)
    }

    // ============================== Details ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {

        if (
            url.host == baseUrl.toHttpUrl().host &&
            url.encodedPath == COLLECTION_PATH
        ) {
            return createCollectionManga(
                title = url.queryParameter("title")
                    ?: return null,
                issues = url.queryParameter("issues")
                    ?.split(COLLECTION_SEPARATOR)
                    ?.filter { it.isNotBlank() }
                    ?: return null,
            )
        }

        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        url.pathSegments.firstOrNull()
            ?: return null

        return parseMangaDetails(
            client.get(url).asJsoup(),
        )
    }

    private fun createCollectionManga(
        title: String,
        issues: List<String>,
    ): SManga {
        return SManga.create().apply {
            this.title = title

            setUrlWithoutDomain(
                buildCollectionUrl(
                    CollectionData(
                        title,
                        issues,
                    ),
                ),
            )

            description = buildString {
                append("Virtual collection")
                appendLine()
                appendLine()
                append("Issues: ${issues.size}")
            }

            genre = "Collection"
            status = SManga.COMPLETED
        }
    }

    // =========================== Chapter Update ==========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {

        if (isCollection(manga)) {
            val collection = getCollectionData(manga)

            val resolvedChapters =
                resolveCollection(collection)

            return SMangaUpdate(
                manga,
                resolvedChapters,
            )
        }

        val doc = client.get(
            getMangaUrl(manga),
        ).asJsoup()

        return SMangaUpdate(
            parseMangaDetails(doc),
            parseChapterList(doc),
        )
    }

    // ============================ Collection =============================

    private suspend fun resolveCollection(
        collection: CollectionData,
    ): List<SChapter> {

        val result = mutableListOf<SChapter>()

        collection.issues.forEachIndexed { index, issueName ->

            val resolved = resolveIssue(issueName)

            if (resolved == null) {
                throw Exception(
                    "Could not find issue: $issueName",
                )
            }

            /*
             * Keep the exact issue name supplied by the user.
             *
             * This means Mihon displays:
             *
             * Green Lantern #1
             *
             * instead of whatever title BatCave happens to use.
             */
            result += SChapter.create().apply {
                url = resolved.url
                name = issueName
                chapter_number = index + 1f
                date_upload = resolved.date_upload
            }
        }

        return result
    }

    private suspend fun resolveIssue(
        issueName: String,
    ): SChapter? {

        val searchUrl =
            baseUrl.toHttpUrl()
                .newBuilder()
                .apply {
                    addPathSegment("search")
                    addPathSegment(issueName)
                    addPathSegment("")
                }
                .build()

        val searchResults =
            parseSearchMangas(
                client.get(searchUrl),
            )

        for (manga in searchResults.mangas) {

            val doc = try {
                client.get(
                    getMangaUrl(manga),
                ).asJsoup()
            } catch (_: Exception) {
                continue
            }

            val chapters = try {
                parseChapterList(doc)
            } catch (_: Exception) {
                continue
            }

            /*
             * First try an exact chapter-title match.
             */
            val exact =
                chapters.firstOrNull {
                    normalize(it.name) ==
                        normalize(issueName)
                }

            if (exact != null) {
                return exact
            }

            /*
             * Then use the issue number.
             */
            val issueNumber =
                extractIssueNumber(issueName)

            if (issueNumber != null) {

                val numberMatch =
                    chapters.firstOrNull {
                        it.chapter_number == issueNumber ||
                            extractIssueNumber(it.name) == issueNumber
                    }

                if (numberMatch != null) {
                    return numberMatch
                }
            }
        }

        return null
    }

    private fun parseCollectionQuery(
        query: String,
    ): CollectionData {

        val parts =
            query
                .removePrefix(COLLECTION_PREFIX)
                .split("|")
                .map { it.trim() }
                .filter { it.isNotBlank() }

        require(parts.size >= 2) {
            "Collection requires a title and at least one issue"
        }

        return CollectionData(
            title = parts.first(),
            issues = parts.drop(1),
        )
    }

    private fun buildCollectionUrl(
        collection: CollectionData,
    ): String {

        val url =
            baseUrl.toHttpUrl()
                .newBuilder()
                .addPathSegment("collection")
                .addQueryParameter(
                    "title",
                    collection.title,
                )
                .addQueryParameter(
                    "issues",
                    collection.issues.joinToString(
                        COLLECTION_SEPARATOR,
                    ),
                )
                .build()

        return url.encodedPath +
            "?" +
            url.encodedQuery
    }

    private fun isCollection(
        manga: SManga,
    ): Boolean =
        manga.url.startsWith(COLLECTION_PATH)

    private fun getCollectionData(
        manga: SManga,
    ): CollectionData {

        val url =
            baseUrl.toHttpUrl()
                .newBuilder()
                .addPathSegments(
                    manga.url.removePrefix("/"),
                )
                .build()

        return CollectionData(
            title =
                url.queryParameter("title")
                    ?: manga.title,

            issues =
                url.queryParameter("issues")
                    ?.split(COLLECTION_SEPARATOR)
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
        )
    }

    // ============================== Helpers ==============================

    private fun normalize(
        value: String,
    ): String =
        value
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun extractIssueNumber(
        value: String,
    ): Float? {

        val match =
            Regex("""#\s*(\d+(?:\.\d+)?)""")
                .find(value)
                ?: return null

        return match.groupValues[1].toFloatOrNull()
    }

    // ============================== Real Manga ===========================

    private fun parseMangaDetails(
        doc: Document,
    ): SManga =
        SManga.create().apply {

            setUrlWithoutDomain(
                doc.location(),
            )

            title =
                doc.selectFirst(
                    "header.page__header h1",
                )!!.text()

            thumbnail_url =
                doc.selectFirst(
                    "div.page__poster img",
                )?.absUrl("src")

            description = buildString {

                doc.getPageListItem("Publisher")?.let {
                    append(it)
                }

                doc.getPageListItem("Year")?.let {
                    appendLine(" — $it")
                }

                appendLine()

                append(
                    doc.selectFirst(
                        "div.page__text",
                    )?.text(),
                )
            }

            author =
                doc.getPageListItem("Writer")

            artist =
                doc.getPageListItem("Artist")

            genre =
                buildList {
                    doc.select(
                        "div.page__tags a",
                    ).mapTo(this) {
                        it.text()
                    }

                    add("Comic")
                }.joinToString()

            status =
                when (
                    doc.selectFirst(
                        ".page__list > li:has(> div:contains(Release type))",
                    )?.ownText()?.trim()
                ) {
                    "Ongoing" ->
                        SManga.ONGOING

                    "Completed" ->
                        SManga.COMPLETED

                    else ->
                        SManga.UNKNOWN
                }

            doc.selectFirst(
                ".page__similar-panel.is-active",
            )?.select(
                ".scroller-2 a.poster",
            )?.let { anchor ->

                memo = buildJsonObject {

                    val similar =
                        anchor.mapNotNull {

                            val title =
                                it.selectFirst(
                                    ".poster__title",
                                )?.text()
                                    ?.takeIf {
                                        str -> str.isNotBlank()
                                    }

                            val url =
                                it.attr("href")
                                    .takeIf {
                                        str -> str.isNotBlank()
                                    }

                            val thumb =
                                it.selectFirst("img")
                                    ?.attr("data-src")

                            if (
                                title != null &&
                                url != null
                            ) {
                                RelatedComic(
                                    title,
                                    url,
                                    thumb,
                                )
                            } else {
                                null
                            }
                        }

                    put(
                        "similarComics",
                        similar.toJsonElement(),
                    )
                }
            }
        }

    private fun parseChapterList(
        document: Document,
    ): List<SChapter> {

        val script =
            document.selectFirst(
                "script:containsData(window.__DATA__)",
            )?.data()
                ?: error("Chapter data script not found")

        val data =
            script
                .substringAfter(
                    "window.__DATA__ = ",
                )
                .substringBeforeLast(";")
                .trim()
                .parseAs<Chapters>()

        return data.chapters.map { chap ->
            SChapter.create().apply {

                url =
                    "/reader/${data.comicId}/${chap.id}${data.xhash}"

                name = chap.title

                chapter_number = chap.number

                date_upload =
                    dateFormat.tryParseDate(chap.date)
            }
        }
    }

    private val dateFormat =
        DateTimeFormatter.ofPattern("d.M.yyyy")

    // =============================== Related =============================

    override val supportsRelatedMangas
        get() = true

    override suspend fun fetchRelatedMangaList(
        manga: SManga,
    ): List<SManga> {

        val related =
            manga.memo["similarComics"]

        return related
            ?.parseAs<List<RelatedComic>>()
            ?.map {

                SManga.create().apply {
                    title = it.name

                    thumbnail_url =
                        "$baseUrl${it.thumbnail}"

                    setUrlWithoutDomain(
                        it.url,
                    )
                }
            }
            ?: emptyList()
    }

    // =============================== Filters ==============================

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {

        val doc =
            client.get(
                "$baseUrl/comix/",
            ).asJsoup()

        val script =
            doc.selectFirst(
                "script:containsData(window.__XFILTER__)",
            )?.data()
                ?: error("Filter data not found")

        val rawFilters =
            script
                .substringAfter(
                    "window.__XFILTER__ = ",
                )
                .substringBeforeLast(";")
                .trim()

        return rawFilters
            .parseAs<XFilters>()
            .filterItems
            .toJsonElement()
    }

    override fun getFilterList(
        data: JsonElement?,
    ): FilterList {

        val filterData =
            data?.parseAs<XFilterItems>()

        val filters = mutableListOf(
            Filter.Header(
                "Filters don't work with text search",
            ),
            Filter.Separator(),
            SortFilter(),
            YearFilter(),
        )

        val publishers =
            filterData
                ?.publisher
                ?.values
                ?.map {
                    it.value to it.id
                }
                ?: emptyList()

        val genres =
            filterData
                ?.genre
                ?.values
                ?.map {
                    it.value to it.id
                }
                ?: emptyList()

        if (publishers.isNotEmpty()) {
            filters.add(
                PublisherFilter(publishers),
            )
        }

        if (genres.isNotEmpty()) {
            filters.add(
                GenreFilter(genres),
            )
        }

        return FilterList(filters)
    }

    fun Document.getPageListItem(
        label: String,
    ): String? =
        selectFirst(
            ".page__list > li:has(> div:contains($label))",
        )
            ?.selectFirst("> a")
            ?.text()
            ?.takeIf {
                it.isNotBlank()
            }

    companion object {

        private const val COLLECTION_PREFIX =
            "@collection|"

        private const val COLLECTION_PATH =
            "/collection"

        private const val COLLECTION_SEPARATOR =
            "|||"

    }
}