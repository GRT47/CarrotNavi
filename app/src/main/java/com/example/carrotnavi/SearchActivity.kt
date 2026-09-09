package com.example.carrotnavi

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

class SearchActivity : AppCompatActivity() {

    private val searchRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://dapi.kakao.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    private val kakaoSearchApi: KakaoSearchApi by lazy {
        searchRetrofit.create(KakaoSearchApi::class.java)
    }

    private val okHttpClient = OkHttpClient()
    private var activeMapCall: Call? = null

    // Left Panel - Search Controls
    private lateinit var etKeyword: EditText
    private lateinit var btnClearKeyword: ImageView
    private lateinit var btnSearch: Button
    private lateinit var toolbar: MaterialToolbar

    // Left Panel - History Section
    private lateinit var llHistorySection: LinearLayout
    private lateinit var tvClearAllHistory: TextView
    private lateinit var rvSearchHistory: RecyclerView
    private lateinit var tvEmptyHistory: TextView
    private lateinit var historyAdapter: SearchHistoryAdapter

    // Left Panel - Search Result Section
    private lateinit var llSearchResultSection: LinearLayout
    private lateinit var rgSort: RadioGroup
    private lateinit var rbSortDistance: RadioButton
    private lateinit var rvResults: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: AddressSearchAdapter

    // Layout Panels
    private lateinit var llLeftPanel: View
    private lateinit var vPanelDivider: View
    private lateinit var flRightPanel: View

    // Right Panel Views
    private lateinit var llEmptyPreview: View
    private lateinit var llPreviewContent: View
    private lateinit var tvPreviewPlaceName: TextView
    private lateinit var tvPreviewAddress: TextView
    private lateinit var ivMapPreview: ImageView
    private lateinit var ivDestinationMarker: ImageView
    private lateinit var pbMapLoading: ProgressBar
    private lateinit var tvMapError: TextView
    private lateinit var btnRegisterBookmark: Button
    private lateinit var btnStartGuidance: Button

    // Map State
    private var currentSelectedDoc: KakaoDocument? = null
    private var currentLevel = 3 // 1 (Most Zoomed In) ~ 12 (Most Zoomed Out)
    private var currentCenterX = 0.0
    private var currentCenterY = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        initViews()
        applyOrientationLayout()
        setupListeners()

        val registerTarget = intent.getStringExtra("register_target")
        if (registerTarget != null) {
            val hintText = when (registerTarget) {
                BookmarkItem.TYPE_HOME -> "🏠 집으로 등록할 장소 검색"
                BookmarkItem.TYPE_OFFICE -> "🏢 사무실로 등록할 장소 검색"
                BookmarkItem.TYPE_FAVORITE -> "⭐ 즐겨찾기 등록 장소 검색"
                else -> null
            }
            if (hintText != null) {
                supportActionBar?.subtitle = hintText
            }
        }

        val initialQuery = intent.getStringExtra("initial_query")
        if (!initialQuery.isNullOrEmpty()) {
            etKeyword.setText(initialQuery)
            etKeyword.post { btnSearch.performClick() }
        } else {
            // 기본 진입 시 가로 화면일 때만 최근 검색 기록 최신 항목을 자동 선택
            loadSearchHistory(autoSelectFirst = isLandscape())
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Left Panel - Search Bar
        etKeyword = findViewById(R.id.etSearchKeyword)
        btnClearKeyword = findViewById(R.id.btnClearKeyword)
        btnSearch = findViewById(R.id.btnSearch)

        // Left Panel - History Section
        llHistorySection = findViewById(R.id.llHistorySection)
        tvClearAllHistory = findViewById(R.id.tvClearAllHistory)
        rvSearchHistory = findViewById(R.id.rvSearchHistory)
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory)

        historyAdapter = SearchHistoryAdapter(
            onItemClick = { item ->
                if (isLandscape()) {
                    adapter.clearSelection()
                    selectDestination(item.toKakaoDocument())
                } else {
                    handleDestinationSelection(item.toKakaoDocument())
                }
            },
            onDeleteClick = { item ->
                SearchHistoryManager.removeHistory(this, item)
                val isCurrentlySelected = currentSelectedDoc?.let {
                    it.place_name == item.place_name && it.x == item.x && it.y == item.y
                } ?: false

                loadSearchHistory(autoSelectFirst = false)
                if (isCurrentlySelected) {
                    clearPreview()
                }
            },
            onBookmarkClick = { item ->
                showBookmarkDialog(item.toKakaoDocument())
            }
        )
        rvSearchHistory.layoutManager = LinearLayoutManager(this)
        rvSearchHistory.adapter = historyAdapter

        // Left Panel - Results Section
        llSearchResultSection = findViewById(R.id.llSearchResultSection)
        rgSort = findViewById(R.id.rgSort)
        rbSortDistance = findViewById(R.id.rbSortDistance)
        rvResults = findViewById(R.id.rvSearchResults)
        pbLoading = findViewById(R.id.pbLoading)
        tvEmpty = findViewById(R.id.tvEmptyResult)

        adapter = AddressSearchAdapter(
            onItemSelected = { doc ->
                if (isLandscape()) {
                    historyAdapter.clearSelection()
                    selectDestination(doc)
                } else {
                    handleDestinationSelection(doc)
                }
            },
            onBookmarkClick = { doc ->
                showBookmarkDialog(doc)
            }
        )
        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter

        // Panels
        llLeftPanel = findViewById(R.id.llLeftPanel)
        vPanelDivider = findViewById(R.id.vPanelDivider)
        flRightPanel = findViewById(R.id.flRightPanel)

        // Right Panel Views
        llEmptyPreview = findViewById(R.id.llEmptyPreview)
        llPreviewContent = findViewById(R.id.llPreviewContent)
        tvPreviewPlaceName = findViewById(R.id.tvPreviewPlaceName)
        tvPreviewAddress = findViewById(R.id.tvPreviewAddress)
        ivMapPreview = findViewById(R.id.ivMapPreview)
        ivDestinationMarker = findViewById(R.id.ivDestinationMarker)
        pbMapLoading = findViewById(R.id.pbMapLoading)
        tvMapError = findViewById(R.id.tvMapError)
        btnRegisterBookmark = findViewById(R.id.btnRegisterBookmark)
        btnStartGuidance = findViewById(R.id.btnStartGuidance)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout()
    }

    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun applyOrientationLayout() {
        val isLand = isLandscape()
        if (isLand) {
            llLeftPanel.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 4.2f)
            vPanelDivider.visibility = View.VISIBLE
            flRightPanel.visibility = View.VISIBLE
        } else {
            llLeftPanel.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
            vPanelDivider.visibility = View.GONE
            flRightPanel.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        btnClearKeyword.setOnClickListener {
            etKeyword.setText("")
            hideKeyboard()
            showHistorySection()
        }

        tvClearAllHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("최근 검색 기록 삭제")
                .setMessage("최근 검색 기록을 모두 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ ->
                    SearchHistoryManager.clearHistory(this)
                    loadSearchHistory(autoSelectFirst = false)
                    clearPreview()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        etKeyword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty()) {
                    btnClearKeyword.visibility = View.GONE
                    showHistorySection()
                } else {
                    btnClearKeyword.visibility = View.VISIBLE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        rgSort.setOnCheckedChangeListener { _, _ ->
            if (etKeyword.text.toString().trim().isNotEmpty()) {
                btnSearch.performClick()
            }
        }

        etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                btnSearch.performClick()
                true
            } else {
                false
            }
        }

        btnSearch.setOnClickListener {
            performSearch()
        }

        btnRegisterBookmark.setOnClickListener {
            val doc = currentSelectedDoc ?: return@setOnClickListener
            showBookmarkDialog(doc)
        }

        btnStartGuidance.setOnClickListener {
            val doc = currentSelectedDoc ?: return@setOnClickListener
            handleDestinationSelection(doc)
        }
    }

    private fun updateMarkerPosition() {
        // 핀 마커는 화면 정중앙에 고정됩니다.
        ivDestinationMarker.translationX = 0f
        ivDestinationMarker.translationY = 0f
        ivDestinationMarker.visibility = if (currentSelectedDoc != null) View.VISIBLE else View.GONE
    }


    private fun resetVisualTransforms() {
        ivMapPreview.translationX = 0f
        ivMapPreview.translationY = 0f
        ivMapPreview.scaleX = 1f
        ivMapPreview.scaleY = 1f
    }

    private fun showHistorySection() {
        llSearchResultSection.visibility = View.GONE
        llHistorySection.visibility = View.VISIBLE
        loadSearchHistory(autoSelectFirst = false)
    }

    private fun showSearchResultSection() {
        llHistorySection.visibility = View.GONE
        llSearchResultSection.visibility = View.VISIBLE
    }

    private fun loadSearchHistory(autoSelectFirst: Boolean) {
        val history = SearchHistoryManager.getHistory(this)
        if (history.isEmpty()) {
            tvEmptyHistory.visibility = View.VISIBLE
            rvSearchHistory.visibility = View.GONE
            tvClearAllHistory.visibility = View.GONE
            if (autoSelectFirst) {
                clearPreview()
            }
        } else {
            tvEmptyHistory.visibility = View.GONE
            rvSearchHistory.visibility = View.VISIBLE
            tvClearAllHistory.visibility = View.VISIBLE
            historyAdapter.submitList(history, autoSelectFirst)
        }
    }

    private fun clearPreview() {
        currentSelectedDoc = null
        llPreviewContent.visibility = View.GONE
        llEmptyPreview.visibility = View.VISIBLE
        ivMapPreview.setImageDrawable(null)
        ivDestinationMarker.visibility = View.GONE
        resetVisualTransforms()
        activeMapCall?.cancel()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etKeyword.windowToken, 0)
    }

    private fun selectDestination(doc: KakaoDocument) {
        currentSelectedDoc = doc

        llEmptyPreview.visibility = View.GONE
        llPreviewContent.visibility = View.VISIBLE

        tvPreviewPlaceName.text = doc.place_name
        val addressText = if (doc.road_address_name.isNotEmpty()) {
            if (doc.address_name.isNotEmpty() && doc.address_name != doc.road_address_name) {
                "${doc.road_address_name}\n(지번: ${doc.address_name})"
            } else {
                doc.road_address_name
            }
        } else {
            doc.address_name
        }
        tvPreviewAddress.text = addressText

        // Reset center coordinates and level for newly selected destination
        currentCenterX = doc.x.toDoubleOrNull() ?: 0.0
        currentCenterY = doc.y.toDoubleOrNull() ?: 0.0
        currentLevel = 3
        resetVisualTransforms()
        updateMarkerPosition()

        loadStaticMap(currentCenterX, currentCenterY, currentLevel, showLoading = true)
    }

    private fun loadStaticMap(
        centerX: Double,
        centerY: Double,
        level: Int,
        showLoading: Boolean = true
    ) {
        activeMapCall?.cancel()

        val prefs = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val restApiKey = prefs.getString("KAKAO_REST_API_KEY", "") ?: ""
        val appKey = prefs.getString("APP_KEY", "") ?: ""
        val keyToUse = if (restApiKey.isNotEmpty()) restApiKey else appKey

        if (keyToUse.isEmpty()) {
            tvMapError.text = "카카오 API 키가 설정되지 않았습니다."
            tvMapError.visibility = View.VISIBLE
            pbMapLoading.visibility = View.GONE
            ivMapPreview.setImageDrawable(null)
            return
        }

        if (showLoading) {
            pbMapLoading.visibility = View.VISIBLE
        }
        tvMapError.visibility = View.GONE

        // markers 파라미터는 카카오 서버의 강제 센터링 및 400 Bad Request("no primary marker")를 유발하므로 제외하고,
        // center, level, size만을 전달하여 완벽한 자유 스크롤/줌을 지원합니다. 목적지 핀은 네이티브 뷰로 표시됩니다.
        val httpUrl = "https://dapi.kakao.com/v2/maps/staticmap".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("center", "$centerX,$centerY")
            ?.addQueryParameter("size", "960x600")
            ?.addQueryParameter("level", level.toString())
            ?.build()

        if (httpUrl == null) {
            tvMapError.text = "URL 생성 실패"
            tvMapError.visibility = View.VISIBLE
            pbMapLoading.visibility = View.GONE
            resetVisualTransforms()
            updateMarkerPosition()
            return
        }

        val request = Request.Builder()
            .url(httpUrl)
            .addHeader("Authorization", "KakaoAK $keyToUse")
            .addHeader("Cache-Control", "no-cache")
            .build()

        activeMapCall = okHttpClient.newCall(request)
        activeMapCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                runOnUiThread {
                    pbMapLoading.visibility = View.GONE
                    tvMapError.text = "지도를 불러올 수 없습니다: ${e.message}"
                    tvMapError.visibility = View.VISIBLE
                    resetVisualTransforms()
                    updateMarkerPosition()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) return
                if (response.isSuccessful && response.body != null) {
                    try {
                        val inputStream = response.body!!.byteStream()
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        runOnUiThread {
                            pbMapLoading.visibility = View.GONE
                            if (bitmap != null) {
                                ivMapPreview.setImageBitmap(bitmap)
                                resetVisualTransforms()
                                updateMarkerPosition()
                            } else {
                                tvMapError.text = "지도 이미지 디코딩 실패"
                                tvMapError.visibility = View.VISIBLE
                                resetVisualTransforms()
                                updateMarkerPosition()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            pbMapLoading.visibility = View.GONE
                            tvMapError.text = "지도 이미지를 불러오지 못했습니다."
                            tvMapError.visibility = View.VISIBLE
                            resetVisualTransforms()
                            updateMarkerPosition()
                        }
                    }
                } else {
                    val code = response.code
                    val errorBody = try { response.body?.string() ?: "" } catch (e: Exception) { "" }
                    android.util.Log.e("SearchActivity", "StaticMap error ($code): $errorBody")
                    runOnUiThread {
                        pbMapLoading.visibility = View.GONE
                        tvMapError.text = if (errorBody.isNotEmpty()) "지도 응답 오류 ($code):\n$errorBody" else "지도 응답 오류 ($code)"
                        tvMapError.visibility = View.VISIBLE
                        resetVisualTransforms()
                        updateMarkerPosition()
                    }
                }
            }
        })
    }

    private fun showBookmarkDialog(doc: KakaoDocument) {
        val options = arrayOf("🏠 집으로 등록", "🏢 사무실로 등록", "⭐ 즐겨찾기에 추가")
        AlertDialog.Builder(this)
            .setTitle("목적지 등록: ${doc.place_name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        DestinationBookmarkManager.setHome(this, BookmarkItem.fromKakaoDocument(doc, BookmarkItem.TYPE_HOME))
                        Toast.makeText(this, "'${doc.place_name}'이(가) 집으로 등록되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        DestinationBookmarkManager.setOffice(this, BookmarkItem.fromKakaoDocument(doc, BookmarkItem.TYPE_OFFICE))
                        Toast.makeText(this, "'${doc.place_name}'이(가) 사무실로 등록되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        DestinationBookmarkManager.addFavorite(this, BookmarkItem.fromKakaoDocument(doc, BookmarkItem.TYPE_FAVORITE))
                        Toast.makeText(this, "'${doc.place_name}'이(가) 즐겨찾기에 추가되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun handleDestinationSelection(doc: KakaoDocument) {
        val registerTarget = intent.getStringExtra("register_target")
        if (registerTarget != null) {
            when (registerTarget) {
                BookmarkItem.TYPE_HOME -> {
                    DestinationBookmarkManager.setHome(this, BookmarkItem.fromKakaoDocument(doc, BookmarkItem.TYPE_HOME))
                    Toast.makeText(this, "'${doc.place_name}'이(가) 집으로 등록되었습니다.", Toast.LENGTH_SHORT).show()
                }
                BookmarkItem.TYPE_OFFICE -> {
                    DestinationBookmarkManager.setOffice(this, BookmarkItem.fromKakaoDocument(doc, BookmarkItem.TYPE_OFFICE))
                    Toast.makeText(this, "'${doc.place_name}'이(가) 사무실로 등록되었습니다.", Toast.LENGTH_SHORT).show()
                }
                BookmarkItem.TYPE_FAVORITE -> {
                    DestinationBookmarkManager.addFavorite(this, BookmarkItem.fromKakaoDocument(doc, BookmarkItem.TYPE_FAVORITE))
                    Toast.makeText(this, "'${doc.place_name}'이(가) 즐겨찾기에 추가되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            AlertDialog.Builder(this)
                .setTitle("등록 완료")
                .setMessage("'${doc.place_name}' 등록이 완료되었습니다.\n바로 경로 안내를 시작하시겠습니까?")
                .setPositiveButton("안내 시작") { _, _ ->
                    startRouteGuidanceTo(doc)
                }
                .setNegativeButton("닫기") { _, _ ->
                    finish()
                }
                .show()
        } else {
            startRouteGuidanceTo(doc)
        }
    }

    private fun startRouteGuidanceTo(doc: KakaoDocument) {
        // 최근 검색 기록에 저장
        SearchHistoryManager.addHistory(this, SearchHistoryItem.fromKakaoDocument(doc))

        val intent = Intent(this, KakaoMapActivity::class.java).apply {
            putExtra("dest_place_name", doc.place_name)
            putExtra("dest_road_address_name", doc.road_address_name)
            putExtra("dest_address_name", doc.address_name)
            putExtra("dest_x", doc.x)
            putExtra("dest_y", doc.y)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun performSearch() {
        val query = etKeyword.text.toString().trim()
        if (query.isEmpty()) return

        hideKeyboard()
        showSearchResultSection()

        val prefs = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val restApiKey = prefs.getString("KAKAO_REST_API_KEY", "") ?: ""
        val appKey = prefs.getString("APP_KEY", "") ?: ""
        val keyToUse = if (restApiKey.isNotEmpty()) restApiKey else appKey

        if (keyToUse.isEmpty()) {
            Toast.makeText(this, "App Key 또는 REST API Key가 설정되어 있지 않습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val authorizationHeader = "KakaoAK $keyToUse"

        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        rvResults.visibility = View.GONE

        var sortParam: String? = null
        var xParam: String? = null
        var yParam: String? = null
        var radiusParam: Int? = null

        if (rbSortDistance.isChecked) {
            sortParam = "distance"
            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                if (location != null) {
                    xParam = location.longitude.toString()
                    yParam = location.latitude.toString()
                    radiusParam = 20000
                } else {
                    Toast.makeText(this, "현재 위치를 알 수 없어 거리순 정렬을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
                    pbLoading.visibility = View.GONE
                    return
                }
            } catch (e: SecurityException) {
                Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                pbLoading.visibility = View.GONE
                return
            }
        }

        kakaoSearchApi.searchKeyword(authorizationHeader, query, sortParam, xParam, yParam, radiusParam)
            .enqueue(object : retrofit2.Callback<KakaoSearchResponse> {
                override fun onResponse(call: retrofit2.Call<KakaoSearchResponse>, response: retrofit2.Response<KakaoSearchResponse>) {
                    pbLoading.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        val docs = response.body()!!.documents
                        if (docs.isEmpty()) {
                            tvEmpty.visibility = View.VISIBLE
                            clearPreview()
                        } else {
                            rvResults.visibility = View.VISIBLE
                            adapter.submitList(docs)
                        }
                    } else {
                        if (response.code() == 401) {
                            Toast.makeText(this@SearchActivity, "API 키가 올바르지 않습니다 (401)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@SearchActivity, "검색 실패: ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onFailure(call: retrofit2.Call<KakaoSearchResponse>, t: Throwable) {
                    pbLoading.visibility = View.GONE
                    Toast.makeText(this@SearchActivity, "네트워크 오류: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    override fun onDestroy() {
        super.onDestroy()
        activeMapCall?.cancel()
    }
}
