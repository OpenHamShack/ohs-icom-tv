package com.openhamshack.stationflow.testapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Debug builds only: publish a pretend StationFlow from the device itself, so
 * discovery can be seen on an emulator, whose NAT never passes the host's
 * mDNS. TXT as docs/TV-API.md section 1 has it:
 *
 *   adb shell am broadcast -n com.openhamshack.stationflow.testapp/.DebugAdvertiser \
 *       --es name StationFlow --es version 1.24.0 --ei port 443 --es scheme https --es path /icom/
 *
 * or a OpenHamShack platform server's advert (PLATFORM-PLAN section 2.1):
 *
 *   ... --es name GM4BRI --ei port 80 --es platform 1 --es kinds datv,stationflow
 *
 * It advertises the device's own address, so opening it reaches nothing.
 */
class DebugAdvertiser : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: "StationFlow"
        val port = intent.getIntExtra("port", 443)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_openhamshack._tcp."
            setPort(port)
            setAttribute("name", name)
            setAttribute("version", intent.getStringExtra("version") ?: "1.24.0")
            setAttribute("port", port.toString())
            if (intent.getStringExtra("platform") == "1") {
                setAttribute("platform", "1")
                setAttribute("kinds", intent.getStringExtra("kinds") ?: "stationflow")
                setAttribute("path", "/.well-known/openhamshack")
            } else {
                setAttribute("kind", intent.getStringExtra("kind") ?: "stationflow")
                setAttribute("scheme", intent.getStringExtra("scheme") ?: "https")
                setAttribute("path", intent.getStringExtra("path") ?: "/icom/")
            }
        }
        context.applicationContext.getSystemService(NsdManager::class.java)
            .registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(si: NsdServiceInfo) {}
                override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(si: NsdServiceInfo) {}
                override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
            })
    }
}
