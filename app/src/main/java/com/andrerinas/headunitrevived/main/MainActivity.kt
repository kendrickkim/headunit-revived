package com.andrerinas.headunitrevived.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.toInetAddress
import java.net.Inet4Address
import com.andrerinas.headunitrevived.utils.Settings
import android.view.WindowManager

class MainActivity : FragmentActivity() {

    private var lastBackPressTime: Long = 0
    var keyListener: KeyListener? = null
    private val viewModel: MainViewModel by viewModels()

    private lateinit var video_button: Button
    private lateinit var usb: Button
    private lateinit var settings: Button
    private lateinit var wifi: Button
    private lateinit var ipView: TextView
    private lateinit var backButton: Button
    private lateinit var mainButtonsContainer: ConstraintLayout
    private lateinit var mainContentFrame: FrameLayout
    private lateinit var headerContainer: LinearLayout
    private lateinit var exitButton: Button

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    interface KeyListener {
        fun onKeyEvent(event: KeyEvent?): Boolean
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.AppTheme)

        setContentView(R.layout.activity_main)

        // Call setFullscreen immediately after setting content view
        setFullscreen()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AppLog.d("MainActivity: handleOnBackPressed - backStackEntryCount: ${supportFragmentManager.backStackEntryCount}")
                if (supportFragmentManager.backStackEntryCount > 0) {
                    AppLog.d("MainActivity: handleOnBackPressed - popping back stack")
                    supportFragmentManager.popBackStack()
                } else if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                    AppLog.d("MainActivity: handleOnBackPressed - finishing activity")
                    finish()
                } else {
                    AppLog.d("MainActivity: handleOnBackPressed - showing exit toast")
                    lastBackPressTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })

        video_button = findViewById(R.id.video_button)
        usb = findViewById(R.id.usb_button)
        settings = findViewById(R.id.settings_button)
        wifi = findViewById(R.id.wifi_button)
        ipView = findViewById(R.id.ip_address)
        backButton = findViewById(R.id.back_button)
        mainButtonsContainer = findViewById(R.id.main_buttons_container)
        mainContentFrame = findViewById(R.id.main_content)
        headerContainer = findViewById(R.id.header_container)
        exitButton = findViewById(R.id.exit_button)

        exitButton.setOnClickListener {
            finishAffinity()
        }

        backButton.setOnClickListener {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            }
        }

        // Initialize networkCallback conditionally
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateIpAddressView()
                }

                override fun onLost(network: Network) {
                    updateIpAddressView()
                }
            }
        }

        video_button.setOnClickListener {
            if (App.provide(this).transport.isAlive) {
                val aapIntent = Intent(this@MainActivity, AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            } else {
                Toast.makeText(this, getString(R.string.no_android_auto_device_connected), Toast.LENGTH_LONG).show()
            }
        }

        usb.setOnClickListener {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.main_content, UsbListFragment())
                .addToBackStack(null)
                .commit()
        }

        settings.setOnClickListener {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.main_content, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }

        wifi.setOnClickListener {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.main_content, NetworkListFragment())
                .addToBackStack(null)
                .commit()
        }

        viewModel.register()

        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        ActivityCompat.requestPermissions(
            this,
            permissionsToRequest.toTypedArray(),
            permissionRequestCode
        )

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.main_content, HomeFragment())
                .commit()
        }
        supportFragmentManager.addOnBackStackChangedListener {
            AppLog.d("MainActivity: onBackStackChanged - backStackEntryCount: ${supportFragmentManager.backStackEntryCount}")
            updateBackButtonVisibility()
        }
        updateBackButtonVisibility()
    }

    private fun setFullscreen() {
        val appSettings = Settings(this)
        if (appSettings.startInFullscreenMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            // If not in fullscreen, ensure system UI is visible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateBackButtonVisibility() {
        val isFragmentOnStack = supportFragmentManager.backStackEntryCount > 0
        backButton.visibility = if (isFragmentOnStack) View.VISIBLE else View.GONE
        ipView.visibility = if (isFragmentOnStack) View.GONE else View.VISIBLE
        mainButtonsContainer.visibility = if (isFragmentOnStack) View.GONE else View.VISIBLE
        mainContentFrame.visibility = if (isFragmentOnStack) View.VISIBLE else View.GONE
        exitButton.visibility = if (isFragmentOnStack) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        setFullscreen() // Call setFullscreen here as well
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            networkCallback?.let {
                connectivityManager.registerNetworkCallback(request, it)
            }
        }
        updateIpAddressView()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen() // Reapply fullscreen mode if window gains focus
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        }
    }

    private fun updateIpAddressView() {
        var ipAddress: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // API 23+ (for getActiveNetwork)
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork // API 23
            if (activeNetwork != null) {
                val linkProperties = connectivityManager.getLinkProperties(activeNetwork) // API 21
                ipAddress = linkProperties?.linkAddresses?.find { it.address is Inet4Address }?.address?.hostAddress
            }
        } else { // API 19, 20, 21, 22
            val wifiManager = App.provide(this).wifiManager
            @Suppress("DEPRECATION")
            val currentIp = wifiManager.connectionInfo.ipAddress
            if (currentIp != 0) {
                ipAddress = currentIp.toInetAddress().hostAddress
            }
        }

        runOnUiThread {
            ipView.text = ipAddress ?: ""
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i("onKeyDown: %d", keyCode)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Let the system handle the back button, which will trigger onBackPressedDispatcher
            return super.onKeyDown(keyCode, event)
        }
        return keyListener?.onKeyEvent(event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i("onKeyUp: %d", keyCode)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Let the system handle the back button, which will trigger onBackPressedDispatcher
            return super.onKeyUp(keyCode, event)
        }
        return keyListener?.onKeyEvent(event) ?: super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val permissionRequestCode = 97
    }
}