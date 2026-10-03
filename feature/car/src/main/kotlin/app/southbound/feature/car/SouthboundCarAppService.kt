package app.southbound.feature.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto's way in. The Car App Library binds to this service when the phone connects to a
 * car running Android Auto; nothing here runs, or is needed, without it.
 */
class SouthboundCarAppService : CarAppService() {

    /**
     * Debug builds accept any host (the Desktop Head Unit). Release builds accept only the hosts
     * the library's allowlist names: Android Auto and the Android Automotive emulators.
     */
    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = SouthboundCarSession()
}
