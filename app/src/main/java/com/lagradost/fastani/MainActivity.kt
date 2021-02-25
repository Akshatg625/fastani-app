package com.lagradost.fastani

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.InputMethodManager
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlin.concurrent.thread

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.fastani.FastAniApi.Companion.getCardById
import com.lagradost.fastani.FastAniApi.Companion.getDonorStatus
import com.lagradost.fastani.ui.PlayerData
import com.lagradost.fastani.ui.PlayerEventType
import com.lagradost.fastani.ui.PlayerFragment
import com.lagradost.fastani.ui.PlayerFragment.Companion.isInPlayer
import com.lagradost.fastani.ui.result.ShiroResultFragment.Companion.isInResults
import java.lang.Exception
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

val Int.toPx: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()
val Int.toDp: Int get() = (this / Resources.getSystem().displayMetrics.density).toInt()

data class EpisodePosDurInfo(
    @JsonProperty("pos") val pos: Long,
    @JsonProperty("dur") val dur: Long,
    @JsonProperty("viewstate") val viewstate: Boolean,
)

data class LastEpisodeInfo(
    @JsonProperty("pos") val pos: Long,
    @JsonProperty("dur") val dur: Long,
    @JsonProperty("seenAt") val seenAt: Long,
    @JsonProperty("id") val id: String,
    @JsonProperty("aniListId") val aniListId: String,
    @JsonProperty("episodeIndex") val episodeIndex: Int,
    @JsonProperty("seasonIndex") val seasonIndex: Int,
    @JsonProperty("isMovie") val isMovie: Boolean,
    @JsonProperty("episode") val episode: FastAniApi.FullEpisode,
    @JsonProperty("coverImage") val coverImage: FastAniApi.CoverImage,
    @JsonProperty("title") val title: FastAniApi.Title,
    @JsonProperty("bannerImage") val bannerImage: String,
)

data class NextEpisode(
    @JsonProperty("isFound") val isFound: Boolean,
    @JsonProperty("episodeIndex") val episodeIndex: Int,
    @JsonProperty("seasonIndex") val seasonIndex: Int,
)

data class BookmarkedTitle(
    @JsonProperty("id") val id: String,
    @JsonProperty("anilistId") val anilistId: String,
    @JsonProperty("description") val description: String,
    @JsonProperty("title") val title: FastAniApi.Title,
    @JsonProperty("coverImage") val coverImage: FastAniApi.CoverImage,
)

class MainActivity : AppCompatActivity() {
    companion object {
        var isInPIPMode = false
        var navController: NavController? = null
        var statusHeight: Int = 0
        var activity: MainActivity? = null
        var canShowPipMode: Boolean = false
        var isDonor: Boolean = false

        var onPlayerEvent = Event<PlayerEventType>()
        var onAudioFocusEvent = Event<Boolean>()

        var focusRequest: AudioFocusRequest? = null

        fun UnixTime(): Long {
            return System.currentTimeMillis() / 1000L
        }

        fun getViewKey(data: PlayerData): String {
            return getViewKey(
                if (data.card != null) data.card.anilistId else data.anilistId!!,
                data.seasonIndex!!,
                data.episodeIndex!!
            )
        }

        fun getViewKey(aniListId: String, seasonIndex: Int, episodeIndex: Int): String {
            return aniListId + "S" + seasonIndex + "E" + episodeIndex
        }

        fun Context.hideKeyboard(view: View) {
            val inputMethodManager = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }

        fun Fragment.hideKeyboard() {
            view.let {
                if (it != null) {
                    activity?.hideKeyboard(it)
                }
            }
        }

        fun requestAudioFocus() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                val audioManager = activity!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.requestAudioFocus(focusRequest!!)
            } else {
                val audioManager: AudioManager =
                    activity?.getSystemService(Context.AUDIO_SERVICE) as AudioManager;
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }

