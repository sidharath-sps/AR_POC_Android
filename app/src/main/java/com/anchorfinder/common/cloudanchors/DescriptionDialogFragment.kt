package com.anchorfinder.common.cloudanchors

import androidx.fragment.app.DialogFragment

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog

class DescriptionDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireActivity())
        val description = arguments?.getString("description") ?: "No description available"

        return builder
            .setTitle("Anchor Description")
            .setMessage(description)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .create()
    }

    companion object {
        fun newInstance(description: String): DescriptionDialogFragment {
            val fragment = DescriptionDialogFragment()
            val args = Bundle()
            args.putString("description", description)
            fragment.arguments = args
            return fragment
        }
    }
}
