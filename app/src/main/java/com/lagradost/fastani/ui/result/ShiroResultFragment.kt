package com.lagradost.fastani.ui.result

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.Transition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProviders
import androidx.mediarouter.app.MediaRouteButton
import androidx.preference.PreferenceManager
import com.bumptech.glide.load.model.GlideUrl
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.cast.CastPlayer
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.common.images.WebImage
import com.lagradost.fastani.*
import com.lagradost.fastani.DataStore.mapper
import com.lagradost.fastani.FastAniApi.Companion.getVideoLink
import com.lagradost.fastani.FastAniApi.Companion.requestHome
import com.lagradost.fastani.MainActivity.Companion.getColorFromAttr
import com.lagradost.fastani.MainActivity.Companion.hideKeyboard
import com.lagradost.fastani.ui.GlideApp
import com.lagradost.fastani.ui.PlayerFragment
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.episode_result.view.*
import kotlinx.android.synthetic.main.fragment_home.*
import kotlinx.android.synthetic.main.fragment_results.*
import kotlinx.android.synthetic.main.fragment_search.*
import kotlinx.android.synthetic.main.home_card.view.*
import kotlinx.android.synthetic.main.number_picker_dialog.*
import kotlinx.android.synthetic.main.player_custom_layout.*
import kotlinx.android.synthetic.main.search_result.view.*
import java.io.File
import kotlin.concurrent.thread


const val DESCRIPTION_LENGTH1 = 200

class ShiroResultFragment : Fragment() {
    var data: FastAniApi.AnimePage? = null
    private lateinit var resultViewModel: ResultViewModel
    private var isMovie: Boolean = false
    var isBookmarked = false

    companion object {
        var isInResults: Boolean = false
        var isViewState: Boolean = true

        fun newInstance(data: FastAniApi.AnimePage) =
            ShiroResultFragment().apply {
                arguments = Bundle().apply {
                    //println(data)
                    putString("data", mapper.writeValueAsString(data))
                }
            }
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        resultViewModel =
            activity?.let { ViewModelProviders.of(it).get(ResultViewModel::class.java) }!!
        return inflater.inflate(R.layout.fragment_results_shiro, container, false)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        arguments?.getString("data")?.let {
            data = mapper.readValue(it, FastAniApi.AnimePage::class.java)
            println("DATAATATAT $data")
        }
        //isMovie = data!!.episodes == 1 && data!!.status == "FINISHED"
        //isBookmarked = DataStore.containsKey(BOOKMARK_KEY, data!!.anilistId)
    }
    /*
    private fun ToggleViewState(_isViewState: Boolean) {
        isViewState = _isViewState
        if (isViewState) {
            title_viewstate.setImageResource(R.drawable.filled_viewstate)
        } else {
            title_viewstate.setImageResource(R.drawable.outlined_viewstate)
        }
    }*/

    private fun toggleHeartVisual(_isBookmarked: Boolean) {
        if (_isBookmarked) {
            title_bookmark.setImageResource(R.drawable.filled_heart)
        } else {
            title_bookmark.setImageResource(R.drawable.outlined_heart)
        }
    }

    private fun toggleHeart(_isBookmarked: Boolean) {
        this.isBookmarked = _isBookmarked
        toggleHeartVisual(_isBookmarked)
        if (_isBookmarked) {
            /*DataStore.setKey<BookmarkedTitle>(
                BOOKMARK_KEY,
                data!!.url,
                BookmarkedTitle(data!!.url, data!!.title, data!!.posterUrl)
            )*/
        } else {
            DataStore.removeKey(BOOKMARK_KEY, data!!.url)
        }
        thread {
            requestHome(true)
        }
    }

