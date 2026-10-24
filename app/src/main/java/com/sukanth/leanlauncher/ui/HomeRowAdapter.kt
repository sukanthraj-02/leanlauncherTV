package com.sukanth.leanlauncher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.sukanth.leanlauncher.R
import com.sukanth.leanlauncher.data.AppEntry
import com.sukanth.leanlauncher.data.IconLoader
import com.sukanth.leanlauncher.databinding.ItemAppBinding
import com.sukanth.leanlauncher.databinding.ItemSectionBinding

/**
 * Renders the launcher grid, which mixes full-width section headings with app tiles.
 *
 * One adapter serves all three tabs, so only a single view pool and icon loader exist.
 */
class HomeRowAdapter(
    context: Context,
    private val iconLoader: IconLoader,
    private val onOpen: (AppEntry) -> Unit,
    private val onContext: (AppEntry) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val iconSizePx = context.resources.getDimensionPixelSize(R.dimen.tile_icon)

    private var rows: List<HomeRow> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun submit(newRows: List<HomeRow>) {
        if (rows == newRows) return
        val oldRows = rows
        rows = newRows
        // A diff rebinds only changed tiles and keeps focus across a hide or a pin, which
        // a full notifyDataSetChanged would throw away (and re-decode every icon for).
        DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldRows.size

                override fun getNewListSize(): Int = newRows.size

                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val old = oldRows[oldPos]
                    val new = newRows[newPos]
                    return when {
                        old is HomeRow.Section && new is HomeRow.Section -> old.title == new.title
                        old is HomeRow.Tile && new is HomeRow.Tile -> old.entry.key == new.entry.key
                        else -> false
                    }
                }

                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    oldRows[oldPos] == newRows[newPos]
            },
            false,
        ).dispatchUpdatesTo(this)
    }

    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is HomeRow.Section

    /** The app under [position], or null when it is a section heading. */
    fun tileAt(position: Int): AppEntry? =
        (rows.getOrNull(position) as? HomeRow.Tile)?.entry

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (isHeader(position)) TYPE_SECTION else TYPE_TILE

    override fun getItemId(position: Int): Long {
        val row = rows.getOrNull(position) ?: return position.toLong()
        return when (row) {
            // Section ids must stay stable when rows are inserted or removed, or RecyclerView
            // would animate a heading as if it were a different item.
            is HomeRow.Section -> Long.MIN_VALUE + row.title.hashCode()
            is HomeRow.Tile -> row.entry.key.hashCode().toLong()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SECTION) {
            SectionHolder(ItemSectionBinding.inflate(inflater, parent, false))
        } else {
            TileHolder(ItemAppBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HomeRow.Section -> (holder as SectionHolder).bind(row)
            is HomeRow.Tile -> (holder as TileHolder).bind(row.entry)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is TileHolder) holder.clear()
        super.onViewRecycled(holder)
    }

    inner class SectionHolder(private val binding: ItemSectionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HomeRow.Section) {
            binding.sectionTitle.text = row.title
        }
    }

    inner class TileHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /**
         * The app this holder currently draws.
         *
         * Kept as the entry itself. The adapter used to hold a `package/activity` map and
         * look each click up again, which kept a second index of every app alive plus one
         * map entry per tile, and could hand back a different instance than the one bound.
         */
        private var entry: AppEntry? = null

        init {
            binding.root.isFocusable = true
            binding.root.isLongClickable = true
            binding.root.onFocusChangeListener = View.OnFocusChangeListener { target, hasFocus ->
                val scale = if (hasFocus) FOCUS_SCALE else 1f
                target.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_ANIMATION_MS).start()
            }
            binding.root.setOnClickListener { entry?.let(onOpen) }
            binding.root.setOnLongClickListener {
                val target = entry
                if (target == null) {
                    false
                } else {
                    onContext(target)
                    true
                }
            }
        }

        fun bind(entry: AppEntry) {
            this.entry = entry
            binding.appLabel.text = entry.label
            iconLoader.load(entry, iconSizePx, binding.appIcon)
        }

        fun clear() {
            entry = null
            binding.appIcon.setImageDrawable(null)
            binding.appIcon.setTag(R.id.tag_icon_key, null)
        }
    }

    private companion object {
        const val TYPE_SECTION = 0
        const val TYPE_TILE = 1
        const val FOCUS_SCALE = 1.06f
        const val FOCUS_ANIMATION_MS = 150L
    }
}