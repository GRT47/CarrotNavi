package com.example.carrotnavi

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

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

    private lateinit var etKeyword: EditText
    private lateinit var btnSearch: Button
    private lateinit var rgSort: RadioGroup
    private lateinit var rbSortDistance: RadioButton
    private lateinit var rvResults: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var toolbar: MaterialToolbar

    private lateinit var adapter: AddressSearchAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        
        toolbar.setNavigationOnClickListener {
            finish()
        }

        etKeyword = findViewById(R.id.etSearchKeyword)
        btnSearch = findViewById(R.id.btnSearch)

        intent.getStringExtra("initial_query")?.let {
            if (it.isNotEmpty()) {
                etKeyword.setText(it)
                // Optionally perform search automatically:
                // etKeyword.post { btnSearch.performClick() }
            }
        }
        rgSort = findViewById(R.id.rgSort)
        rbSortDistance = findViewById(R.id.rbSortDistance)
        rvResults = findViewById(R.id.rvSearchResults)
        pbLoading = findViewById(R.id.pbLoading)
        tvEmpty = findViewById(R.id.tvEmptyResult)

        adapter = AddressSearchAdapter { doc ->
            // Launch KakaoMapActivity with the selected destination
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

        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter

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
    }

    private fun performSearch() {
        val query = etKeyword.text.toString().trim()
        if (query.isEmpty()) return

        // Hide keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etKeyword.windowToken, 0)

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
            .enqueue(object : Callback<KakaoSearchResponse> {
                override fun onResponse(call: Call<KakaoSearchResponse>, response: Response<KakaoSearchResponse>) {
                    pbLoading.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        val docs = response.body()!!.documents
                        if (docs.isEmpty()) {
                            tvEmpty.visibility = View.VISIBLE
                        } else {
                            rvResults.visibility = View.VISIBLE
                            adapter.submitList(docs)
                        }
                    } else {
                        if (response.code() == 401) {
                            Toast.makeText(this@SearchActivity, "API 인증 실패 (401). REST API 키를 확인해주세요.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@SearchActivity, "검색 실패: ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                        tvEmpty.text = "검색 오류 발생"
                        tvEmpty.visibility = View.VISIBLE
                    }
                }

                override fun onFailure(call: Call<KakaoSearchResponse>, t: Throwable) {
                    pbLoading.visibility = View.GONE
                    Toast.makeText(this@SearchActivity, "네트워크 오류: ${t.message}", Toast.LENGTH_SHORT).show()
                    tvEmpty.text = "네트워크 오류 발생"
                    tvEmpty.visibility = View.VISIBLE
                }
            })
    }
}
