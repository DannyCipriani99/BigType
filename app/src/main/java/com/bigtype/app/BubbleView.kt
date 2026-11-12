package com.bigtype.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * الكرة العائمة. تقرأ مظهرها من [Prefs]: الحجم، اللون، الشفافية، الشكل، الحدود،
 * أو صورة / GIF / فيديو MP4 كخلفية.
 * حجم العنصر الخارجي = مساحة اللمس (28dp على الأقل)، والمرئي في الوسط.
 */
class BubbleView(context: Context) : FrameLayout(context) {

    companion object {
        fun visualPx(c: Context): Int =
            (Prefs.bubbleSize(c) * c.resources.displayMetrics.density).toInt()

        fun touchPx(c: Context): Int =
            max(visualPx(c), (28 * c.resources.displayMetrics.density).toInt())

        fun mediaFile(c: Context): File = File(c.filesDir, "bubble_media")
    }

    private val holder = FrameLayout(context)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    init {
        contentDescription = "فتح المحرر الكبير"
        addView(holder)
        setup()
    }

    fun setup() {
        release()
        holder.removeAllViews()
        val c = context
        val d = c.resources.displayMetrics.density
        val vis = visualPx(c)
        val round = Prefs.bubbleRound(c)
        val color = Prefs.bubbleColor(c)

        holder.layoutParams = LayoutParams(vis, vis, Gravity.CENTER)
        holder.alpha = Prefs.bubbleOpacity(c) / 100f

        val bg = GradientDrawable()
        bg.shape = if (round) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
        if (round) bg.cornerRadius = vis * 0.28f
        bg.setColor(color)
        if (Prefs.bubbleBorder(c)) bg.setStroke(max(1, (1.5f * d).toInt()), Color.WHITE)
        holder.background = bg
        holder.clipToOutline = true
        holder.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (round) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.28f)
                } else {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }

        val shown = when (Prefs.mediaType(c)) {
            "image" -> addImage(vis, false)
            "gif" -> addImage(vis, true)
            "video" -> addVideo(vis)
            else -> false
        }
        if (!shown && Prefs.bubbleIcon(c) && vis >= 18 * d) {
            val iv = ImageView(c)
            iv.setImageResource(R.drawable.ic_edit)
            val lum = (0.299 * Color.red(color) + 0.587 * Color.green(color) +
                0.114 * Color.blue(color)) / 255.0
            iv.setColorFilter(if (lum > 0.65) Color.BLACK else Color.WHITE, PorterDuff.Mode.SRC_IN)
            val s = (vis * 0.55f).toInt()
            holder.addView(iv, LayoutParams(s, s, Gravity.CENTER))
        }
    }

    private fun addImage(vis: Int, gif: Boolean): Boolean {
        val f = mediaFile(context)
        if (!f.exists()) return false
        val iv = ImageView(context)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        try {
            if (gif && Build.VERSION.SDK_INT >= 28) {
                val dr = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                    val s = max(1, min(info.size.width, info.size.height) / (vis * 2))
                    dec.setTargetSampleSize(s)
                }
                iv.setImageDrawable(dr)
                if (dr is AnimatedImageDrawable) {
                    dr.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    dr.start()
                }
            } else {
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeFile(f.path, o)
                var s = 1
                while (min(o.outWidth, o.outHeight) / (s * 2) >= vis * 2) s *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = s
                val bmp = BitmapFactory.decodeFile(f.path, o2) ?: return false
                iv.setImageBitmap(bmp)
            }
        } catch (e: Exception) {
            return false
        }
        holder.addView(iv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return true
    }

    private fun addVideo(vis: Int): Boolean {
        val f = mediaFile(context)
        if (!f.exists()) return false
        val tv = TextureView(context)
        holder.addView(tv, LayoutParams(vis, vis))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                try {
                    val sf = Surface(st)
                    surface = sf
                    val mp = MediaPlayer()
                    mp.setDataSource(f.absolutePath)
                    mp.setSurface(sf)
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)
                    mp.setOnVideoSizeChangedListener { _, vw, vh -> cropToFill(tv, vw, vh, vis) }
                    mp.setOnPreparedListener { it.start() }
                    mp.setOnErrorListener { _, _, _ -> true }
                    mp.prepareAsync()
                    player = mp
                } catch (e: Exception) {
                    release()
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                release()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        return true
    }

    /** يملأ المربع بالفيديو (قص من الأطراف) بدل تمديده. */
    private fun cropToFill(tv: TextureView, vw: Int, vh: Int, vis: Int) {
        if (vw <= 0 || vh <= 0) return
        val s = max(vis.toFloat() / vw, vis.toFloat() / vh)
        val m = Matrix()
        m.setScale(vw * s / vis, vh * s / vis, vis / 2f, vis / 2f)
        tv.setTransform(m)
    }

    fun release() {
        try {
            player?.stop()
        } catch (_: Exception) {}
        try {
            player?.release()
        } catch (_: Exception) {}
        player = null
        try {
            surface?.release()
        } catch (_: Exception) {}
        surface = null
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