        fun changeStatusBarState(hide: Boolean) {
            statusHeight = if (hide) {
                activity!!.window.setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                )
                0
            } else {
                activity!!.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                activity!!.getStatusBarHeight()
            }
        }

        fun openBrowser(url: String) {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            activity!!.startActivity(intent)
        }

        fun getViewPosDur(aniListId: String, seasonIndex: Int, episodeIndex: Int): EpisodePosDurInfo {
            val key = getViewKey(aniListId, seasonIndex, episodeIndex)

            return EpisodePosDurInfo(
                DataStore.getKey<Long>(VIEW_POS_KEY, key, -1L)!!,
                DataStore.getKey<Long>(VIEW_DUR_KEY, key, -1L)!!,
                DataStore.containsKey(VIEWSTATE_KEY, key)
            )
        }

        fun canPlayNextEpisode(card: FastAniApi.Card, seasonIndex: Int, episodeIndex: Int): NextEpisode {
            val canNext = card.cdnData.seasons[seasonIndex].episodes.size > (episodeIndex + 1)

            return if (!canNext) {
                if (card.cdnData.seasons.size > (seasonIndex + 1)) {
                    NextEpisode(true, 0, seasonIndex + 1)
                } else {
                    NextEpisode(false, 0, 0)
                }
            } else {
                NextEpisode(true, episodeIndex + 1, seasonIndex)
            }
        }

        fun getNextEpisode(data: FastAniApi.Card): NextEpisode {
            // HANDLES THE LOGIC FOR NEXT EPISODE
            var episodeIndex = 0
            var seasonIndex = 0
            val maxValue = 90
            val firstPos = getViewPosDur(data.anilistId, 0, 0)
            // Hacky but works :)
            if (((firstPos.pos * 100) / firstPos.dur <= maxValue || firstPos.pos == -1L) && !firstPos.viewstate) {
                return NextEpisode(true, episodeIndex, seasonIndex)
            }

            while (true) { // IF PROGRESS IS OVER 95% CONTINUE SEARCH FOR NEXT EPISODE
                val next = canPlayNextEpisode(data, seasonIndex, episodeIndex)
                if (next.isFound) {
                    val nextPro = getViewPosDur(data.anilistId, next.seasonIndex, next.episodeIndex)
                    seasonIndex = next.seasonIndex
                    episodeIndex = next.episodeIndex
                    if (((nextPro.pos * 100) / nextPro.dur <= maxValue || nextPro.pos == -1L) && !nextPro.viewstate) {
                        return NextEpisode(true, episodeIndex, seasonIndex)
                    }
                } else {
                    return NextEpisode(true, episodeIndex, seasonIndex)
                }
            }
        }


        fun setViewPosDur(data: PlayerData, pos: Long, dur: Long) {
            val key = getViewKey(data)
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(activity)

            if (settingsManager.getBoolean("save_history", true)) {
                DataStore.setKey(VIEW_POS_KEY, key, pos)
                DataStore.setKey(VIEW_DUR_KEY, key, dur)
            }

            if (data.card == null) return

            // HANDLES THE LOGIC FOR NEXT EPISODE
            var episodeIndex = data.episodeIndex!!
            var seasonIndex = data.seasonIndex!!
            val maxValue = 90
            var canContinue: Boolean = (pos * 100 / dur) > maxValue
            var isFound: Boolean = true
            var _pos = pos
            var _dur = dur

            val card = data.card
            while (canContinue) { // IF PROGRESS IS OVER 95% CONTINUE SEARCH FOR NEXT EPISODE
                val next = canPlayNextEpisode(card, seasonIndex, episodeIndex)
                if (next.isFound) {
                    val nextPro = getViewPosDur(card.anilistId, next.seasonIndex, next.episodeIndex)
                    seasonIndex = next.seasonIndex
                    episodeIndex = next.episodeIndex
                    if ((nextPro.pos * 100) / dur <= maxValue) {
                        _pos = nextPro.pos
                        _dur = nextPro.dur
                        canContinue = false
                        isFound = true
                    }
                } else {
                    canContinue = false
                    isFound = false
                }
            }

            if (!isFound) return

            if (settingsManager.getBoolean("save_history", true)) {
                DataStore.setKey(
                    VIEW_LST_KEY,
                    data.card.anilistId,
                    LastEpisodeInfo(
                        _pos,
                        _dur,
                        System.currentTimeMillis(),
                        card.id,
                        card.anilistId,
                        episodeIndex,
                        seasonIndex,
                        data.card.episodes == 1 && data.card.status == "FINISHED",
                        card.cdnData.seasons[seasonIndex].episodes[episodeIndex],
                        card.coverImage,
                        card.title,
                        card.bannerImage,
                    )
                )

                thread {
                    FastAniApi.requestHome(true)
                }
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

        fun popCurrentPage() {
            println("POPP")
            val currentFragment = activity?.supportFragmentManager!!.fragments.last {
                it.isVisible
            }

            val settingsManager = PreferenceManager.getDefaultSharedPreferences(activity)
            if (settingsManager.getBoolean("rotation_enabled", false)) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            } else {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }


            // No fucked animations leaving the player :)
            if (isInPlayer) {
                activity?.supportFragmentManager!!.beginTransaction()
                    //.setCustomAnimations(R.anim.enter, R.anim.exit, R.anim.pop_enter, R.anim.pop_exit)
                    .remove(currentFragment).commit()
            } else {
                activity?.supportFragmentManager!!.beginTransaction()
                    .setCustomAnimations(R.anim.enter, R.anim.exit, R.anim.pop_enter, R.anim.pop_exit)
                    .remove(currentFragment).commit()
            }
        }

        fun hideSystemUI() {
            // Enables regular immersive mode.
            // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
            // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            activity!!.window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    // Set the content to appear under the system bars so that the
                    // content doesn't resize when the system bars hide and show.
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    // Hide the nav bar and status bar
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
        }

        fun String.md5(): String {
            return hashString(this, "MD5")
        }

        fun String.sha256(): String {
            return hashString(this, "SHA-256")
        }

        private fun hashString(input: String, algorithm: String): String {
            return MessageDigest
                .getInstance(algorithm)
                .digest(input.toByteArray())
                .fold("", { str, it -> str + "%02x".format(it) })
        }

        // Shows the system bars by removing all the flags
// except for the ones that make the content appear under the system bars.
        fun showSystemUI() {
            activity!!.window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }

        fun loadPlayer(episodeIndex: Int, seasonIndex: Int, card: FastAniApi.Card) {
            loadPlayer(
                PlayerData(
                    null, null,
                    episodeIndex,
                    seasonIndex,
                    card,
                    null,
                    null
                )
            )
        }

        fun loadPlayer(title: String?, url: String, startAt: Long?) {
            loadPlayer(PlayerData(title, url, null, null, null, startAt, null))
        }

        fun loadPlayer(data: PlayerData) {
            activity?.supportFragmentManager?.beginTransaction()
                ?.setCustomAnimations(R.anim.enter, R.anim.exit, R.anim.pop_enter, R.anim.pop_exit)
                ?.add(
                    R.id.videoRoot, PlayerFragment.newInstance(
                        data
                    )
                )
                ?.commit()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }

        fun loadPage(card: FastAniApi.Card) {

            activity?.supportFragmentManager?.beginTransaction()
                ?.setCustomAnimations(R.anim.enter, R.anim.exit, R.anim.pop_enter, R.anim.pop_exit)
                ?.add(R.id.homeRoot, ResultFragment.newInstance(card))
                ?.commit()
            /*
            activity?.runOnUiThread {
                val _navController = Navigation.findNavController(activity!!, R.id.nav_host_fragment)
                _navController?.navigateUp()
                _navController?.navigate(R.layout.fragment_results,null,null)
            }
*/
            // NavigationUI.navigateUp(navController!!,R.layout.fragment_results)
        }

        @ColorInt
        fun Context.getColorFromAttr(
            @AttrRes attrColor: Int,
            typedValue: TypedValue = TypedValue(),
            resolveRefs: Boolean = true,
        ): Int {
            theme.resolveAttribute(attrColor, typedValue, resolveRefs)
            return typedValue.data
        }

    }

    // AUTH FOR LOGIN
    override fun onNewIntent(intent: Intent?) {
        if (intent != null) {
            val dataString = intent.dataString
            if (dataString != null && dataString != "") {
                println("GOT fastaniapp auth" + dataString)
                if (dataString.contains("fastaniapp")) {
                    if (dataString.contains("/anilistlogin")) {
                        AniListApi.authenticateLogin(dataString)
                    } else if (dataString.contains("/mallogin")) {
                        MALApi.authenticateLogin(dataString)
                    }
                }
            }
        }

        super.onNewIntent(intent)
    }

    override fun onBackPressed() {
        println("BACK PRESSED!!!!")

        if (isInResults || isInPlayer) {
            popCurrentPage()
        } else {
            super.onBackPressed()
        }
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    private fun enterPIPMode() {
        if (!shouldShowPIPMode() || !canShowPipMode) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            } catch (e: Exception) {
                enterPictureInPictureMode()
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                enterPictureInPictureMode()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPIPMode()
    }

    override fun onRestart() {
        super.onRestart()
        if (isInPlayer) {
            hideSystemUI()
        }
    }

    override fun onResume() {
        super.onResume()
        println("RESUMED!!!")
        // This is needed to avoid NPE crash due to missing context
        DataStore.init(this)
        DownloadManager.init(this)
        if (isInPlayer) {
            hideSystemUI()
        }
    }

    private fun shouldShowPIPMode(): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(activity)
        return settingsManager.getBoolean("pip_enabled", true) && isInPlayer
    }

    private fun hasPIPPermission(): Boolean {
        val appOps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        } else {
            return false
        }
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private val callbacks = object : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            if (mediaButtonEvent != null) {

                val event = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) as KeyEvent
                println("EVENT: " + event.keyCode)
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> onPlayerEvent.invoke(PlayerEventType.Pause)
                    KeyEvent.KEYCODE_MEDIA_PLAY -> onPlayerEvent.invoke(PlayerEventType.Play)
                    KeyEvent.KEYCODE_MEDIA_STOP -> onPlayerEvent.invoke(PlayerEventType.Pause)
                    KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> onPlayerEvent.invoke(PlayerEventType.SeekForward)
                    KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> onPlayerEvent.invoke(PlayerEventType.SeekBack)
                    KeyEvent.KEYCODE_HEADSETHOOK -> onPlayerEvent.invoke(PlayerEventType.Pause)
                }
            }
            return super.onMediaButtonEvent(mediaButtonEvent)
        }

        override fun onPlay() {
            onPlayerEvent.invoke(PlayerEventType.Play)
        }

        override fun onStop() {
            onPlayerEvent.invoke(PlayerEventType.Pause)
        }
    }

    private val myAudioFocusListener =
        AudioManager.OnAudioFocusChangeListener {
            onAudioFocusEvent.invoke(
                when (it) {
                    AudioManager.AUDIOFOCUS_GAIN -> true
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> true
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> true
                    else -> false
                }
            )
        }

    override fun onDestroy() {
        mediaSession?.isActive = false
        super.onDestroy()
    }

    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        activity = this
        DataStore.init(this)
        DownloadManager.init(this)

        @SuppressLint("HardwareIds")
        val androidId: String = Settings.Secure.getString(activity?.contentResolver, Settings.Secure.ANDROID_ID).md5()
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(activity)
        if (settingsManager.getBoolean("rotation_enabled", false)) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        thread {
            FastAniApi.init()
        }
        thread {
            // Developers please do not share an apk with donor mode enabled for all as fastani relies on donors to keep the site alive and ad-free.
            isDonor = getDonorStatus() == androidId
        }
        //https://stackoverflow.com/questions/29146757/set-windowtranslucentstatus-true-when-android-lollipop-or-higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            )
        }
        if (settingsManager.getBoolean("cool_mode", false)) {
            theme.applyStyle(R.style.OverlayPrimaryColorBlue, true)
        }
        changeStatusBarState(settingsManager.getBoolean("statusbar_hidden", true))
        //window.statusBarColor = R.color.transparent

        //https://stackoverflow.com/questions/52594181/how-to-know-if-user-has-disabled-picture-in-picture-feature-permission
        //https://developer.android.com/guide/topics/ui/picture-in-picture

        //val action: String? = intent?.action


        canShowPipMode =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && // OS SUPPORT
                    packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) && // HAS FEATURE, MIGHT BE BLOCKED DUE TO POWER DRAIN
                    hasPIPPermission() // CHECK IF FEATURE IS ENABLED IN SETTINGS


        // CRASHES ON 7.0.0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(AudioAttributes.Builder().run {
                    setUsage(AudioAttributes.USAGE_MEDIA)
                    setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    build()
                })
                setAcceptsDelayedFocusGain(true)
                setOnAudioFocusChangeListener(myAudioFocusListener)
                build()
            }
        }

        // Setting the theme
        /*
    val autoDarkMode = settingsManager.getBoolean("auto_dark_mode", true)
    val darkMode = settingsManager.getBoolean("dark_mode", false)

    if (autoDarkMode) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    } else {
        if (darkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }*/
        mediaSession = MediaSessionCompat(activity!!, "fastani").apply {

            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            // Do not let MediaButtons restart the player when the app is not visible
            setMediaButtonReceiver(null)

            // Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PLAY_PAUSE)
            setPlaybackState(stateBuilder.build())

            // MySessionCallback has methods that handle callbacks from a media controller
            setCallback(callbacks)
        }

        mediaSession!!.isActive = true

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        val navView: BottomNavigationView = findViewById(R.id.nav_view)
        navController = findNavController(R.id.nav_host_fragment)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home, R.id.navigation_search, R.id.navigation_downloads, R.id.navigation_settings
            )
        )
        //setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController!!)

        window.setBackgroundDrawableResource(R.color.background);
        //val castContext = CastContext.getSharedInstance(activity!!.applicationContext)
        val data: Uri? = intent?.data

        if (data != null) {
            val dataString = data.toString()
            if (dataString != "") {
                println("GOT fastaniapp auth awake: " + dataString)
                if (dataString.contains("fastaniapp")) {
                    if (dataString.contains("/anilistlogin")) {
                        AniListApi.authenticateLogin(dataString)
                    } else if (dataString.contains("/mallogin")) {
                        MALApi.authenticateLogin(dataString)
                    }
                }
            }

            thread {
                val urlRegex = Regex("""fastani\.net\/watch\/(.*?)\/(\d+)\/(\d+)""")
                val found = urlRegex.find(data.toString())
                if (found != null) {
                    val (id, season, episode) = found.destructured
                    println("$id $season $episode")
                    val card = getCardById(id)
                    if (card?.anime?.cdnData?.seasons?.getOrNull(season.toInt() - 1) != null) {
                        if (card.anime.cdnData.seasons[season.toInt() - 1].episodes.getOrNull(episode.toInt() - 1) != null) {
                            loadPlayer(episode.toInt() - 1, season.toInt() - 1, card.anime)
                        }
                    }
                }
            }
        } else {
            AniListApi.initGetUser()
        }
    }
}
