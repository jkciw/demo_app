/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import android.app.Application
import org.meshtastic.sdk.storage.sqldelight.AndroidContextHolder

class MeshDemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.context = applicationContext
    }
}
