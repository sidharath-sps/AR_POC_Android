package com.anchorfinder.common.cloudanchors

import android.content.Context
import android.os.Parcelable
import android.util.Log
import com.anchorfinder.fragments.HostAndResolveFragment
import com.google.android.gms.common.internal.Preconditions
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.parcelize.Parcelize


class FirebaseManager {

    private val TAG: String =
        HostAndResolveFragment::class.java.simpleName + "." + FirebaseManager::class.java.simpleName

    private var app: FirebaseApp? = null
    private var hotspotListRef: DatabaseReference? = null
    private var roomCodeRef: DatabaseReference? = null
    private var currentRoomRef: DatabaseReference? = null

    // Names of the nodes used in the Firebase Database
    private val ROOT_FIREBASE_HOTSPOTS = "hotspot_list"

    // Some common keys and values used when writing to the Firebase Database.
    private val KEY_ANCHOR_ID = "hosted_anchor_id"
    private val KEY_TIMESTAMP = "updated_at_timestamp"
    private val KEY_ANCHOR_DESCRIPTION = "anchor_description"


    fun initFirebase(context: Context) {
        app = FirebaseApp.initializeApp(context)
        if (app != null) {
            val rootRef = FirebaseDatabase.getInstance(app!!).reference
            hotspotListRef = rootRef.child(ROOT_FIREBASE_HOTSPOTS)
            DatabaseReference.goOnline()
        } else {
            Log.d(TAG, "Could not connect to Firebase Database!")
            hotspotListRef = null
            roomCodeRef = null
        }
    }

    /** Stores the given anchor ID in the given room code.  */
    fun storeAnchorIdInFirebase(cloudAnchorId: String?, cloudAnchorDescription: String) {
        app?.let { Preconditions.checkNotNull(it, "Firebase App was null") }
        val roomRef = cloudAnchorId?.let { hotspotListRef?.child(it) }
        if (roomRef != null) {
            roomRef.child(KEY_ANCHOR_ID).setValue(cloudAnchorId)
            roomRef.child(KEY_ANCHOR_DESCRIPTION).setValue(cloudAnchorDescription)
            roomRef.child(KEY_TIMESTAMP).setValue(System.currentTimeMillis())
        }

    }

    fun getAnchorList(onComplete: (List<ResolveAnchors>) -> Unit) {
        val anchorIdList = mutableListOf<ResolveAnchors>()

        app?.let { Preconditions.checkNotNull(it, "Firebase App was null") }
        currentRoomRef = hotspotListRef // Correctly reference the hotspot list

        val valueEventListener = object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                Log.d(TAG, "DataSnapshot: $dataSnapshot")
                // Clear the list before adding new data to avoid duplicates
                anchorIdList.clear()
                for (snapshot in dataSnapshot.children) {
                    val anchorId = snapshot.child("hosted_anchor_id").getValue(String::class.java)
                    val anchorDescription =
                        snapshot.child("anchor_description").getValue(String::class.java)
                    if (anchorId != null && anchorDescription != null)
                        anchorIdList.add(
                            ResolveAnchors(
                                cloudAnchorId = anchorId,
                                cloudDescription = anchorDescription
                            )
                        )

                }
                onComplete(anchorIdList)
            }

            override fun onCancelled(databaseError: DatabaseError) {
                Log.w(TAG, "The Firebase operation was cancelled.", databaseError.toException())
                onComplete(emptyList()) // Return an empty list on failure
            }
        }

        currentRoomRef?.addValueEventListener(valueEventListener)
    }
}

@Parcelize
data class ResolveAnchors(val cloudAnchorId: String = "", val cloudDescription: String = "") : Parcelable