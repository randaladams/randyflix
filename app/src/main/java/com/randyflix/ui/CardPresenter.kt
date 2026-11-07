package com.randyflix.ui

import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.randyflix.R
import com.randyflix.data.ActionItem
import com.randyflix.data.Channel
import com.randyflix.data.ContinueItem
import com.randyflix.data.EpisodeRef
import com.randyflix.data.Library
import com.randyflix.data.Movie
import com.randyflix.data.Show

/** Draws posters, continue-watching tiles and the action tiles (All Movies, Search, ...). */
class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context)
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = viewHolder.view as ImageCardView
        val ctx = card.context
        val posterW = Format.dp(ctx, POSTER_W)
        val posterH = Format.dp(ctx, POSTER_H)
        val wideW = Format.dp(ctx, WIDE_W)
        val wideH = Format.dp(ctx, WIDE_H)
        card.mainImageView.setBackgroundColor(ContextCompat.getColor(ctx, R.color.card))
        card.setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)

        when (item) {
            is Movie -> {
                card.titleText = item.title
                card.contentText = listOfNotNull(item.year?.toString(), Format.status(item.id)).joinToString("  ·  ")
                card.setMainImageDimensions(posterW, posterH)
                ImageLoader.load(card.mainImageView, item.poster, posterW)
            }
            is Show -> {
                card.titleText = item.title
                val unwatched = Library.unwatchedCount(item)
                card.contentText = listOfNotNull(
                    item.year?.toString(),
                    if (unwatched > 0) "$unwatched unwatched" else "✓ Watched",
                ).joinToString("  ·  ")
                card.setMainImageDimensions(posterW, posterH)
                ImageLoader.load(card.mainImageView, item.poster, posterW)
            }
            is ContinueItem -> {
                card.setMainImageDimensions(wideW, wideH)
                val status = item.resume?.let { Format.left(it) } ?: "Next up"
                when (val t = item.target) {
                    is Movie -> {
                        card.titleText = t.title
                        card.contentText = status
                        ImageLoader.load(card.mainImageView, t.fanart ?: t.poster, wideW)
                    }
                    is EpisodeRef -> {
                        card.titleText = t.show.title
                        card.contentText = "${Format.episodeCode(t.episode.season, t.episode.episode)} · ${t.episode.title} · $status"
                        ImageLoader.load(card.mainImageView, t.episode.thumb ?: t.show.fanart ?: t.show.poster, wideW)
                    }
                }
            }
            is Channel -> {
                card.titleText = item.name
                card.contentText = item.groups.joinToString(", ").ifEmpty { "Live" }
                card.setMainImageDimensions(wideW, wideH)
                card.setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)
                ImageLoader.load(card.mainImageView, item.logo, wideW, placeholder = R.drawable.ic_tv)
            }
            is ActionItem -> {
                card.titleText = item.title
                card.contentText = item.subtitle
                card.setMainImageDimensions(wideW, wideH)
                ImageLoader.cancel(card.mainImageView)
                card.setMainImageScaleType(ImageView.ScaleType.CENTER)
                card.mainImage = ContextCompat.getDrawable(ctx, item.icon)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        ImageLoader.cancel(card.mainImageView)
        card.mainImage = null
    }

    companion object {
        const val POSTER_W = 130
        const val POSTER_H = 195
        const val WIDE_W = 240
        const val WIDE_H = 135
    }
}
