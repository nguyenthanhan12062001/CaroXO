package com.game.caroxo.an.ui

import android.content.Context
import android.view.LayoutInflater
import com.game.caroxo.an.databinding.DialogAboutBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

object AboutDialogHelper {
    fun show(context: Context) {
        val sheet = BottomSheetDialog(context)
        val binding = DialogAboutBinding.inflate(LayoutInflater.from(context))
        binding.btnCloseAbout.setOnClickListener {
            sheet.dismiss()
        }
        sheet.setContentView(binding.root)
        sheet.show()
    }
}
