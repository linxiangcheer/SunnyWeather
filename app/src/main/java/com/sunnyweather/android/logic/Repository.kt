package com.sunnyweather.android.logic

import android.content.Context
import androidx.lifecycle.liveData
import com.sunnyweather.android.logic.dao.LngLatCityDao
import com.sunnyweather.android.logic.dao.MenuDao
import com.sunnyweather.android.logic.dao.PlaceDao
import com.sunnyweather.android.logic.model.CitydisWeather
import com.sunnyweather.android.logic.model.LngLatCity
import com.sunnyweather.android.logic.model.Place
import com.sunnyweather.android.logic.model.Weather
import com.sunnyweather.android.logic.network.SunnyWeatherNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.lang.Exception
import kotlin.coroutines.CoroutineContext

/*
* 仓库层
* */
object Repository {

    //不允许在主线程中进行网络请求，所以要在仓库曾进行一次线程转换
    //Dispatchers.IO 所有的代码都在子线程中运行
    fun searchPlaces(query: String) = fire(Dispatchers.IO) {
        val placeResponse = SunnyWeatherNetwork.searchPlaces(query)
        if (placeResponse.status == "ok") { //判断服务器相应的状态时ok
            val places = placeResponse.places
            Result.success(places) //包装获取的城市数据列表
        } else {
            Result.failure(RuntimeException("response status is ${placeResponse.status}"))
        }
    }


    fun refreshWeatherList(locationlist: List<String>) = fire(Dispatchers.IO){
        var weatherlist = mutableListOf<Weather>()
        var resultNum = 0
        coroutineScope {
            for(location in locationlist){
                val deferredRealtime = async {
                    SunnyWeatherNetwork.getRealtimeWeather(location)
                }
                val deferredDaily = async {
                    SunnyWeatherNetwork.getDailyWeather(location)
                }
                val realtimeResponse = deferredRealtime.await()
                val dailyResponse = deferredDaily.await()
                if (realtimeResponse.status == "ok" && dailyResponse.status == "ok") { //成功 包装获取到的实时天气信息和未来天气信息
                    val weather = Weather(realtimeResponse.result.realtime,
                        dailyResponse.result.daily)
                    weatherlist.add(weather) //将天气数据添加到weatherlist中
                } else { //失败,打印具体情况
                    resultNum = 1
                    break
                }
            }
            //进行判断是否有请求错误的，如果有Result.failure,如果没有Result.success
            if (resultNum == 0){
                Result.success(weatherlist)
            } else {
                Result.failure(RuntimeException("请求失败！"))
            }
        }
    }


    fun refreshWeather(location: String) = fire(Dispatchers.IO) {
        /*
        * 创建协程作用域
        * 协程作用域内，用async函数发起网络请求，可以保证在两个网络请求成功响应之后才会进一步执行程序
        * */
        coroutineScope {
            val deferredRealtime = async {
                SunnyWeatherNetwork.getRealtimeWeather(location)
            }
            val deferredDaily = async {
                SunnyWeatherNetwork.getDailyWeather(location)
            }
            val realtimeResponse = deferredRealtime.await()
            val dailyResponse = deferredDaily.await()
            if (realtimeResponse.status == "ok" && dailyResponse.status == "ok") { //成功 包装获取到的实时天气信息和未来天气信息
                val weather = Weather(
                    realtimeResponse.result.realtime,
                    dailyResponse.result.daily
                )
                Result.success(weather)
            } else { //失败,打印具体情况
                Result.failure(
                    RuntimeException(
                        "realtime response status is ${realtimeResponse.status} " +
                                "daily response status is ${dailyResponse.status}"
                    )
                )
            }
        }
    }

    //获取ip地址对应的城市名
    fun getQueryIp_LngLat() = fire(Dispatchers.IO) {
        coroutineScope {
            val deferredQueryIp = async { SunnyWeatherNetwork.getQueryIp() }
            val queryIpResponse = deferredQueryIp.await()
            if (queryIpResponse.status == "1") {
                val province = queryIpResponse.province
                val city = queryIpResponse.city

                val deferredQueryLngLat =
                    async { SunnyWeatherNetwork.getQueryLngLat("$province$city") }
                val queryLngLatResponse = deferredQueryLngLat.await()
                if (queryLngLatResponse.status == "1") {

                    val geocodes = queryLngLatResponse.geocodes
                    val lnglatcity = LngLatCity(geocodes[0].location, geocodes[0].city)

                    Result.success(lnglatcity)
                } else {
                    Result.failure(
                        RuntimeException("queryip response status is ${queryLngLatResponse.status} ${queryLngLatResponse.info} ")
                    )
                }
            } else {
                Result.failure(
                    RuntimeException("queryip response status is ${queryIpResponse.status} ${queryIpResponse.info} ")
                )
            }
        }
    }

    //获取lnglat
    fun getQuery_LngLat(address: String) = fire(Dispatchers.IO) {
        coroutineScope {
            val deferredQueryLngLat = async { SunnyWeatherNetwork.getQueryLngLat(address) }
            val queryLngLatResponse = deferredQueryLngLat.await()
            if (queryLngLatResponse.status == "1") {
                Result.success(queryLngLatResponse.geocodes[0].location)
            } else {
                Result.failure(
                    RuntimeException("queryip response status is ${queryLngLatResponse.status} ${queryLngLatResponse.info} ")
                )
            }
        }
    }


    /*
    * 一个按照liveData()函数的参数接受标准定义的一个高阶函数
    * 在liveData()函数代码中进行统一的try catch处理
    * 并在try语句中调用传入的Lambda表达式中的代码
    * 最终获取Lambda表达式的执行结果并调用emit()方法发射出去
    *
    * 注意，在liveData()函数代码块中我们是拥有挂起函数上下文的，可当回调到Lambdaa表达式中代码就没有挂起函数上下文了，
    * 但实际上Lambda表达式中的代码一定也是在挂起函数中运行的，
    * 所以我们需要声明一个suspend关键字表示在所有传入的Lambda表达式中的代码也是拥有挂起函数上下文的
    *
    * suspend是函数的创建者对函数的调用者的提醒，"我"是一个耗时操作，被我的创建者放在了后台运行
    * */
    private fun <T> fire(context: CoroutineContext, block: suspend () -> Result<T>) =
        liveData<Result<T>>(context) {
            val result = try {
                block()
            } catch (e: Exception) {
                Result.failure<T>(e)
            }
            emit(result)
        }

    /*
    * 即使对SharedPreferences文件进行读写操作，也不建议在主线程中进行
    * 最佳的实现方式是开启一个线程来执行这些比较耗时的任务
    * 然后通过LiveData对象进行数据返回
    * */
    fun savePlace(place: Place) = PlaceDao.savePlace(place) //将数据存储到本地
    fun getSavedPlace() = PlaceDao.getSavedPlace() //获取本地存储的数据
    fun isPlacesSaved() = PlaceDao.isPlaceSaved() //判断本地是否有数据


    fun saveLngLatCity(lnglatCity: String) = LngLatCityDao.saveLngLatCity(lnglatCity)
    fun getSavedLngLatCity() = LngLatCityDao.getSavedLngLatCity()
    fun isLngLatCitySaved() = LngLatCityDao.isLngLatCitySaved()


    fun CreateMenuSqlDao(context: Context, name: String, version: Int) = MenuDao.CreateMenuSqlDao(context, name, version)

}