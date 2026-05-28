package com.example.gpstagger.gps

import android.content.Context
import android.location.Location
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

/**
 * Bridges [LocationSource] to OSMDroid's [IMyLocationProvider] so the map's
 * "My Location" overlay reflects whichever GPS source the user has chosen —
 * internal or USB.
 */
class LocationSourceMyLocationProvider(context: Context) : IMyLocationProvider {

    private val source = LocationSource(context.applicationContext)
    private var consumer: IMyLocationConsumer? = null
    private var lastLocation: Location? = null

    init {
        source.setListener(object : LocationSource.Listener {
            override fun onLocation(location: Location) {
                lastLocation = location
                consumer?.onLocationChanged(location, this@LocationSourceMyLocationProvider)
            }
            override fun onSourceStatus(label: String) { /* status surfaced elsewhere */ }
        })
    }

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        source.start()
        return true
    }

    override fun stopLocationProvider() {
        source.stop()
        consumer = null
    }

    override fun getLastKnownLocation(): Location? = lastLocation

    override fun destroy() {
        stopLocationProvider()
    }
}
