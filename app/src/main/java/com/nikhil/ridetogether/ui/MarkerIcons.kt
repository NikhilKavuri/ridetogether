package com.nikhil.ridetogether.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.nikhil.ridetogether.data.model.RiderCharacter

/**
 * Draws each rider's map marker: their character on a coloured pill with their
 * name under it.
 *
 * Two things here matter for the low-end-device requirement.
 *
 * First, these are drawn once and cached. A marker bitmap costs a few
 * milliseconds to rasterise, which is invisible once but ruinous if it happens
 * for every rider on every recomposition -- and the ride screen recomposes
 * every time anyone's position updates. The cache key is everything that can
 * change the pixels, so a cache hit is always correct.
 *
 * Second, the bitmaps are small and drawn at a fixed density-scaled size rather
 * than scaled from a large asset, which keeps both memory and the GPU upload
 * cheap on phones with little of either.
 */
object MarkerIcons {

    // Roughly 8 riders x 2 states. Bounded so it can never grow without limit.
    private val cache = LruCache<String, BitmapDescriptor>(24)

    fun forRider(
        context: Context,
        character: RiderCharacter,
        label: String,
        stale: Boolean
    ): BitmapDescriptor {
        val key = "${character.id}|$label|$stale"
        cache.get(key)?.let { return it }

        val descriptor = BitmapDescriptorFactory.fromBitmap(
            render(context, character, label, stale)
        )
        cache.put(key, descriptor)
        return descriptor
    }

    /** Call if the theme or density changes under us. */
    fun clear() = cache.evictAll()

    private fun render(
        context: Context,
        character: RiderCharacter,
        label: String,
        stale: Boolean
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        fun dp(value: Float) = value * density

        val pillHeight = dp(38f)
        val glyphSize = dp(22f)
        val labelSize = dp(11f)
        val labelGap = dp(3f)
        val padding = dp(4f)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = glyphSize
            textAlign = Paint.Align.CENTER
        }

        val trimmedLabel = label.take(10)
        val labelWidth = labelPaint.measureText(trimmedLabel)
        val pillWidth = maxOf(dp(44f), labelWidth + dp(16f))

        val labelBoxHeight = labelSize + dp(8f)
        val width = (pillWidth + padding * 2).toInt().coerceAtLeast(1)
        val height = (pillHeight + labelGap + labelBoxHeight + padding * 2).toInt()
            .coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val body = if (stale) withAlpha(character.color, 0.45f) else character.color

        // Character pill
        val pillRect = RectF(padding, padding, padding + pillWidth, padding + pillHeight)
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = body }
        canvas.drawRoundRect(pillRect, dp(12f), dp(12f), pillPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            color = if (stale) withAlpha(Color.WHITE, 0.5f) else Color.WHITE
        }
        canvas.drawRoundRect(pillRect, dp(12f), dp(12f), borderPaint)

        // Character glyph, vertically centred in the pill
        val glyphMetrics = glyphPaint.fontMetrics
        val glyphBaseline = pillRect.centerY() -
            (glyphMetrics.ascent + glyphMetrics.descent) / 2f
        canvas.drawText(character.glyph, pillRect.centerX(), glyphBaseline, glyphPaint)

        // Name plate
        val labelRect = RectF(
            padding,
            pillRect.bottom + labelGap,
            padding + pillWidth,
            pillRect.bottom + labelGap + labelBoxHeight
        )
        canvas.drawRoundRect(
            labelRect,
            dp(6f),
            dp(6f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(Color.BLACK, 0.72f) }
        )

        val labelMetrics = labelPaint.fontMetrics
        val labelBaseline = labelRect.centerY() -
            (labelMetrics.ascent + labelMetrics.descent) / 2f
        canvas.drawText(trimmedLabel, labelRect.centerX(), labelBaseline, labelPaint)

        return bitmap
    }

    private fun withAlpha(color: Int, factor: Float): Int = Color.argb(
        (Color.alpha(color) * factor).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
