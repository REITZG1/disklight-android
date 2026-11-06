package org.filelight.android

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Adapter daftar isi folder: ikon, nama, bar kontribusi, ukuran, persen.
 */
class ChildAdapter(
    private val context: Context
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private var items: List<Node> = emptyList()
    private var totalSize: Long = 0L

    fun setData(items: List<Node>, totalSize: Long) {
        this.items = items
        this.totalSize = totalSize
        notifyDataSetChanged()
    }

    fun clear() {
        items = emptyList()
        totalSize = 0L
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Node = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row: View
        val holder: ViewHolder
        if (convertView == null) {
            row = inflater.inflate(R.layout.item_child, parent, false)
            holder = ViewHolder()
            holder.icon = row.findViewById(R.id.itemIcon)
            holder.name = row.findViewById(R.id.itemName)
            holder.bar = row.findViewById(R.id.itemBar)
            holder.size = row.findViewById(R.id.itemSize)
            holder.share = row.findViewById(R.id.itemShare)
            row.tag = holder
        } else {
            row = convertView
            holder = row.tag as ViewHolder
        }

        val item = items[position]
        holder.name.text = if (item.synthetic) item.name else item.name
        holder.size.text = SizeUtil.format(item.size)

        val fraction = if (totalSize > 0) item.size.toDouble() / totalSize.toDouble() else 0.0
        holder.bar.progress = (fraction * 1000).toInt().coerceIn(0, 1000)
        holder.share.text = SizeUtil.formatPercent(fraction)

        holder.icon.setImageResource(
            when {
                item.isDirectory -> R.drawable.ic_folder
                else -> R.drawable.ic_file
            }
        )

        // Node sintetis / file kecil: pudarkan bar agar folder menonjol
        holder.bar.alpha = if (item.isDirectory) 1f else 0.55f

        return row
    }

    private class ViewHolder {
        lateinit var icon: ImageView
        lateinit var name: TextView
        lateinit var bar: ProgressBar
        lateinit var size: TextView
        lateinit var share: TextView
    }
}
