package com.lagradost.fastani

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.fastani.MainActivity.Companion.activity
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

const val CLIENT_ID = "4636"
const val ACCOUNT_ID = "0" // MIGHT WANT TO BE USED IF YOU WANT MULTIPLE ACCOUNT LOGINS

class AniListApi {
    companion object {
        val aniListStatusString = arrayOf("CURRENT", "COMPLETED", "PAUSED", "DROPPED", "PLANNING", "REPEATING")

        val mapper = JsonMapper.builder().addModule(KotlinModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

        fun fromInt(value: Int) = AniListStatusType.values().first { it.value == value }

        fun authenticate() {
            val request = "https://anilist.co/api/v2/oauth/authorize?client_id=$CLIENT_ID&response_type=token";
            MainActivity.openBrowser(request);
        }

        fun initGetUser() {
            if (DataStore.getKey<String>(ANILIST_TOKEN_KEY, ACCOUNT_ID, null) == null) return
            thread {
                getUser()
            }
        }

        fun splitQuery(url: URL): Map<String, String>? {
            val query_pairs: MutableMap<String, String> = LinkedHashMap()
            val query: String = url.getQuery()
            val pairs = query.split("&").toTypedArray()
            for (pair in pairs) {
                val idx = pair.indexOf("=")
                query_pairs[URLDecoder.decode(pair.substring(0, idx), "UTF-8")] =
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }
            return query_pairs
        }

        fun authenticateLogin(data: String) {
            try {
                val sanitizer = splitQuery(URL(data.replace("fastaniapp", "https").replace("/#", "?")))!! // FIX ERROR
                val token = sanitizer["access_token"]!!
                val expiresIn = sanitizer["expires_in"]!!
                println("DATA: " + token + "|" + expiresIn)

                val unixTime = System.currentTimeMillis() / 1000L
                val endTime = unixTime + expiresIn.toLong()

                DataStore.setKey(ANILIST_UNIXTIME_KEY, ACCOUNT_ID, endTime)
                DataStore.setKey(ANILIST_TOKEN_KEY, ACCOUNT_ID, token)

                println("ANILIST LOGIN DONE")
                thread {
                    getUser()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun checkToken(): Boolean {
            val unixTime = System.currentTimeMillis() / 1000L
            if (unixTime > DataStore.getKey(ANILIST_UNIXTIME_KEY, ACCOUNT_ID, 0L)!!) {
                val alertDialog: AlertDialog? = activity?.let {
                    val builder = AlertDialog.Builder(it)
                    builder.apply {
                        setPositiveButton("Login",
                            DialogInterface.OnClickListener { dialog, id ->
                                authenticate()
                            })
                        setNegativeButton("Cancel",
                            DialogInterface.OnClickListener { dialog, id ->
                                // User cancelled the dialog
                            })
                    }
                    // Set other dialog properties
                    builder.setTitle("AniList token has expired")

                    // Create the AlertDialog
                    builder.create()
                }
                alertDialog?.show()
                return true
            } else {
                return false
            }
        }

        private fun postApi(url: String, q: String): String {
            return try {
                if (!checkToken()) {
                    // println("VARS_ " + vars)
                    khttp.post(
                        "https://graphql.anilist.co/",
                        headers = mapOf(
                            "Authorization" to "Bearer " + DataStore.getKey(
                                ANILIST_TOKEN_KEY,
                                ACCOUNT_ID,
                                ""
                            )!!
                        ),
                        data = mapOf("query" to q)//(if (vars == null) mapOf("query" to q) else mapOf("query" to q, "variables" to vars))
                    ).text.replace("\\", "")
                } else {
                    ""
                }
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }

        fun getDataAboutId(id: Int): AniListTitleHolder? {
            val q: String =
                """query (${'$'}id: Int = $id) { # Define which variables will be used in the query (id)
                Media (id: ${'$'}id, type: ANIME) { # Insert our variables into the query arguments (id) (type: ANIME is hard-coded in the query)
                    id
                    episodes
                    isFavourite
                    mediaListEntry {
                        progress
                        status
                        score (format: POINT_10)
                    }
                }
            }"""
            try {
                println("ID::::: " + id)
                val data = postApi("https://graphql.anilist.co", q)
                println(data)
                val d = mapper.readValue<GetDataRoot>(data)
                val main = d.data.media
                if(main.mediaListEntry != null) {
                    return AniListTitleHolder(
                        id = id,
                        isFavourite = main.isFavourite,
                        progress = main.mediaListEntry.progress,
                        episodes = main.episodes,
                        score = main.mediaListEntry.score,
                        type = fromInt(aniListStatusString.indexOf(main.mediaListEntry.status))
                    )
                }
                else {
                    return AniListTitleHolder(
                        id = id,
                        isFavourite = main.isFavourite,
                        progress = 0,
                        episodes = main.episodes,
                        score = 0,
                        type = fromInt(-1)
                    )
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                return null
            }

        }

        fun toggleLike(id: Int): Boolean {
            val q: String = """mutation (${'$'}animeId: Int = $id) {
				ToggleFavourite (animeId: ${'$'}animeId) {
					anime {
						nodes {
							id
							title {
								romaji
							}
						}
					}
				}
			}"""
            val data = postApi("https://graphql.anilist.co", q)
            return data != ""
        }

        fun postDataAboutId(id: Int, type: AniListStatusType, score: Int, progress: Int): Boolean {
            val q: String =
                """mutation (${'$'}id: Int = $id, ${'$'}status: MediaListStatus = ${aniListStatusString[type.value]}, ${'$'}scoreRaw: Int = ${score * 10}, ${'$'}progress: Int = $progress) {
                SaveMediaListEntry (mediaId: ${'$'}id, status: ${'$'}status, scoreRaw: ${'$'}scoreRaw, progress: ${'$'}progress) {
                    id
                    status
                    progress
                    score
                }
                }"""

            val data = postApi("https://graphql.anilist.co", q)
            return data != ""
        }

        fun getUser(setSettings: Boolean = true): AniListUser? {
            val q: String = """
				{
  					Viewer {
    					id
    					name
						avatar {
							large
						}
                        favourites {
                            anime {
                                nodes {
                                    id
                                    title {
                                        romaji
                                        english
                                    }
                                }
                            }
                        }
  					}
				}"""
            try {
                val data = postApi("https://graphql.anilist.co", q)
                if (data == "") return null
                println("RESULT: " + data)
                val userData = mapper.readValue<AniListRoot>(data)
                val u = userData.data.Viewer
                val user = AniListUser(
                    u.id,
                    u.name,
                    u.avatar.large,
                )
                if (setSettings) {
                    DataStore.setKey(ANILIST_USER_KEY, ACCOUNT_ID, user)
                }
                /* // TODO FIX FAVS
                for(i in u.favourites.anime.nodes) {
                    println("FFAV:" + i.id)
                }*/
                return user
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                return null
            }
        }

        fun getSeason(id: Int): SeasonResponse? {
            val q: String = """
               query (${'$'}id: Int = $id) {
                   Media (id: ${'$'}id, type: ANIME) {
                       id
                       idMal
                       relations {
                            edges {
                                 id
                                 relationType(version: 2)
                                 node {
                                      id
                                      format
                                      nextAiringEpisode {
                                           timeUntilAiring
                                           episode
                                      }
                                 }
                            }
                       }
                       nextAiringEpisode {
                            timeUntilAiring
                            episode
                       }
                       format
                   }
               }
        """

            val data = khttp.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to q)
            ).text
            if (data == "") return null
            return mapper.readValue(data)
        }

        fun getAllSeasons(id: Int): List<SeasonResponse?> {
            val seasons = mutableListOf<SeasonResponse?>()
            fun getSeasonRecursive(id: Int) {
                println(id)
                val season = getSeason(id)
                println(season)
                if (season != null) {
                    seasons.add(season)
                    if (season.data.Media.format?.startsWith("TV") == true) {
                        season.data.Media.relations.edges.forEach {
                            if (it.relationType == "SEQUEL" && it.node.format.startsWith("TV")) {
                                getSeasonRecursive(it.node.id)
                                return@forEach
                            }
                        }
                    }
                }
            }
            getSeasonRecursive(id)
            return seasons.toList()
        }

        fun secondsToReadable(seconds: Int): String {
            var secondsLong = seconds.toLong()
            val days = TimeUnit.SECONDS
                .toDays(secondsLong)
            secondsLong -= TimeUnit.DAYS.toSeconds(days)

            val hours = TimeUnit.SECONDS
                .toHours(secondsLong)
            secondsLong -= TimeUnit.HOURS.toSeconds(hours)

            val minutes = TimeUnit.SECONDS
                .toMinutes(secondsLong)
            secondsLong -= TimeUnit.MINUTES.toSeconds(minutes)

            return "${if (days != 0L) "$days" + "d " else ""}${if (hours != 0L && days != 0L) "$hours" + "h " else ""}${minutes}m"
        }
    }

    enum class AniListStatusType(val value: Int) {
        Watching(0),
        Completed(1),
        OnHold(2),
        Dropped(3),
        PlanToWatch(4),
        ReWatching(5),
        None(-1)
    }

    data class SeasonResponse(
        @JsonProperty("data") val data: SeasonData,
    )

    data class SeasonData(
        @JsonProperty("Media") val Media: SeasonMedia,
    )

    data class SeasonMedia(
        @JsonProperty("id") val id: Int,
        @JsonProperty("idMal") val idMal: Int?,
        @JsonProperty("format") val format: String?,
        @JsonProperty("nextAiringEpisode") val nextAiringEpisode: SeasonNextAiringEpisode?,
        @JsonProperty("relations") val relations: SeasonEdges,
    )

    data class SeasonNextAiringEpisode(
        @JsonProperty("episode") val episode: Int,
        @JsonProperty("timeUntilAiring") val timeUntilAiring: Int,
    )

    data class SeasonEdges(
        @JsonProperty("edges") val edges: List<SeasonEdge>,
    )

    data class SeasonEdge(
        @JsonProperty("id") val id: Int,
        @JsonProperty("relationType") val relationType: String,
        @JsonProperty("node") val node: SeasonNode,
    )

    data class AniListFavoritesMediaConnection(
        @JsonProperty("nodes") val nodes: List<LikeNode>,
    )

    data class AniListFavourites(
        @JsonProperty("anime") val anime: AniListFavoritesMediaConnection,
    )

    data class SeasonNode(
        @JsonProperty("id") val id: Int,
        @JsonProperty("format") val format: String,
        @JsonProperty("nextAiringEpisode") val nextAiringEpisode: SeasonNextAiringEpisode?,
    )

    data class AniListAvatar(
        @JsonProperty("large") val large: String,
    )

    data class AniListViewer(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("avatar") val avatar: AniListAvatar,
        @JsonProperty("favourites") val favourites: AniListFavourites,
    )

    data class AniListData(
        @JsonProperty("Viewer") val Viewer: AniListViewer,
    )

    data class AniListRoot(
        @JsonProperty("data") val data: AniListData,
    )

    data class AniListUser(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("picture") val picture: String,
    )


    data class LikeNode(
        @JsonProperty("id") val id: Int,
        //@JsonProperty("idMal") public int idMal;
    )

    data class LikePageInfo(
        @JsonProperty("total") val total: Int,
        @JsonProperty("currentPage") val currentPage: Int,
        @JsonProperty("lastPage") val lastPage: Int,
        @JsonProperty("perPage") val perPage: Int,
        @JsonProperty("hasNextPage") val hasNextPage: Boolean,
    )

    data class LikeAnime(
        @JsonProperty("nodes") val nodes: List<LikeNode>,
        @JsonProperty("pageInfo") val pageInfo: LikePageInfo,
    )

    data class LikeFavourites(
        @JsonProperty("anime") val anime: LikeAnime,
    )

    data class LikeViewer(
        @JsonProperty("favourites") val favourites: LikeFavourites,
    )

    data class LikeData(
        @JsonProperty("Viewer") val Viewer: LikeViewer,
    )

    data class LikeRoot(
        @JsonProperty("data") val data: LikeData,
    )

    data class AniListTitleHolder(
        val isFavourite: Boolean,
        val id: Int,
        val progress: Int,
        val episodes: Int,
        val score: Int,
        val type: AniListStatusType,
    )

    data class GetDataMediaListEntry(
        @JsonProperty("progress") val progress: Int,
        @JsonProperty("status") val status: String,
        @JsonProperty("score") val score: Int,
    )

    data class GetDataMedia(
        @JsonProperty("isFavourite") val isFavourite: Boolean,
        @JsonProperty("episodes") val episodes: Int,
        @JsonProperty("mediaListEntry") val mediaListEntry: GetDataMediaListEntry?,
    )

    data class GetDataData(
        @JsonProperty("Media") val media: GetDataMedia,
    )

    data class GetDataRoot(
        @JsonProperty("data") val data: GetDataData,
    )
}