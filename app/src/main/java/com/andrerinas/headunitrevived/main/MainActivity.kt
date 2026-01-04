package com.andrerinas.headunitrevived.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.contract.ConnectedIntent
import com.andrerinas.headunitrevived.contract.DisconnectIntent
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.toInetAddress
import java.net.Inet4Address
import com.andrerinas.headunitrevived.utils.Settings
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val activityJob = Job()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    private var lastBackPressTime: Long = 0
    var keyListener: KeyListener? = null
    private val viewModel: MainViewModel by viewModels()

    private lateinit var self_mode_button: Button
    private lateinit var self_mode_button_text: TextView
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

    private val connectionStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLog.i("MainActivity received ${intent?.action}")
            updateProjectionButtonText()
        }
    }

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

        self_mode_button = findViewById(R.id.self_mode_button)
        self_mode_button_text = findViewById(R.id.self_mode_text)
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
            // Explicitly stop the AapService
            val stopServiceIntent = Intent(this, AapService::class.java).apply {
                action = AapService.ACTION_STOP_SERVICE
            }
            startService(stopServiceIntent)
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

        self_mode_button.setOnClickListener {
            if (AapService.isConnected) {
                val aapIntent = Intent(this@MainActivity, AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            } else {
                AapService.selfMode = true
                AapService.isConnected = false // Reset flag before starting
                val intent = Intent(this, AapService::class.java)
                intent.action = AapService.ACTION_START_SELF_MODE
                startService(intent)
                Toast.makeText(this, "Starting Self Mode...", Toast.LENGTH_SHORT).show()
                
                activityScope.launch {
                    var attempts = 0
                    while (attempts < 15 && !AapService.isConnected) {
                        delay(1000)
                        attempts++
                    }

                    if (AapService.isConnected) {
                        val aapIntent = Intent(this@MainActivity, AapProjectionActivity::class.java)
                        aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                        startActivity(aapIntent)
                    } else {
                        Toast.makeText(this@MainActivity, "Self Mode connection failed to establish.", Toast.LENGTH_LONG).show()
                    }
                }
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

        val filter = IntentFilter().apply {
            addAction(ConnectedIntent.action)
            addAction(DisconnectIntent.action)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(connectionStatusReceiver, filter)
        }

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
        updateProjectionButtonText() // Update button text on resume
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen() // Reapply fullscreen mode if window gains focus
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(connectionStatusReceiver)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityJob.cancel()
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

    private fun updateProjectionButtonText() {
        val selfModeTextView = findViewById<TextView>(R.id.self_mode_text)
        if (AapService.isConnected) {
            selfModeTextView.text = getString(R.string.to_android_auto)
        } else {
            selfModeTextView.text = getString(R.string.self_mode)
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