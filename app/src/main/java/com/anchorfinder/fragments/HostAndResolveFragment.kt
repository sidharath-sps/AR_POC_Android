package com.anchorfinder.fragments

import android.content.Context
import android.content.SharedPreferences
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.GuardedBy
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.anchorfinder.R
import com.anchorfinder.common.Constant
import com.anchorfinder.common.cloudanchors.CloudAnchorManager
import com.anchorfinder.common.cloudanchors.DescriptionDialogFragment
import com.anchorfinder.common.cloudanchors.FirebaseManager
import com.anchorfinder.common.cloudanchors.HostDialogFragment
import com.anchorfinder.common.cloudanchors.PrivacyNoticeDialogFragment
import com.anchorfinder.common.cloudanchors.ResolveAnchors
import com.anchorfinder.common.helpers.CameraPermissionHelper
import com.anchorfinder.common.helpers.DisplayRotationHelper
import com.anchorfinder.common.helpers.SnackbarHelper
import com.anchorfinder.common.helpers.TrackingStateHelper
import com.anchorfinder.common.rendering.BackgroundRenderer
import com.anchorfinder.common.rendering.ObjectRenderer
import com.anchorfinder.common.rendering.PlaneRenderer
import com.anchorfinder.common.rendering.PointCloudRenderer
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Config
import com.google.ar.core.Config.CloudAnchorMode
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.common.base.Preconditions
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt


class HostAndResolveFragment : Fragment(), GLSurfaceView.Renderer  {

    private val TAG: String = HostAndResolveFragment::class.java.simpleName
    private val ALLOW_SHARE_IMAGES_KEY = "ALLOW_SHARE_IMAGES"
    private val PREFERENCE_FILE_KEY: String = "CLOUD_ANCHOR_PREFERENCES"

    private enum class HostResolveMode {
        HOSTING,
        RESOLVING,
    }

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private var surfaceView: GLSurfaceView? = null
    private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer()
    private val anchorObject: ObjectRenderer = ObjectRenderer()
    private val virtualObjectShadow: ObjectRenderer = ObjectRenderer()
    private val planeRenderer: PlaneRenderer = PlaneRenderer()
    private val pointCloudRenderer: PointCloudRenderer = PointCloudRenderer()
    private lateinit var snackbarHelper: SnackbarHelper
    private var installRequested = false

    // Temporary matrices allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    // Locks needed for synchronization
    private val singleTapLock = Any()
    private val anchorLock = Any()

    // Tap handling and UI.
    private var gestureDetector: GestureDetector? = null
    private var displayRotationHelper: DisplayRotationHelper? = null
    private var trackingStateHelper: TrackingStateHelper? = null
    private var sharedPreferences: SharedPreferences? = null

    private var anchorPose: Pose? = null

    @GuardedBy("singleTapLock")
    private var queuedSingleTap: MotionEvent? = null

    private var session: Session? = null

    @GuardedBy("anchorLock")
    private var anchor: Anchor? = null

    @GuardedBy("anchorLock")
    private val resolvedAnchors: ArrayList<ResolvedAnchors> = ArrayList()

    private val hostAnchors: ArrayList<Anchor> = ArrayList()

    @GuardedBy("anchorLock")
    private var unresolvedAnchorIds: ArrayList<ResolveAnchors> = ArrayList()

    private var cloudAnchorManager: CloudAnchorManager? = null
    private var currentMode: HostResolveMode? = null

    private var firebaseManager: FirebaseManager? = null
    private var progressBar: ConstraintLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_host_and_resolve, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        trackingStateHelper = TrackingStateHelper(activity)
        firebaseManager = FirebaseManager()
        snackbarHelper = SnackbarHelper(activity)
        context?.let { firebaseManager?.initFirebase(it) }
        currentMode = if (arguments?.getBoolean(Constant.HOSTING_MODE) == true) {
            HostResolveMode.HOSTING
        } else {
            HostResolveMode.RESOLVING
        }
        if (arguments?.getParcelableArrayList<ResolveAnchors>(
                Constant.EXTRA_ANCHORS_TO_RESOLVE,
            ) != null
        ) {
            unresolvedAnchorIds = arguments?.getParcelableArrayList(
                Constant.EXTRA_ANCHORS_TO_RESOLVE
            )!!
        }

