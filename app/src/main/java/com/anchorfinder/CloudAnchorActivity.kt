package com.anchorfinder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment

class CloudAnchorActivity : AppCompatActivity(), NavController.OnDestinationChangedListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_anchor)

        val navController by lazy {
            val navHostFragment =
                supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment?
            navHostFragment!!.navController
        }

        navController.addOnDestinationChangedListener(this)
        val inflater = navController.navInflater
        val graph = inflater.inflate(R.navigation.nav_graph)
        navController.graph = graph
    }

    override fun onDestinationChanged(
        controller: NavController,
        destination: NavDestination,
        arguments: Bundle?
    ) {

    }

}