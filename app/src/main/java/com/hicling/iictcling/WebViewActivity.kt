/*
*
* devilyouwei
* devilyouwei@gmail.com
* 2020-5-20
* IICT copyright
* 中国科学院计算所苏州
* MIT License
* All activities with webview component need to extend this class
*/

package com.hicling.iictcling

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.LinearLayout
import android.widget.Toast
import com.google.gson.Gson
import com.linchaolong.android.imagepicker.ImagePicker
import net.vidageek.mirror.dsl.Mirror
import wendu.webviewjavascriptbridge.WVJBWebView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.NetworkInterface
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


open class WebViewActivity : Activity() {

    open var url = "http://192.168.1.79:8080"
    //open var url = "http://192.168.1.103:8080" //前端

    open val tag: String = this.javaClass.simpleName
    private var loading: Boolean = false
    private val content: Int = R.layout.activity_webview // overridable
    open var mWebView: WVJBWebView? = null

    // record the devices are scanned
    open val bleDeviceHash = HashMap<String, BluetoothDevice>()
    private val handler = Handler()
    val imagePicker: ImagePicker = ImagePicker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 获取权限，蓝牙，wifi等
        getPermission()
        val bundle = this.intent.extras
        bundle?.getBoolean("loading")?.let { loading = it }
        // 当有url传入的时候在本类中直接调用initWebView，该类一般不被继承了
        bundle?.getString("url")?.let {
            setContentView(content)
            val mWebView: WVJBWebView = findViewById(R.id.webview)
            this.mWebView = mWebView
            url = it
            initWebView(mWebView, loading)
        }
    }

    // initial function, make all the config for jsbridge
    @SuppressLint("SetJavaScriptEnabled")
    open fun initWebView(
        mWebView: WVJBWebView,
        loading: Boolean = false,
        otherUrl: String? = null
    ) {
        if (otherUrl != null) url = otherUrl
        Log.i(tag, "initWebView: $url")
        showLoading(loading)
        val webSettings: WebSettings = mWebView.settings

        webSettings.setSupportZoom(false)
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.defaultTextEncodingName = "utf-8"
        webSettings.loadsImagesAutomatically = true
        //多窗口
        webSettings.supportMultipleWindows()
        //允许访问文件
        webSettings.allowFileAccess = true
        //开启javascript
        webSettings.javaScriptEnabled = true
        //支持通过JS打开新窗口
        //webSettings.javaScriptCanOpenWindowsAutomatically = true
        //关闭webview中缓存
        webSettings.setAppCacheEnabled(true)
        webSettings.cacheMode = WebSettings.LOAD_NO_CACHE
        webSettings.domStorageEnabled = true //DOM Storage

        mWebView.overScrollMode = WVJBWebView.OVER_SCROLL_NEVER // 取消WebView中滚动或拖动到顶部、底部时的阴影
        mWebView.scrollBarStyle = WVJBWebView.SCROLLBARS_INSIDE_OVERLAY // 取消滚动条白边效果
        //获取触摸焦点
        mWebView.requestFocusFromTouch()
        // >= 19(SDK4.4)启动硬件加速，否则启动软件加速
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webSettings.loadsImagesAutomatically = true //支持自动加载图片
        } else {
            mWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            webSettings.loadsImagesAutomatically = false
        }

        //deal with the error network
        mWebView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                onLoadFinish()
                super.onPageFinished(view, url)
                Log.i(tag, "Webview finish loading: $url")
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                Log.i(tag, "onReceivedHttpError")
                //view?.loadUrl("file:///android_asset/error.html")
                onLoadError()
                super.onReceivedHttpError(view, request, errorResponse)
            }
        }

        // deal with error page for android < 6.0 mash
        mWebView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String) {
                Log.i(tag, "$title")
                if (title.contains("404")
                    || title.contains("找不到")
                    || title.contains("Error")
                    || title.contains("500")
                    || title.contains("无法打开")
                    || title.contains("not available")
                    || title.contains("not found")
                ) {
                    Log.i(tag, "title find error, android<6.0")
                    view.loadUrl("file:///android_asset/error.html")
                    onLoadError()
                    super.onReceivedTitle(view, title)
                }
            }

        }
        mWebView.loadUrl(url)
    }

    open fun initBridge(mWebView: WVJBWebView) {
        Init().initBridge(mWebView, this)
    }

    open fun onLoadFinish() {
        showLoading(false)
    }

    open fun onLoadError() {
        Log.i(tag, "register refresh error page: $url")
        mWebView?.registerHandler(
            "refreshErrorPage",
            WVJBWebView.WVJBHandler<Any?, Any?> { _, function ->
                function.onResult(json(1, url, "refresh the error page"))
            })
        showLoading(false)
    }

    // 获取mac地址
    open fun getWifiMac(): String {
        try {
            val all = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in all) {
                if (!nif.name.equals("wlan0", ignoreCase = true)) continue

                val macBytes = nif.hardwareAddress ?: return ""

                val res1 = StringBuilder()
                for (b in macBytes) {
                    //res1.append(Integer.toHexString(b & 0xFF) + ":");
                    res1.append(String.format("%02X:", b))
                }

                if (res1.isNotEmpty()) {
                    res1.deleteCharAt(res1.length - 1)
                }
                Log.i("wlan0", res1.toString())
                return res1.toString()
            }
        } catch (e: Exception) {
            Log.i("error", e.message)
        }

        return "02:00:00:00:00:00"
    }

    open fun getBleMac(): String {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val bluetoothManagerService = Mirror().on(bluetoothAdapter).get().field("mService")
            if (bluetoothManagerService == null) {
                Log.w(tag, "no bluetoothManagerService")
                return "02:00:00:00:00:00"
            }
            val address =
                Mirror().on(bluetoothManagerService).invoke().method("getAddress").withoutArgs()
            return if (address != null && address is String) {
                Log.i("BleMac", address.toString())
                address.toString()
            } else {
                Log.i("BleMacError", "Ble Mac is NUll")
                "02:00:00:00:00:00"
            }
        } catch (e: Exception) {
            Log.i("BleMacError", e.message)
            return "02:00:00:00:00:00"
        }
    }

    // 获取手机自己生成的uuid
    open fun getLocalUUID(): ArrayList<String> {
        val list = ArrayList<String>()
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val uuids =
                Mirror().on(bluetoothAdapter).invoke().method("getUuids").withoutArgs() as Array<*>
            for (uuid in uuids) {
                list.add(uuid.toString())
            }
        } catch (e: Exception) {
            Log.e("uuid_error", e.message)
        }
        return list
    }

    open fun getBleName(): String {
        return try {
            BluetoothAdapter.getDefaultAdapter().name
        } catch (e: Exception) {
            ""
        }
    }

    // give standard response as JSON to frontend
    open fun json(status: Int, data: Any? = null, msg: String? = ""): String {
        return Gson().toJson(ResData(status, data, msg))
    }

    // use to init all the bridge functions to handle js call, only for the activities which extends jsActivity
    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    @SuppressLint("MissingPermission")

    open fun checkBle(): Boolean {
        val blueAdapter = BluetoothAdapter.getDefaultAdapter()
        return blueAdapter != null && blueAdapter.isEnabled
    }

    // 初始化蓝牙广播
    open fun setUuids(): HashMap<String, UUID> {
        val uuids = HashMap<String, UUID>()
        uuids["uuidServer"] = UUID.randomUUID()
        uuids["uuidCharRead"] = UUID.randomUUID()
        uuids["uuidCharWrite"] = UUID.randomUUID()
        uuids["uuidDescriptor"] = UUID.randomUUID()
        return uuids
    }

    // 初始化广播GATT
    /*
     * @uuids 生成的uuid
     * @data 蓝牙需要携带的信息
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    fun initGATT(uuids: HashMap<String, UUID>, data: String) {
        val settings: AdvertiseSettings = AdvertiseSettings.Builder()
            .setConnectable(true)
            .build()
        val advertiseData: AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(true)
            .build()
        val scanResponseData: AdvertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(uuids["uuidServer"]))
            .setIncludeTxPowerLevel(true)
            .addServiceData(ParcelUuid(uuids["uuidServer"]), data.toByteArray())
            .build()
        val callback: AdvertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("initGATT", "Succeed to add BLE ad")
                startGATT(uuids)
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e("initGATT", "Failed to add BLE ad, reason: $errorCode")
            }
        }
        val mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val bluetoothLeAdvertiser: BluetoothLeAdvertiser =
            mBluetoothAdapter.bluetoothLeAdvertiser
        bluetoothLeAdvertiser.startAdvertising(settings, advertiseData, scanResponseData, callback)
    }

    // 启动uuid蓝牙广播
    private fun startGATT(uuids: HashMap<String, UUID>) {
        val mBluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothGattServer = mBluetoothManager.openGattServer(
            applicationContext,
            bluetoothGattServerCallback
        )
        val service =
            BluetoothGattService(uuids["uuidServer"], BluetoothGattService.SERVICE_TYPE_PRIMARY)
        //add a read characteristic.
        val characteristicRead = BluetoothGattCharacteristic(
            uuids["uuidCharRead"],
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        //add a descriptor
        val descriptor =
            BluetoothGattDescriptor(
                uuids["uuidDescriptor"],
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        characteristicRead.addDescriptor(descriptor)
        service.addCharacteristic(characteristicRead)
        //add a write characteristic.
        val characteristicWrite = BluetoothGattCharacteristic(
            uuids["uuidCharWrite"],
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristicWrite)
        bluetoothGattServer.addService(service)
        Log.i("GATT", "2. initServices ok")
    }

    // callback
    private val bluetoothGattServerCallback: BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int
            ) {
                Log.i(
                    "GATT",
                    "onConnectionStateChange：name = ${device.name}, address = ${device.address}"
                )
                Log.i(
                    "GATT", "onConnectionStateChange：status $status, newState $newState ",
                )
                super.onConnectionStateChange(device, status, newState)
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                super.onServiceAdded(status, service)
                Log.i("GATT", "onServiceAdded：status = $status")
            }

        }

    // go to other activity
    fun goWebView(url: String?, loading: Boolean = false) {
        val intent = Intent(this, WebViewActivity::class.java)
        val bundle = Bundle()
        bundle.putString("url", url)
        bundle.putBoolean("loading", loading)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    fun goMap(path: String = "") {
        val intent = Intent(this, MapActivity::class.java)
        val bundle = Bundle()
        bundle.putString("path", path)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    fun showLoading(load: Boolean) {
        val modal: LinearLayout = findViewById(R.id.load)
        if (load) modal.visibility = View.VISIBLE
        else modal.visibility = View.INVISIBLE
    }

    private var isExit = 0
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.i(tag, "on key down")
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return if (mWebView!!.canGoBack()) {
                Log.i(tag, "webview go back")
                mWebView?.goBack()
                true
            } else {
                Log.i(tag, "Activity go back")
                isExit++
                exit()
                false
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun exit() {
        if (isExit < 2 && tag == "MainActivity") {
            Toast.makeText(applicationContext, R.string.exit, Toast.LENGTH_SHORT).show()
            handler.postDelayed({ isExit-- }, 2000L)
        } else {
            this.finish()
        }
    }

    /**
     * 解决：android>6，定位等权限，蓝牙问题
     */
    private val accessCode = 102
    private val permissions: Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.INTERNET,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CHANGE_NETWORK_STATE,
        Manifest.permission.CAMERA
    )
    private var countRequest = 0

    // get bluetooth permission
    private fun getPermission() {
        countRequest++
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            var permissionCheck = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionCheck += checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permissions, accessCode)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // check permission of images picker
        imagePicker.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
        when (requestCode) {
            accessCode -> if (checkPermission(grantResults)) {
                Log.i(tag, "onRequestPermissionsResult: 用户允许权限 accessCode:$accessCode")
            } else {
                Log.i(tag, "onRequestPermissionsResult: 拒绝搜索设备权限 accessCode:$accessCode")
                if (countRequest > 2) {
                    // ask User to grant permission manually
                    AlertDialog.Builder(this)
                        .setMessage(R.string.open_permission_req)
                        .setTitle(R.string.request)
                        .setPositiveButton(R.string.confirm) { _, _ ->
                            goIntentSetting()
                        }.create().show()
                } else getPermission()
            }
        }
    }

    open fun checkPermission(grantResults: IntArray): Boolean {
        for (grantResult in grantResults) {
            if (grantResult == PackageManager.PERMISSION_DENIED) {
                return false
            }
        }
        return true
    }

    // open app settings let user grant the permission
    open fun goIntentSetting() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri: Uri = Uri.fromParts("package", this.packageName, null)
        intent.data = uri
        try {
            this.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    open fun toast(text: String?) {
        text?.let {
            val toast = Toast.makeText(this, it, Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.BOTTOM, 0, 100)
            toast.show()
        }
    }

    open fun toast(text: Int?) {
        text?.let {
            val toast = Toast.makeText(this, it, Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.BOTTOM, 0, 100)
            toast.show()
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        imagePicker.onActivityResult(this, requestCode, resultCode, data)
    }

    // file to base64
    open fun base64Img(path: String?, quality: Int = 100): String? {
        if (path == null || TextUtils.isEmpty(path)) {
            throw Error("error: path is empty")
        }
        return try {
            val bitmap = BitmapFactory.decodeFile(path)
            val os = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, quality, os)
            val bytes = os.toByteArray()
            Log.i(tag, "path:$path, quality:$quality")
            String(Base64.encode(bytes, Base64.DEFAULT))
        } catch (e: IOException) {
            Log.e(tag, e.message)
            null
        }
    }

    // base64 to bitmap
    open fun base64ToBitmap(base64Data: String?): Bitmap? {
        val bytes =
            Base64.decode(base64Data, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        mWebView?.destroy()
    }
}