    private fun castEpsiode(episodeIndex: Int) {
        val castContext = CastContext.getSharedInstance(activity!!.applicationContext)
        castContext.castOptions
        val url = data!!.episodes[episodeIndex]
        val key = data!!.url + episodeIndex//MainActivity.getViewKey(data!!.anilistId, seasonIndex, episodeIndex)

        val movieMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
        movieMetadata.putString(
            MediaMetadata.KEY_TITLE,
            "Episode ${episodeIndex + 1}"
        )
        movieMetadata.putString(MediaMetadata.KEY_ALBUM_ARTIST, data!!.title)
        movieMetadata.addImage(WebImage(Uri.parse(data!!.posterUrl)))
        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(MimeTypes.VIDEO_UNKNOWN)
            .setMetadata(movieMetadata).build()

        val mediaItems = arrayOf(MediaQueueItem.Builder(mediaInfo).build())
        val castPlayer = CastPlayer(castContext)

        castPlayer.loadItems(
            mediaItems,
            0,
            DataStore.getKey<Long>(VIEW_POS_KEY, key, 0L)!!,
            Player.REPEAT_MODE_OFF
        )


        /*castPlayer.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {

            }

            override fun onCastSessionUnavailable() {}
        })*/
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun loadSeason() {
        println("LOAD SEASON! ${data?.episodes}")
        title_season_cards.removeAllViews()
        var epNum = 0
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(MainActivity.activity)
        val save = settingsManager.getBoolean("save_history", true)

        if (data!!.episodes.isNotEmpty()) {
            data!!.episodes.forEach { fullEpisode ->
                val epIndex = epNum
                epNum++

                val card: View = layoutInflater.inflate(R.layout.episode_result, null)
                val key = data!!.url + epIndex//MainActivity.getViewKey(data!!.url, index, epIndex)

                if (false) {
                    card.cdi.setOnClickListener {
                        if (data != null) {
                            /*DownloadManager.downloadEpisode(
                                DownloadManager.DownloadInfo(
                                    index,
                                    epIndex,
                                    data!!.title,
                                    isMovie,
                                    data!!.anilistId,
                                    data!!.id,
                                    data!!.cdnData.seasons[index].episodes[epIndex],
                                    data!!.coverImage.large
                                )
                            )*/
                        }
                    }
                } else {
                    card.cdi.visibility = View.GONE
                    val param = card.cardTitle.layoutParams as ViewGroup.MarginLayoutParams
                    param.updateMarginsRelative(
                        card.cardTitle.marginLeft,
                        card.cardTitle.marginTop,
                        10.toPx,
                        card.cardTitle.marginBottom
                    )
                    card.cardTitle.layoutParams = param
                }

                card.cardBg.setOnClickListener {
                    val castContext = CastContext.getSharedInstance(activity!!.applicationContext)
                    println("SSTATE: " + castContext.castState + "<<")
                    if (save) {
                        DataStore.setKey<Long>(VIEWSTATE_KEY, key, System.currentTimeMillis())
                    }

                    if (castContext.castState == CastState.CONNECTED) {
                        castEpsiode(epIndex)
                        loadSeason()
                    } else {
                        thread {
                            val videoUrl = getVideoLink(data!!.episodes[epIndex])
                            if (videoUrl != null) {
                                MainActivity.loadPlayer("${data!!.title} - Episode ${epIndex + 1}", videoUrl, 0L)
                            } else {
                                requireActivity().runOnUiThread {
                                    Toast.makeText(activity, "Failed to get video link :(", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }

                card.setOnLongClickListener {
                    if (isViewState) {
                        if (DataStore.containsKey(VIEWSTATE_KEY, key)) {
                            DataStore.removeKey(VIEWSTATE_KEY, key)
                        } else {
                            DataStore.setKey<Long>(VIEWSTATE_KEY, key, System.currentTimeMillis())
                        }
                        loadSeason()
                    }
                    return@setOnLongClickListener true
                }

                val title = "Episode ${epIndex + 1}"

                card.cardTitle.text = title
                if (DataStore.containsKey(VIEWSTATE_KEY, key)) {
                    card.cardBg.setCardBackgroundColor(
                        requireContext().getColorFromAttr(
                            R.attr.colorPrimaryMegaDark
                        )
                    )
                }

                val pro = MainActivity.getViewPosDur(data!!.url, 0, epIndex)
                //println("DURPOS:" + epNum + "||" + pro.pos + "|" + pro.dur)
                if (pro.dur > 0 && pro.pos > 0) {
                    var progress: Int = (pro.pos * 100L / pro.dur).toInt()
                    if (progress < 5) {
                        progress = 5
                    } else if (progress > 95) {
                        progress = 100
                    }
                    card.video_progress.progress = progress
                } else {
                    card.video_progress.alpha = 0f
                }

                if (MainActivity.isDonor) {
                    val internalId = (data!!.url + "E${epIndex}").hashCode()
                    val child = DataStore.getKey<DownloadManager.DownloadFileMetadata>(
                        DOWNLOAD_CHILD_KEY,
                        internalId.toString()
                    )
                    // ================ DOWNLOAD STUFF ================
                    if (child != null) {
                        println("CHILD NOT NULL:" + epIndex)
                        val file = File(child.videoPath)
                        if (file.exists()) {
                            val megaBytesTotal = DownloadManager.convertBytesToAny(child.maxFileSize, 0, 2.0).toInt()
                            val localBytesTotal =
                                maxOf(DownloadManager.convertBytesToAny(file.length(), 0, 2.0).toInt(), 1)

                            fun updateIcon(megabytes: Int) {
                                if (!file.exists()) {
                                    card.cdi.visibility = View.VISIBLE
                                    card.progressBar.visibility = View.GONE
                                    card.cardPauseIcon.visibility = View.GONE
                                    card.cardRemoveIcon.visibility = View.GONE
                                } else {
                                    card.cdi.visibility = View.GONE
                                    if (megabytes + 3 >= megaBytesTotal) {
                                        card.progressBar.visibility = View.GONE
                                        card.cardPauseIcon.visibility = View.GONE
                                        card.cardRemoveIcon.visibility = View.VISIBLE
                                    } else {
                                        card.progressBar.visibility = View.VISIBLE
                                        card.cardRemoveIcon.visibility = View.GONE
                                        card.cardPauseIcon.visibility = View.VISIBLE
                                    }
                                }
                            }

                            println("FILE EXISTS:" + epIndex)
                            fun deleteFile() {
                                if (file.exists()) {
                                    file.delete()
                                }
                                activity?.runOnUiThread {
                                    DataStore.removeKey(DOWNLOAD_CHILD_KEY, key)
                                    Toast.makeText(
                                        context,
                                        "${child.videoTitle} S${child.seasonIndex + 1}:E${child.episodeIndex + 1} deleted",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    updateIcon(0)
                                }
                            }

                            card.cardRemoveIcon.setOnClickListener {
                                val alertDialog: AlertDialog? = activity?.let {
                                    val builder = AlertDialog.Builder(it)
                                    builder.apply {
                                        setPositiveButton("Delete",
                                            DialogInterface.OnClickListener { dialog, id ->
                                                deleteFile()
                                            })
                                        setNegativeButton("Cancel",
                                            DialogInterface.OnClickListener { dialog, id ->
                                                // User cancelled the dialog
                                            })
                                    }
                                    // Set other dialog properties
                                    builder.setTitle("Delete ${child.videoTitle} - S${child.seasonIndex + 1}:E${child.episodeIndex + 1}")

                                    // Create the AlertDialog
                                    builder.create()
                                }
                                alertDialog?.show()
                            }

                            card.cardTitle.text = title

                            //card.cardTitleExtra.text = "$localBytesTotal / $megaBytesTotal MB"


                            fun getDownload(): DownloadManager.DownloadInfo {
                                return DownloadManager.DownloadInfo(
                                    child.seasonIndex,
                                    child.episodeIndex,
                                    FastAniApi.Title(data!!.title, data!!.title, data!!.title),
                                    isMovie,
                                    child.anilistId,
                                    child.fastAniId,
                                    FastAniApi.FullEpisode(child.downloadFileUrl, child.videoTitle, child.thumbPath),
                                    null
                                )
                            }

                            fun getStatus(): Boolean { // IF CAN RESUME
                                return if (DownloadManager.downloadStatus.containsKey(child.internalId)) {
                                    DownloadManager.downloadStatus[child.internalId] == DownloadManager.DownloadStatusType.IsPaused
                                } else {
                                    true
                                }
                            }

                            fun setStatus() {
                                activity?.runOnUiThread {
                                    if (getStatus()) {
                                        card.cardPauseIcon.setImageResource(R.drawable.netflix_play)
                                    } else {
                                        card.cardPauseIcon.setImageResource(R.drawable.exo_icon_stop)
                                    }
                                }
                            }

                            setStatus()
                            updateIcon(localBytesTotal)

                            card.cardPauseIcon.setOnClickListener { v ->
                                val popup = PopupMenu(context, v)
                                if (getStatus()) {
                                    popup.setOnMenuItemClickListener {
                                        when (it.itemId) {
                                            R.id.res_resumedload -> {
                                                DownloadManager.downloadEpisode(getDownload(), true)
                                            }
                                            R.id.res_stopdload -> {
                                                DownloadManager.invokeDownloadAction(
                                                    child.internalId,
                                                    DownloadManager.DownloadStatusType.IsStoped
                                                )
                                                deleteFile()
                                            }
                                        }
                                        return@setOnMenuItemClickListener true
                                    }
                                    popup.inflate(R.menu.resume_menu)
                                } else {
                                    popup.setOnMenuItemClickListener {
                                        when (it.itemId) {
                                            R.id.stop_pauseload -> {
                                                DownloadManager.invokeDownloadAction(
                                                    child.internalId,
                                                    DownloadManager.DownloadStatusType.IsPaused
                                                )
                                            }
                                            R.id.stop_stopdload -> {
                                                DownloadManager.invokeDownloadAction(
                                                    child.internalId,
                                                    DownloadManager.DownloadStatusType.IsStoped
                                                )
                                                deleteFile()
                                            }
                                        }
                                        return@setOnMenuItemClickListener true
                                    }
                                    popup.inflate(R.menu.stop_menu)
                                }
                                popup.show()
                            }

                            card.progressBar.progress = maxOf(minOf(localBytesTotal * 100 / megaBytesTotal, 100), 0)

                            DownloadManager.downloadPauseEvent += {
                                if (it == child.internalId) {
                                    setStatus()
                                }
                            }

                            DownloadManager.downloadDeleteEvent += {
                                if (it == child.internalId) {
                                    deleteFile()
                                }
                            }

                            DownloadManager.downloadEvent += {
                                activity?.runOnUiThread {
                                    if (it.id == child.internalId) {
                                        val megaBytes = DownloadManager.convertBytesToAny(it.bytes, 0, 2.0).toInt()
                                        //card.cardTitleExtra.text = "${megaBytes} / $megaBytesTotal MB"
                                        card.progressBar.progress = maxOf(
                                            minOf(megaBytes * 100 / megaBytesTotal, 100),
                                            0
                                        )
                                        updateIcon(megaBytes)
                                    }
                                }
                            }
                            // END DOWNLOAD
                        }
                    }
                }
                title_season_cards.addView(card)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PlayerFragment.onLeftPlayer -= ::onLeftVideoPlayer
        DownloadManager.downloadStartEvent -= ::onDownloadStarted
        isInResults = false
    }

    fun onLeftVideoPlayer(event: Boolean) {
        //loadSeason(currentSeasonIndex)
    }

    fun onDownloadStarted(anilistId: String) {
        /*if (anilistId == data!!.anilistId) {
            activity?.runOnUiThread {
                loadSeason(currentSeasonIndex)
            }
        }*/
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        hideKeyboard()
        //title_duration.text = data!!.duration.toString() + "min"

        val mMediaRouteButton = view.findViewById<MediaRouteButton>(R.id.media_route_button)

        CastButtonFactory.setUpMediaRouteButton(activity, mMediaRouteButton);
        val castContext = CastContext.getSharedInstance(activity!!.applicationContext)

        if (castContext.castState != CastState.NO_DEVICES_AVAILABLE) media_route_button.visibility = View.VISIBLE
        castContext.addCastStateListener(CastStateListener { state ->
            if (media_route_button != null) {
                if (state == CastState.NO_DEVICES_AVAILABLE) media_route_button.visibility = View.GONE else {
                    if (media_route_button.visibility == View.GONE) media_route_button.visibility = View.VISIBLE
                }
            }
        })
        isInResults = true
        //isViewState = false
        PlayerFragment.onLeftPlayer += ::onLeftVideoPlayer
        DownloadManager.downloadStartEvent += ::onDownloadStarted
        toggleHeartVisual(isBookmarked)

        title_go_back_holder.setPadding(0, MainActivity.statusHeight, 0, 0)
        media_route_button_holder.setPadding(0, MainActivity.statusHeight, 0, 0)
        //media_route_button.layoutParams = LinearLayout.LayoutParams(20.toPx, 20.toPx + MainActivity.statusHeight)  //setPadding(0, MainActivity.statusHeight, 0, 0)
        title_go_back.setOnClickListener {
            MainActivity.popCurrentPage()
        }

        bookmark_holder.setOnClickListener {
            toggleHeart(!isBookmarked)
        }

        /*
        title_viewstate.setOnClickListener {
            ToggleViewState(!isViewState)
        }*/

        view.setOnTouchListener { _, _ -> return@setOnTouchListener true } // VERY IMPORTANT https://stackoverflow.com/questions/28818926/prevent-clicking-on-a-button-in-an-activity-while-showing-a-fragment

        title_holder.setPadding(
            title_holder.paddingLeft,
            MainActivity.statusHeight + title_background.minimumHeight - 44.toPx,
            title_holder.paddingRight,
            0,
        )

        val glideUrl =
            GlideUrl(data!!.posterUrl)

        /*if (data!!.trailer != null) {
            title_background.setOnLongClickListener {
                Toast.makeText(context, data!!.title.english + " - Trailer", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }

            title_background.setOnClickListener() {
                MainActivity.loadPlayer(
                    data!!.title.english + " - Trailer",
                    "https://fastani.net/" + data!!.trailer!!,
                    null
                )
            }
        } else {
        }
*/
        title_trailer_btt.alpha = 0f
        // SEASON SELECTOR
        loadSeason()

        context?.let {
            GlideApp.with(it)
                .load(glideUrl)
                .into(title_background)
        }

        title_name.text = data!!.title
        val descript = data!!.description
            .replace("<br>", "")
            .replace("<i>", "")
            .replace("</i>", "")
            .replace("\n", " ")

        title_descript.text = descript.substring(0, minOf(descript.length, DESCRIPTION_LENGTH1 - 3)) + "..."
        title_descript.setOnClickListener {
            val transition: Transition = ChangeBounds()
            transition.duration = 100
            if (title_descript.text.length == 200) {
                title_descript.text = descript
            } else {
                title_descript.text = descript.substring(0, minOf(descript.length, DESCRIPTION_LENGTH1 - 3)) + "..."
            }
            TransitionManager.beginDelayedTransition(title_holder, transition)
        }

        /*var ratTxt = (data!!.averageScore / 10f).toString().replace(',', '.') // JUST IN CASE DUE TO LANG SETTINGS
        if (!ratTxt.contains('.')) ratTxt += ".0"
        title_rating.text = "Rated: $ratTxt"
        */
        title_genres.text = data!!.genres
    }
}