        surfaceView = view.findViewById(R.id.surfaceview)
        progressBar = view.findViewById(R.id.clProgressBar)
        displayRotationHelper = DisplayRotationHelper(context)
        setUpTapListener()

        view.findViewById<ImageView>(R.id.ivBack).setOnClickListener {
            findNavController().popBackStack()
        }

        // Set up renderer.
        surfaceView?.preserveEGLContextOnPause = true
        surfaceView?.setEGLContextClientVersion(2)
        surfaceView?.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView?.setRenderer(this)
        surfaceView?.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView?.setWillNotDraw(false)
        installRequested = false

        showPrivacyDialog()
    }

    private fun showPrivacyDialog() {
        sharedPreferences =
            activity?.getSharedPreferences(PREFERENCE_FILE_KEY, Context.MODE_PRIVATE)

        if (currentMode == HostResolveMode.HOSTING) {
            if (!sharedPreferences!!.getBoolean(
                    ALLOW_SHARE_IMAGES_KEY,
                    false
                )
            ) {
                showNoticeDialog { this.onPrivacyAcceptedForHost() }
            } else {
                onPrivacyAcceptedForHost()
            }
        } else {
            if (!sharedPreferences!!.getBoolean(
                    ALLOW_SHARE_IMAGES_KEY,
                    false
                )
            ) {
                showNoticeDialog { this.onPrivacyAcceptedForResolve() }
            } else {
                onPrivacyAcceptedForResolve()
            }
        }
    }

    private fun setUpTapListener() {
        gestureDetector =
            GestureDetector(
                activity,
                object : SimpleOnGestureListener() {
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        synchronized(singleTapLock) {
                            if ((currentMode == HostResolveMode.HOSTING || currentMode == HostResolveMode.RESOLVING) && progressBar?.visibility == View.GONE) {
                                queuedSingleTap = e
                            }
                        }
                        return true
                    }

                    override fun onDown(e: MotionEvent): Boolean {
                        return true
                    }
                })
        surfaceView!!.setOnTouchListener { v: View?, event: MotionEvent? ->
            gestureDetector!!.onTouchEvent(
                event!!
            )
            true
        }
    }

    override fun onDestroy() {
        if (session != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            session!!.close()
            session = null
        }

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (sharedPreferences!!.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
            createSession()
        }
        surfaceView!!.onResume()
        displayRotationHelper!!.onResume()
    }

    private fun createSession() {
        if (session == null) {
            var exception: Exception? = null
            var messageId = -1
            try {
                when (ArCoreApk.getInstance()
                    .requestInstall(activity, !installRequested)) {
                    InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }

                    InstallStatus.INSTALLED -> {}
                }
                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(activity)) {
                    CameraPermissionHelper.requestCameraPermission(activity)
                    return
                }
                session = Session(activity)
                cloudAnchorManager = CloudAnchorManager(session)
            } catch (e: UnavailableArcoreNotInstalledException) {
                messageId = R.string.arcore_unavailable
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                messageId = R.string.arcore_too_old
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                messageId = R.string.arcore_sdk_too_old
                exception = e
            } catch (e: Exception) {
                messageId = R.string.arcore_exception
                exception = e
            }

            if (exception != null) {
                snackbarHelper.showError(activity?.getString(messageId))
                Log.e(TAG, "Exception creating session", exception)
                return
            }

            // Create default config and check if supported.
            val config = Config(session)
            config.setCloudAnchorMode(CloudAnchorMode.ENABLED)
            session!!.configure(config)
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            snackbarHelper.showError(activity?.getString(R.string.camera_unavailable))
            session = null
            cloudAnchorManager = null
        }
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper!!.onPause()
            surfaceView!!.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)

        if (!CameraPermissionHelper.hasCameraPermission(activity)) {
            Toast.makeText(
                activity,
                "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(activity)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(activity)
            }
        }
    }

    /**
     * Handles the most recent user tap.
     *
     *
     * We only ever handle one tap at a time, since this app only allows for a single anchor.
     *
     * @param frame the current AR frame
     * @param cameraTrackingState the current camera tracking state
     */

    private fun handleTap(frame: Frame, cameraTrackingState: TrackingState) {
        // Handle taps. Handling only one tap per frame, as taps are usually low frequency
        // compared to frame rate.
        synchronized(singleTapLock) {
            synchronized(anchorLock) {
                if (currentMode == HostResolveMode.HOSTING) {
                    if (anchor == null && queuedSingleTap != null && cameraTrackingState == TrackingState.TRACKING) {
                        Preconditions.checkState(
                            currentMode == HostResolveMode.HOSTING,
                            "We should only be creating an anchor in hosting mode."
                        )
                        for (hit in frame.hitTest(queuedSingleTap)) {
                            if (shouldCreateAnchorWithHit(hit)) {
                                // Create an anchor using a hit test with plane
                                val newAnchor = hit.createAnchor()
                                cloudAnchorManager?.hostCloudAnchor(
                                    newAnchor
                                ) { cloudAnchorId, cloudAnchorState ->
                                    if (cloudAnchorState.isError) {
                                        Log.e(
                                            TAG,
                                            "Error hosting a cloud anchor, state $cloudAnchorState"
                                        )
                                        snackbarHelper.showError(
                                            activity?.getString(
                                                R.string.hosting_error,
                                                cloudAnchorState
                                            )
                                        )
                                        return@hostCloudAnchor
                                    }
                                    saveAnchorWithDescription(cloudAnchorId = cloudAnchorId)
                                    synchronized(singleTapLock) {
                                        queuedSingleTap = null
                                    }
                                    synchronized(anchorLock) {
                                        anchor = null
                                    }
                                }
                                setNewAnchor(newAnchor)
                                activity?.runOnUiThread {
                                    progressBar?.visibility = View.VISIBLE
                                    snackbarHelper.showMessageForLongDuration(
                                        activity?.getString(R.string.snackbar_anchor_placed)
                                    )
                                }
                                break // Only handle the first valid hit.
                            }
                        }
                    }
                } else if (currentMode == HostResolveMode.RESOLVING) {
                    // Check if there is a queued tap and if the camera is currently tracking
                    if (queuedSingleTap != null && cameraTrackingState == TrackingState.TRACKING) {
                        val tap = queuedSingleTap
                        queuedSingleTap = null // Reset the queued tap to prevent re-processing
                        // Process hit test for the tap
                        for (hit in frame.hitTest(tap)) {
                            // Find the first matching resolved anchor whose pose is within bounds of the hit pose
                            val matchingAnchor = resolvedAnchors.find { resolvedAnchor ->
                                resolvedAnchor.anchor.trackingState == TrackingState.TRACKING &&
                                        resolvedAnchor.anchor.pose.isWithinBounds(hit.hitPose)
                            }
                            // If a matching anchor is found, display the description dialog
                            if (matchingAnchor != null) {
                                Log.d("HIT POSE", "handleTap: ${hit.hitPose}")
                                Log.d("ANCHOR POSE", "handleTap: ${matchingAnchor.anchor.pose}")
                                showDescriptionDialog(matchingAnchor.cloudAnchorDescription)
                                return // Exit after showing the dialog for the first matched anchor
                            }
                        }
                    }
                }
                queuedSingleTap = null
            }
        }
    }

    /**
     * Extension function to check if the distance between two poses is within a certain threshold.
     *
     * @param hitPose The pose to compare with.
     * @return True if the distance between the two poses is less than the threshold, false otherwise.
     */
    private fun Pose.isWithinBounds(hitPose: Pose): Boolean {
        val distanceThreshold = 0.7f // The maximum allowed distance between the two poses for them to be considered within bounds

        // Calculate the differences in the x, y, and z coordinates between the current pose and the hit pose
        val distanceX = this.tx() - hitPose.tx()
        val distanceY = this.ty() - hitPose.ty()
        val distanceZ = this.tz() - hitPose.tz()

        // Calculate the Euclidean distance between the two poses
        val distance = sqrt(distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ)
        Log.d("DISTANCE", "isWithinBounds: $distance")
        // Return true if the distance is less than the threshold, otherwise return false
        return distance < distanceThreshold
    }

    /**
     * Displays a description dialog for the given cloud anchor ID.
     *
     * @param cloudAnchorId The ID of the cloud anchor for which the description dialog should be shown.
     */
    private fun showDescriptionDialog(cloudAnchorId: String) {
        // Ensure the activity and its support fragment manager are available
        activity?.supportFragmentManager?.let {
            // Create a new instance of DescriptionDialogFragment with the cloud anchor ID
            val dialog = DescriptionDialogFragment.newInstance(cloudAnchorId)

            // Show the dialog using the fragment manager and tag it with "DescriptionDialog"
            dialog.show(it, "DescriptionDialog")
        }
    }

    /** Returns `true` if and only if the hit can be used to create an Anchor reliably.  */
    private fun shouldCreateAnchorWithHit(hit: HitResult): Boolean {
        val trackable = hit.trackable
        if (trackable is Plane) {
            // Check if the hit was within the plane's polygon.
            return trackable.isPoseInPolygon(hit.hitPose)
        }
        return false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {

            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(activity)
            planeRenderer.createOnGlThread(activity, "models/trigrid.png")
            pointCloudRenderer.createOnGlThread(activity)

            anchorObject.createOnGlThread(activity, "models/andy.obj", "models/andy.png")
            anchorObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)

            virtualObjectShadow.createOnGlThread(
                activity, "models/andy_shadow.obj", "models/andy_shadow.png"
            )
            virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow)
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f)


        } catch (ex: IOException) {
            Log.e(TAG, "Failed to read an asset file", ex)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (session == null) {
            return
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session)

        try {
            session!!.setCameraTextureName(backgroundRenderer.textureId)

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = session!!.update()
            val camera = frame.camera
            val cameraTrackingState = camera.trackingState


            // Handle user input.
            handleTap(frame, cameraTrackingState)

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame)

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper?.updateKeepScreenOnFlag(camera.trackingState)

            // If not tracking, don't draw 3d objects.
            if (cameraTrackingState == TrackingState.PAUSED) {
                return
            }

            // Get camera and projection matrices.
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

            frame.acquirePointCloud().use { pointCloud ->
                pointCloudRenderer.update(pointCloud)
                pointCloudRenderer.draw(viewMatrix, projectionMatrix)
            }
            val colorCorrectionRgba = FloatArray(4)
            val scaleFactor = 1.0f
            frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)
            // Check if the anchor can be visualized or not, and get its pose if it can be.
            synchronized(anchorLock) {
                for (resolvedAnchor in resolvedAnchors) {
                    // Update the poses of resolved anchors that can be drawn and render them.
                    if (resolvedAnchor != null
                        && resolvedAnchor.anchor.trackingState == TrackingState.TRACKING
                    ) {
                        // Get the current pose of an Anchor in world space. The Anchor pose is updated
                        // during calls to session.update() as ARCore refines its estimate of the world.
                        anchorPose = resolvedAnchor.anchor.pose
                        anchorPose!!.toMatrix(anchorMatrix, 0)
                        // Update and draw the model and its shadow.
                        drawAnchor(anchorMatrix, scaleFactor, colorCorrectionRgba)
                    }
                }
                if (anchor == null) {
                    // Visualize planes.
                    planeRenderer.drawPlanes(
                        session!!.getAllTrackables(Plane::class.java),
                        camera.displayOrientedPose,
                        projectionMatrix
                    )
                }

            }

            hostAnchors.forEach { anchorItem ->
                // Update the pose of the anchor (to be) hosted if it can be drawn and render the anchor.
                if (anchorItem != null && anchorItem!!.getTrackingState() == TrackingState.TRACKING) {
                    anchorPose = anchorItem!!.getPose()
                    anchorPose!!.toMatrix(anchorMatrix, 0)
                    drawAnchor(anchorMatrix, scaleFactor, colorCorrectionRgba)
                }
            }

        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    private fun drawAnchor(
        anchorMatrix: FloatArray,
        scaleFactor: Float,
        colorCorrectionRgba: FloatArray
    ) {
        anchorObject.updateModelMatrix(anchorMatrix, scaleFactor)
        anchorObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba)
    }

    /** Sets the new value of the current anchor. Detaches the old anchor, if it was non-null.  */
    private fun setNewAnchor(newAnchor: Anchor) {
        hostAnchors.add(newAnchor)
        Log.d(TAG, "setNewAnchor: Host Anchor List ${hostAnchors.size}")
    }

    /**
     * Sets the specified cloud anchor as resolved by adding it to the list of resolved anchors and removing it from the list of unresolved anchors.
     *
     * @param cloudAnchorId The ID of the cloud anchor to set as resolved.
     * @param newAnchor The new ARCore anchor object associated with the cloud anchor ID.
     */
    private fun setAnchorAsResolved(cloudAnchorId: String, newAnchor: Anchor) {
        synchronized(anchorLock) {
            // Find the unresolved anchor by its cloud anchor ID
            unresolvedAnchorIds.find { it.cloudAnchorId == cloudAnchorId }?.let { anchor ->

                // Create a new ResolvedAnchors object and add it to the list of resolved anchors
                resolvedAnchors.add(ResolvedAnchors(
                    cloudAnchorId,
                    cloudAnchorDescription = anchor.cloudDescription,
                    anchor = newAnchor
                ))

                // Remove the resolved anchor from the list of unresolved anchors
                unresolvedAnchorIds.remove(anchor)

            }
        }
    }

    /** Callback function invoked when the privacy notice is accepted.  */
    private fun onPrivacyAcceptedForHost() {
        if (!sharedPreferences!!.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true)
                .commit()
        ) {
            throw AssertionError("Could not save the user preference to SharedPreferences!")
        }
        createSession()
    }

    private fun onPrivacyAcceptedForResolve() {
        if (!sharedPreferences!!.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true)
                .commit()
        ) {
            throw AssertionError("Could not save the user preference to SharedPreferences!")
        }
        createSession()
        synchronized(anchorLock) {
            snackbarHelper.showMessageForLongDuration(

                activity?.getString(R.string.resolving_processing)
            )
            Log.i(
                TAG,
                String.format(
                    "Attempting to resolve %d anchor(s): %s",
                    unresolvedAnchorIds.size, unresolvedAnchorIds
                )
            )
            for (cloudAnchorId in unresolvedAnchorIds) {
                cloudAnchorManager?.resolveCloudAnchor(
                    cloudAnchorId.cloudAnchorId
                ) { cloudAnchorId, anchor, cloudAnchorState ->
                    /* Listens for a resolved anchor. */
                    if (cloudAnchorState.isError) {
                        Log.e(
                            HostAndResolveFragment().TAG,
                            "Error hosting a cloud anchor, state $cloudAnchorState"
                        )
                        snackbarHelper.showError(activity?.getString(R.string.resolving_error) + cloudAnchorState)
                        return@resolveCloudAnchor
                    }
                    setAnchorAsResolved(cloudAnchorId, anchor)
                    snackbarHelper.showMessageForLongDuration(

                        activity?.getString(R.string.resolving_success)
                    )

                    synchronized(anchorLock) {
                        if (unresolvedAnchorIds.isEmpty()) {
                            snackbarHelper.showMessageForLongDuration(

                                activity?.getString(R.string.debug_resolving_success) + cloudAnchorState
                            )
                        } else {
                            Log.i(
                                TAG,
                                String.format(
                                    "Attempting to resolve %d anchor(s): %s",
                                    unresolvedAnchorIds.size, unresolvedAnchorIds
                                )
                            )
                            snackbarHelper.showMessageForLongDuration(

                                activity?.getString(
                                    R.string.debug_resolving_processing,
                                    unresolvedAnchorIds.size
                                )
                            )
                        }
                    }
                }
            }

        }
    }

    private fun showNoticeDialog(listener: PrivacyNoticeDialogFragment.HostResolveListener?) {
        val dialog: DialogFragment = PrivacyNoticeDialogFragment.createDialog(listener)
        activity?.supportFragmentManager?.let {
            dialog.show(
                it,
                PrivacyNoticeDialogFragment::class.java.name
            )
        }
    }

    private fun saveAnchorWithDescription(cloudAnchorId: String?) {
        if (cloudAnchorId == null) {
            return
        }
        val hostDialogFragment = HostDialogFragment()

        hostDialogFragment.setOkListener { anchorDescription: String? ->
            if (anchorDescription != null) {
                saveAnchorToFirebase(cloudAnchorId, anchorDescription)
            }
        }

        activity?.supportFragmentManager?.let {
            hostDialogFragment.show(it, "HostDialog")
            progressBar?.visibility = View.GONE
        }
    }

    private fun saveAnchorToFirebase(cloudAnchorId: String, cloudAnchorDescription: String) {

        firebaseManager?.storeAnchorIdInFirebase(
            cloudAnchorId = cloudAnchorId,
            cloudAnchorDescription = cloudAnchorDescription
        )

    }

}
data class ResolvedAnchors(val cloudAnchorId: String, val cloudAnchorDescription: String, val anchor:Anchor)