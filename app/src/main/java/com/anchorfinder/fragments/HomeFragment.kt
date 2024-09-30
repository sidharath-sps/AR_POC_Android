package com.anchorfinder.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.anchorfinder.R
import com.anchorfinder.common.Constant
import com.anchorfinder.common.cloudanchors.FirebaseManager
import com.anchorfinder.common.cloudanchors.ResolveAnchors


class HomeFragment : Fragment() {

    private lateinit var firebaseManager: FirebaseManager
    private lateinit var anchorsToResolveList: ArrayList<ResolveAnchors>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        firebaseManager = FirebaseManager()
        firebaseManager.initFirebase(requireActivity())
        anchorsToResolveList = ArrayList()
        firebaseManager.getAnchorList {
            anchorsToResolveList.addAll(it)
        }

        view.findViewById<Button>(R.id.home_scan_button).setOnClickListener {
            val bundle = Bundle().apply {
                putBoolean(Constant.HOSTING_MODE, true)
            }
            findNavController().navigate(R.id.action_homeFragment_to_hostAndResolveFragment, bundle)
        }

        view.findViewById<Button>(R.id.home_retrieve_button).setOnClickListener {
            val bundle = Bundle().apply {
                putBoolean(Constant.HOSTING_MODE, false)
                putParcelableArrayList(Constant.EXTRA_ANCHORS_TO_RESOLVE, anchorsToResolveList)
            }
            findNavController().navigate(R.id.action_homeFragment_to_hostAndResolveFragment, bundle)
        }

    }

}