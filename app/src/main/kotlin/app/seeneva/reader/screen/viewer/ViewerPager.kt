/*
 * This file is part of Seeneva Android Reader
 * Copyright (C) 2021 Sergei Solodovnikov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.seeneva.reader.screen.viewer

import android.app.ActivityManager
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.core.app.ActivityManagerCompat
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import app.seeneva.reader.R
import app.seeneva.reader.extension.recyclerView
import app.seeneva.reader.logic.entity.ComicBookPage
import org.tinylog.kotlin.Logger
import kotlin.math.abs
import kotlin.math.floor
import kotlin.properties.Delegates


/*
I was tried to modify ViewPager's inner RecyclerView to reverse using LinearLayoutManager.reverseLayout
but have exception during fling
ViewPager2 is final so I can't override isRTL method which can help me to resolve this issue
 */

/**
 * Wrapper around [ViewPager2] to make some hacky magic
 * Because [ViewPager2] is final and it is really hard to change its behaviour without reflection
 */
class ViewerPager(
    private val pager: ViewPager2,
    private val adapter: BookViewerAdapter,
    reverse: Boolean = false
) {
    private val context
        get() = pager.context

    private val activityManager by lazy { context.getSystemService<ActivityManager>()!! }

    private val callbacks =
        hashMapOf<ViewPager2.OnPageChangeCallback, ViewPager2.OnPageChangeCallback>()

    private val pagerRecyclerView by lazy { pager.recyclerView }

    /**
     * If `true` user initiated page turns (horizontal swipes) are performed instantly:
     * the page does not slide with the finger and the target page is set immediately
     * on release, without any page sliding animation
     */
    var instantPageTurns: Boolean by Delegates.observable(false) { _, old, new ->
        if (old != new) {
            pagerRecyclerView.setOnTouchListener(if (new) instantSwipeTouchListener else null)
        }
    }

    private val reversePage = { count: Int, pos: Int ->
        if (count == 0) {
            0
        } else {
            count - 1 - pos
        }
    }

    var reverse: Boolean by Delegates.observable(reverse) { _, old, new ->
        if (old != new) {
            adapter.setPositionOverrideFun(
                if (new) {
                    reversePage
                } else {
                    null
                }
            )
            // we should reverse item position
            pager.setCurrentItem(reversePage(adapter.itemCount, pager.currentItem), false)
        }
    }

    /**
     * Real pager position which depends on [reverse] value
     */
    val currentItem: Int
        get() = calculatePosition(pager.currentItem)

    val currentItemId: Long
        get() = adapter.getItemId(pager.currentItem)

    /**
     * How many pages in the adapter
     */
    val count
        get() = adapter.itemCount

    init {
        // Change pager fling velocity
        pagerRecyclerView.onFlingListener = object : RecyclerView.OnFlingListener() {
            // ViewPager2 uses SnapHelper which should set own onFlingListener
            // So receive it here and use later
            val snapFlingListener = pagerRecyclerView.onFlingListener

            // Custom required minimal fling velocity
            // see RecyclerView.minFlingVelocity or ViewConfiguration
            val requiredVelocity =
                pager.resources.getDimensionPixelSize(R.dimen.viewer_fling_velocity)

            override fun onFling(velocityX: Int, velocityY: Int): Boolean {
                // Handle fling event if its X velocity is less than required
                if (abs(velocityX) < requiredVelocity) {
                    // Hack to stay on current page
                    // ViewPager2.setCurrentItem doesn't work here. After call first touch will be ignored by inner Fragment's view
                    // So it is another way to resolve issue without calling RecyclerView.fling
                    pagerRecyclerView.smoothScrollToPosition(pager.currentItem)

                    return true
                }

                // otherwise pass arguments to SnapHelper's onFlingListener
                return snapFlingListener?.onFling(velocityX, velocityY) ?: false
            }
        }
    }

    /**
     * Touch listener which intercepts horizontal swipes on the pager to perform
     * instant page turns.
     *
     * `ACTION_DOWN` can be consumed by the page's own image view (e.g. zoomed image
     * panning), so the gesture is tracked from the first event the pager's RecyclerView
     * receives.
     */
    private val instantSwipeTouchListener = object : View.OnTouchListener {
        private var downX = Float.NaN

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                }

                MotionEvent.ACTION_MOVE -> {
                    if (downX.isNaN()) {
                        // The page image view consumed ACTION_DOWN and the RecyclerView
                        // took over the gesture. Track the swipe from here.
                        downX = e.x
                    }

                    // Consume the gesture so the pager does not slide with the finger
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!downX.isNaN()) {
                        val dx = e.x - downX

                        instantPageTurnTarget(
                            currentItem = pager.currentItem,
                            itemCount = adapter.itemCount,
                            dx = dx,
                            width = pager.width,
                            thresholdFraction = INSTANT_PAGE_TURN_THRESHOLD
                        )?.also { target ->
                            // ViewPager2.setCurrentItem is unsafe inside the touch dispatch,
                            // so apply the page jump after the event is fully processed
                            v.post { pager.setCurrentItem(target, false) }
                        }

                        downX = Float.NaN
                    }

                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    downX = Float.NaN

                    return true
                }
            }

            return false
        }
    }

    fun setCurrentItem(item: Int, smoothScroll: Boolean = true) {
        if (item == currentItem) {
            return
        }

        pager.setCurrentItem(calculatePosition(item), smoothScroll)
    }

    fun setPages(pages: List<ComicBookPage>) {
        adapter.setPages(pages)

        //set only after calling setPages!
        //Otherwise adapter will clean all fragments during state restore!
        if (pager.adapter == null) {
            // On pre Android 8 devices Bitmap was stored on Java Heap
            // Comic book pages are really heavy, so I need to limit how many pages should be stored in the pager
            // To help prevent some OOM issues
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                ActivityManagerCompat.isLowRamDevice(activityManager)
            ) {
                val pageLimit = activityManager.memoryClass.let { memoryClass ->
                    if (memoryClass <= 48) {
                        0
                    } else {
                        // Page mean size in megabytes
                        val pageMeanSize = pages.bytes / 1024 / 1024 / pages.size

                        floor(memoryClass / pageMeanSize.toFloat())
                            .toInt()
                            .coerceIn(0, 2)
                    }
                }

                // pager.offscreenPageLimit doesn't do anything for some reason
                // So I use related RecyclerView methods
                pagerRecyclerView.apply {
                    layoutManager!!.isItemPrefetchEnabled = false
                    setItemViewCacheSize(pageLimit)
                }

                Logger.debug("Viewer pager offscreen limit: $pageLimit")
            }

            pager.adapter = adapter
        }
    }

    fun registerOnPageChangeCallback(callback: ViewPager2.OnPageChangeCallback) {
        if (callbacks.containsKey(callback)) {
            return
        }

        val realCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                callback.onPageScrollStateChanged(state)
            }

            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                callback.onPageScrolled(
                    calculatePosition(position),
                    positionOffset,
                    positionOffsetPixels
                )
            }

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                callback.onPageSelected(calculatePosition(position))
            }
        }

        pager.registerOnPageChangeCallback(realCallback)

        callbacks[callback] = realCallback
    }

    fun unregisterOnPageChangeCallback(callback: ViewPager2.OnPageChangeCallback) {
        callbacks.remove(callback)?.also { pager.unregisterOnPageChangeCallback(it) }
    }

    private fun calculatePosition(pos: Int) =
        if (reverse) {
            reversePage(adapter.itemCount, pos)
        } else {
            pos
        }

    /**
     * Calculate approximate comic book page size in bytes like it was decoded using ARGB format (32 bit depth)
     */
    private val ComicBookPage.bytes: Long
        get() = width.toLong() * height * 32 / 8

    /**
     * Calculate approximate comic book pages total size in bytes like it was decoded using ARGB format (32 bit depth)
     */
    private val Iterable<ComicBookPage>.bytes
        get() = fold(0L) { acc, page -> acc.plus(page.bytes) }

    companion object {
        /**
         * Fraction of the pager width a horizontal swipe must cover to turn the page
         * in instant mode
         */
        private const val INSTANT_PAGE_TURN_THRESHOLD = 0.25f

        /**
         * Compute the target physical page position for an instant page turn.
         *
         * Works in physical pager coordinates (the same space [ViewPager2] uses), so
         * the RTL position reversal stays consistent with the animated behavior.
         *
         * @param currentItem current physical page position
         * @param itemCount total pages count
         * @param dx horizontal swipe distance in pixels (negative = swipe left)
         * @param width pager width in pixels
         * @param thresholdFraction fraction of [width] the swipe must cover to turn the page
         * @return target physical page position or null if no page change should happen
         */
        internal fun instantPageTurnTarget(
            currentItem: Int,
            itemCount: Int,
            dx: Float,
            width: Int,
            thresholdFraction: Float
        ): Int? {
            if (itemCount <= 0 || width <= 0 || abs(dx) < width * thresholdFraction) {
                return null
            }

            return (currentItem + if (dx < 0) 1 else -1).coerceIn(0, itemCount - 1)
        }
    }
}