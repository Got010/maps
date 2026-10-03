package app.southbound.feature.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/** One car connection: the trip screen, plus the reporter that tells the car's navigation widgets about the trip. */
class SouthboundCarSession : Session() {
    private var reporter: TripReporter? = null

    override fun onCreateScreen(intent: Intent): Screen {
        if (reporter == null) reporter = TripReporter(carContext, lifecycle)
        return TripScreen(carContext)
    }
}
