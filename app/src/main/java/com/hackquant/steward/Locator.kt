package com.hackquant.steward

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/** Location context, saved places and arrival reminders. Coordinates never leave the phone. */
object Locator {
    fun hasLocation(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Blocking. Last known fix if it's fresh, otherwise a new one. */
    @SuppressLint("MissingPermission")
    fun current(ctx: Context): Location? {
        if (!hasLocation(ctx)) return null
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        return runCatching {
            Tasks.await(fused.lastLocation, 2, TimeUnit.SECONDS)
                ?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000 }
                ?: Tasks.await(fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null), 8, TimeUnit.SECONDS)
        }.getOrNull()
    }

    fun placeAt(loc: Location, places: List<Place>): Place? = places
        .map { p -> p to FloatArray(1).also { Location.distanceBetween(loc.latitude, loc.longitude, p.lat, p.lng, it) }[0] }
        .filter { (p, d) -> d <= p.radius }
        .minByOrNull { it.second }?.first

    /** A short description of where the user is, for the model's context. */
    fun describe(ctx: Context, places: List<Place>): String? {
        val loc = current(ctx) ?: return null
        return placeAt(loc, places)?.let { "at ${it.name}" } ?: "not at any saved place"
    }

    private fun geofenceIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    fun addArrivalReminder(ctx: Context, id: String, place: Place): Boolean {
        if (!hasLocation(ctx) || !hasBackground(ctx)) return false
        val fence = Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(place.lat, place.lng, place.radius.coerceAtLeast(100f))
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setExpirationDuration(7 * 24 * 60 * 60 * 1000L)
            .build()
        val req = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()
        return runCatching {
            Tasks.await(LocationServices.getGeofencingClient(ctx).addGeofences(req, geofenceIntent(ctx)), 5, TimeUnit.SECONDS)
        }.isSuccess
    }

    fun removeArrivalReminder(ctx: Context, id: String) {
        runCatching { LocationServices.getGeofencingClient(ctx).removeGeofences(listOf(id)) }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        Store.init(ctx)
        val ids = event.triggeringGeofences?.map { it.requestId } ?: return
        val reminders = Store.config.value.geoReminders
        ids.forEach { id ->
            reminders[id]?.let { Tools.notify(ctx, id.hashCode(), "Reminder from Alfred", it) }
            Locator.removeArrivalReminder(ctx, id)
        }
        Store.update { c -> c.copy(geoReminders = c.geoReminders - ids.toSet()) }
    }
}
