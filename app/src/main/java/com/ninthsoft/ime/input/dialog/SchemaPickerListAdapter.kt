package com.ninthsoft.ime.input.dialog

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.EngineMessage

class SchemaPickerListAdapter(
    private val schemas: List<EngineMessage.Schema>,
    private var selectedIndex: Int,
    private val colors: KeyboardColors.ColorScheme,
    private val onSchemaClick: (EngineMessage.Schema) -> Unit,
) : RecyclerView.Adapter<SchemaPickerListAdapter.Holder>() {

    inner class Holder(val ui: SchemaPickerEntryUi) : RecyclerView.ViewHolder(ui.root)

    override fun getItemCount(): Int = schemas.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ui = SchemaPickerEntryUi(parent.context, colors)
        return Holder(ui)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val schema = schemas[position]
        holder.ui.bind(schema.name, position == selectedIndex)
        holder.ui.root.setOnClickListener {
            onSchemaClick(schema)
        }
    }

    fun setSelected(position: Int) {
        if (position == selectedIndex) return
        val old = selectedIndex
        selectedIndex = position
        notifyItemChanged(old)
        notifyItemChanged(position)
    }
}
